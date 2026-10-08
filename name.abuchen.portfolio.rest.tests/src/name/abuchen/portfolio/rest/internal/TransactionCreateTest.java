package name.abuchen.portfolio.rest.internal;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.ServiceLoader;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import org.eclipse.core.runtime.preferences.InstanceScope;
import org.junit.Test;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import name.abuchen.portfolio.checks.Check;
import name.abuchen.portfolio.model.Account;
import name.abuchen.portfolio.model.AccountTransaction;
import name.abuchen.portfolio.model.BuySellEntry;
import name.abuchen.portfolio.model.Client;
import name.abuchen.portfolio.model.Portfolio;
import name.abuchen.portfolio.model.Security;
import name.abuchen.portfolio.rest.ApiRoutes;
import name.abuchen.portfolio.rest.FileAccessRegistry;
import name.abuchen.portfolio.rest.testsupport.FakeHost;

@SuppressWarnings("nls")
public class TransactionCreateTest
{
    private final Client client = new Client();
    private final Account account = new Account();
    private final Security security = new Security();
    private final Portfolio portfolio = new Portfolio();
    private final AtomicInteger dirty = new AtomicInteger();

    public TransactionCreateTest()
    {
        account.setCurrencyCode("EUR");
        account.setName("Cash");
        client.addAccount(account);
        security.setName("Instrument");
        security.setCurrencyCode("EUR");
        client.addSecurity(security);
        portfolio.setName("Investments");
        portfolio.setReferenceAccount(account);
        client.addPortfolio(portfolio);
        client.addPropertyChangeListener("dirty", event -> dirty.incrementAndGet());
    }

    private JsonObject body(String type)
    {
        var body = JsonParser.parseString("""
                        {"type":"deposit", "dateTime":"2026-01-02T12:30:00",
                         "amount":{"value":12.34,"currency":"EUR"}}
                        """).getAsJsonObject();
        body.addProperty("type", type);
        var reference = new JsonObject();
        reference.addProperty("uuid", account.getUUID());
        body.add("cashAccount", reference);
        return body;
    }

    private void instrument(JsonObject body)
    {
        var reference = new JsonObject();
        reference.addProperty("uuid", security.getUUID());
        body.add("instrument", reference);
    }

    private JsonObject create(JsonObject body)
    {
        return TransactionsHandler.create(client, body, "Paired client");
    }

    private ApiException rejected(JsonObject body)
    {
        int count = account.getTransactions().size();
        int investments = portfolio.getTransactions().size();
        int changes = dirty.get();
        var error = assertThrows(ApiException.class, () -> create(body));
        assertEquals(422, error.getStatus());
        assertEquals(count, account.getTransactions().size());
        assertEquals(investments, portfolio.getTransactions().size());
        assertEquals(changes, dirty.get());
        return error;
    }

    private void has(ApiException error, String field, String code)
    {
        assertTrue(error.getErrors().toString(), error.getErrors().stream()
                        .anyMatch(value -> value.field().equals(field) && value.code().equals(code)));
    }

    private JsonObject investmentBody(String type)
    {
        var body = body(type);
        instrument(body);
        var reference = new JsonObject();
        reference.addProperty("uuid", portfolio.getUUID());
        body.add("investmentAccount", reference);
        body.add("shares", JsonParser.parseString("1.23456789"));
        if (type.startsWith("delivery"))
            body.remove("cashAccount");
        return body;
    }

    private void assertCrossEntriesConsistent()
    {
        var check = ServiceLoader.load(Check.class, Check.class.getClassLoader()).stream()
                        .filter(provider -> provider.type().getName().equals("name.abuchen.portfolio.checks.impl.CrossEntryCheck"))
                        .findFirst().orElseThrow().get();
        assertTrue(check.execute(client).isEmpty());
    }

    @Test
    public void createsInvestmentFamiliesWithCanonicalIdentityAndLinkedCash()
    {
        for (var type : List.of("buy", "sell", "delivery-inbound", "delivery-outbound"))
        {
            var body = investmentBody(type);
            body.addProperty("note", "Investment");
            var json = create(body);
            var transaction = portfolio.getTransactions().getLast();
            assertEquals(type, json.get("type").getAsString());
            assertEquals(transaction.getUUID(), json.get("uuid").getAsString());
            assertEquals(123456789L, transaction.getShares());
            assertEquals(1234L, transaction.getAmount());
            if (type.equals("buy") || type.equals("sell"))
                assertTrue(transaction.getCrossEntry() instanceof BuySellEntry);
            if (transaction.getCrossEntry() instanceof BuySellEntry entry)
            {
                var cash = entry.getAccountTransaction();
                assertTrue(account.getTransactions().contains(cash));
                assertSame(entry, cash.getCrossEntry());
                assertSame(portfolio, entry.getPortfolio());
                assertSame(account, entry.getAccount());
                assertEquals(0L, cash.getShares());
                assertEquals("Investment", cash.getNote());
                assertEquals("Paired client", cash.getSource());
                assertEquals(json, TransactionsHandler.get(client, cash.getUUID()));
            }
            assertEquals(json, TransactionsHandler.get(client, transaction.getUUID()));
            assertCrossEntriesConsistent();
        }
        assertEquals(2, account.getTransactions().size());
        assertEquals(4, dirty.get());
    }

    @Test
    public void rejectsInvestmentRulesWithoutMutatingEitherOwner()
    {
        for (var type : List.of("buy", "sell", "delivery-inbound", "delivery-outbound"))
        {
            var body = investmentBody(type);
            body.addProperty("shares", 0);
            body.remove("instrument");
            body.addProperty("grossValue", 12.34);
            var error = rejected(body);
            has(error, "shares", "shares-required");
            has(error, "instrument", "instrument-required");
            has(error, "grossValue", "unknown-field");
            body = investmentBody(type);
            body.getAsJsonObject("amount").addProperty("value", 0);
            if (type.equals("delivery-outbound"))
                assertEquals(0, create(body).getAsJsonObject("grossValue").get("value").getAsInt());
            else
                has(rejected(body), "amount", type.equals("sell") ? "use-outbound-delivery" : "gross-value-required");
        }
        for (var type : List.of("buy", "sell"))
        {
            var body = investmentBody(type);
            body.getAsJsonObject("cashAccount").addProperty("uuid", "unknown");
            has(rejected(body), "cashAccount", "unknown-entity");
        }
        portfolio.setReferenceAccount(null);
        for (var type : List.of("delivery-inbound", "delivery-outbound"))
            has(rejected(investmentBody(type)), "investmentAccount", "reference-account-required");
    }

    @Test
    public void rejectsBuySellCurrencyDifferentFromCashAccountWithoutMutation()
    {
        account.setCurrencyCode("USD");
        for (var type : List.of("buy", "sell"))
        {
            var error = rejected(investmentBody(type));
            has(error, "amount.currency", "currency-mismatch");
            assertEquals(1, error.getErrors().size());
        }
    }

    @Test
    public void deliveryCurrencyIsIndependentOfReferenceCashAccount()
    {
        account.setCurrencyCode("USD");
        for (var type : List.of("delivery-inbound", "delivery-outbound"))
        {
            var json = create(investmentBody(type));
            assertEquals("EUR", json.getAsJsonObject("amount").get("currency").getAsString());
            assertEquals("EUR", portfolio.getTransactions().getLast().getCurrencyCode());
        }
        assertEquals(0, account.getTransactions().size());
        assertEquals(2, dirty.get());
    }

    @Test
    public void acceptsZeroNetOutboundDeliveryWithPositiveGross()
    {
        var body = investmentBody("delivery-outbound");
        body.getAsJsonObject("amount").addProperty("value", 0);
        body.add("units", JsonParser.parseString("""
                        [{"type":"fee","amount":{"value":1,"currency":"EUR"}},
                         {"type":"tax","amount":{"value":2,"currency":"EUR"}}]
                        """));
        assertEquals(3, create(body).getAsJsonObject("grossValue").get("value").getAsInt());
        assertEquals(0, account.getTransactions().size());
    }

    @Test
    public void validatesInvestmentForexGrossForEachFamily()
    {
        security.setCurrencyCode("USD");
        for (var type : List.of("buy", "sell", "delivery-inbound", "delivery-outbound"))
        {
            var body = investmentBody(type);
            body.getAsJsonObject("amount").addProperty("value", type.equals("buy") || type.equals("delivery-inbound") ? 81 : 79);
            body.add("units", JsonParser.parseString("""
                            [{"type":"gross-value","amount":{"value":80,"currency":"EUR"},
                              "forex":{"value":100,"currency":"USD"},"exchangeRate":0.8},
                             {"type":"fee","amount":{"value":1,"currency":"EUR"}}]
                            """));
            assertEquals(80, create(body).getAsJsonObject("grossValue").get("value").getAsInt());
            assertCrossEntriesConsistent();
            body.getAsJsonObject("amount").addProperty("value", 78.99);
            has(rejected(body), "units", "gross-value-mismatch");
        }
    }

    @Test
    public void createsEveryCashTypeAndMarksDirty()
    {
        var names = List.of("deposit", "removal", "interest", "interest-charge", "fee", "fee-refund", "tax",
                        "tax-refund", "dividend");
        var types = List.of(AccountTransaction.Type.DEPOSIT, AccountTransaction.Type.REMOVAL,
                        AccountTransaction.Type.INTEREST, AccountTransaction.Type.INTEREST_CHARGE,
                        AccountTransaction.Type.FEES, AccountTransaction.Type.FEES_REFUND,
                        AccountTransaction.Type.TAXES, AccountTransaction.Type.TAX_REFUND,
                        AccountTransaction.Type.DIVIDENDS);
        for (int index = 0; index < names.size(); index++)
        {
            var input = body(names.get(index));
            if (names.get(index).equals("dividend"))
                instrument(input);
            var entity = create(input);
            assertEquals(names.get(index), entity.get("type").getAsString());
            assertEquals("Paired client", entity.get("source").getAsString());
            assertEquals(types.get(index), account.getTransactions().get(index).getType());
            assertEquals(entity, TransactionsHandler.get(client, entity.get("uuid").getAsString()));
        }
        assertEquals(names.size(), dirty.get());
    }

    @Test
    public void acceptsExactModelPrecisionAndOptionalMetadata()
    {
        var body = body("dividend");
        instrument(body);
        body.getAsJsonObject("amount").add("value", JsonParser.parseString("12.3400"));
        body.add("shares", JsonParser.parseString("1.2345678900"));
        body.addProperty("note", "Dividend");
        body.addProperty("source", "Explicit source");
        body.addProperty("exDate", "2026-01-02T23:59:59");
        var json = create(body);
        assertEquals("Explicit source", json.get("source").getAsString());
        assertEquals(1234, account.getTransactions().getFirst().getAmount());
        assertEquals(123456789, account.getTransactions().getFirst().getShares());
        body.add("source", com.google.gson.JsonNull.INSTANCE);
        assertFalse(create(body).has("source"));
    }

    @Test
    public void aggregatesUnknownFieldsPrecisionAndReferencesWithoutMutation()
    {
        var body = body("dividend");
        body.addProperty("grossValue", 10);
        body.addProperty("uuid", "read-only");
        body.getAsJsonObject("cashAccount").addProperty("name", "read-only");
        body.getAsJsonObject("cashAccount").addProperty("uuid", "missing");
        body.getAsJsonObject("amount").addProperty("typo", true);
        body.getAsJsonObject("amount").add("value", JsonParser.parseString("1.001"));
        body.add("shares", JsonParser.parseString("1.000000001"));
        var errors = rejected(body);
        has(errors, "grossValue", "unknown-field");
        has(errors, "uuid", "unknown-field");
        has(errors, "cashAccount.name", "unknown-field");
        has(errors, "cashAccount", "unknown-entity");
        has(errors, "amount.typo", "unknown-field");
        has(errors, "amount.value", "too-many-decimals");
        has(errors, "shares", "too-many-decimals");
        has(errors, "instrument", "instrument-required");
    }

    @Test
    public void requiresStrictLocalSecondsAndRejectsOverflow()
    {
        for (var date : List.of("2026-01-02", "2026-01-02T12:30", "2026-01-02T12:30:00Z",
                        "2026-02-30T12:30:00", "2026-01-02T12:30:00.0"))
        {
            var body = body("deposit");
            body.addProperty("dateTime", date);
            has(rejected(body), "dateTime", "invalid-date");
        }
        for (var amount : List.of("92233720368547758.08", "1e100000", "1e2147483648"))
        {
            var body = body("deposit");
            body.getAsJsonObject("amount").add("value", JsonParser.parseString(amount));
            has(rejected(body), "amount.value", "out-of-range");
        }
    }

    @Test
    public void enforcesCashDialogRules()
    {
        var body = body("deposit");
        body.addProperty("shares", 1);
        instrument(body);
        body.addProperty("exDate", "2026-01-03T00:00:00");
        body.add("units", JsonParser.parseString("""
                        [{"type":"fee","amount":{"value":1,"currency":"EUR"}},
                         {"type":"tax","amount":{"value":1,"currency":"EUR"}}]
                        """));
        var error = rejected(body);
        has(error, "shares", "shares-not-allowed");
        has(error, "units", "fees-not-allowed");
        has(error, "units", "taxes-not-allowed");
        has(error, "exDate", "ex-date-after-date");
        var zero = body("deposit");
        zero.getAsJsonObject("amount").addProperty("value", 0);
        has(rejected(zero), "amount", "gross-value-required");
        var interest = body("interest");
        interest.getAsJsonObject("amount").addProperty("value", 0);
        interest.add("units", JsonParser.parseString("""
                        [{"type":"tax","amount":{"value":1,"currency":"EUR"}}]
                        """));
        assertEquals(1, create(interest).getAsJsonObject("grossValue").get("value").getAsInt());
    }

    @Test
    public void validatesForexGrossWithoutCorrectingClientNumbers()
    {
        security.setCurrencyCode("USD");
        var body = body("dividend");
        instrument(body);
        body.getAsJsonObject("amount").addProperty("value", 79);
        body.add("units", JsonParser.parseString("""
                        [{"type":"gross-value","amount":{"value":80,"currency":"EUR"},
                          "forex":{"value":100,"currency":"USD"},"exchangeRate":0.8},
                         {"type":"tax","amount":{"value":1,"currency":"EUR"}}]
                        """));
        assertEquals(80, create(body).getAsJsonObject("grossValue").get("value").getAsInt());
        body.getAsJsonObject("amount").addProperty("value", 78.99);
        has(rejected(body), "units", "gross-value-mismatch");
        body.getAsJsonArray("units").get(0).getAsJsonObject().remove("forex");
        has(rejected(body), "units[0].forex", "forex-required");
    }

    @Test
    public void routeReturnsLocationAndGatesWritesWhileEditing() throws Exception
    {
        var node = InstanceScope.INSTANCE.getNode("rest-test-" + UUID.randomUUID());
        try
        {
            var path = "/tmp/transaction-create.portfolio";
            var registry = new FileAccessRegistry(node);
            registry.setEnabled(path, true);
            registry.setAlias(path, "sample");
            var host = new FakeHost(List.of(new FakeHost.FakeOpenFile(path, "Sample", client)));
            var router = ApiRoutes.create(registry, host, null);
            var uri = "/v1/files/sample/transactions";
            var route = router.match("POST", uri);
            for (var type : List.of("deposit", "buy", "sell", "delivery-inbound", "delivery-outbound"))
            {
                var body = type.equals("deposit") ? body(type) : investmentBody(type);
                var request = new Request("POST", uri, route.pathParams(), Map.of(),
                                body.toString().getBytes(StandardCharsets.UTF_8),
                                Request.Authorization.VALID, null, "Paired client");
                int cashCount = account.getTransactions().size();
                int investmentCount = portfolio.getTransactions().size();
                int changes = dirty.get();
                host.setUserEditing(true);
                var error = assertThrows(ApiException.class, () -> route.handler().handle(request));
                assertEquals(423, error.getStatus());
                assertEquals(cashCount, account.getTransactions().size());
                assertEquals(investmentCount, portfolio.getTransactions().size());
                assertEquals(changes, dirty.get());
                host.setUserEditing(false);
                var response = route.handler().handle(request);
                assertEquals(201, response.status());
                var transaction = type.equals("deposit") ? account.getTransactions().getLast()
                                : portfolio.getTransactions().getLast();
                assertEquals(uri + "/" + transaction.getUUID(), response.headers().get("Location"));
                assertEquals(changes + 1, dirty.get());
            }
            assertFalse(host.hasAccessedOutsideUIThread());
        }
        finally
        {
            node.removeNode();
        }
    }

    @Test
    public void rejectsEmptyCurrencyBeforeConstructingMoney()
    {
        var body = body("dividend");
        instrument(body);
        body.getAsJsonObject("amount").addProperty("currency", "");
        body.add("units", JsonParser.parseString("""
                        [{"type":"tax","amount":{"value":1,"currency":""}},
                         {"type":"gross-value","amount":{"value":12.34,"currency":"EUR"},
                          "forex":{"value":12.34,"currency":""},"exchangeRate":1}]
                        """));
        var error = rejected(body);
        has(error, "amount.currency", "unsupported-currency");
        has(error, "units[0].amount.currency", "unsupported-currency");
        has(error, "units[1].forex.currency", "unsupported-currency");
    }

    @Test
    public void handlesExtremeTinyAmountsAndRatesWithoutArithmeticFailure()
    {
        var tiny = body("deposit");
        tiny.getAsJsonObject("amount").add("value", JsonParser.parseString("1e-2147483647"));
        has(rejected(tiny), "amount.value", "out-of-range");
        tiny.getAsJsonObject("amount").add("value", JsonParser.parseString("0e-2147483647"));
        has(rejected(tiny), "amount.value", "out-of-range");
        tiny.getAsJsonObject("amount").add("value", JsonParser.parseString("1e-9999"));
        has(rejected(tiny), "amount.value", "too-many-decimals");
        tiny.getAsJsonObject("amount").add("value", JsonParser.parseString("0e-9999"));
        has(rejected(tiny), "amount", "gross-value-required");
        security.setCurrencyCode("USD");
        for (var rate : List.of("1e2147483647", "1e-2147483647"))
        {
            var body = body("dividend");
            instrument(body);
            body.add("units", JsonParser.parseString("""
                            [{"type":"gross-value","amount":{"value":12.34,"currency":"EUR"},
                              "forex":{"value":12.34,"currency":"USD"},"exchangeRate":%s}]
                            """.formatted(rate)));
            has(rejected(body), "units[0].exchangeRate", "out-of-range");
        }
    }

    @Test
    public void rejectsMalformedNestedValuesAndSameCurrencyGross()
    {
        var body = body("dividend");
        instrument(body);
        body.add("units", JsonParser.parseString("""
                        [false, {"type":"gross-value","amount":{"value":12.34,"currency":"EUR"},
                          "forex":{"value":12.34,"currency":"EUR"},"exchangeRate":1,"extra":true},
                         {"type":"fee","amount":{"value":"1","currency":"EUR"}}]
                        """));
        var error = rejected(body);
        has(error, "units[0]", "invalid-type");
        has(error, "units[1].extra", "unknown-field");
        has(error, "units[1].forex", "forex-not-allowed");
        has(error, "units[2].amount.value", "invalid-type");
    }
}
