package name.abuchen.portfolio.rest.internal;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.ServiceLoader;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import org.eclipse.core.runtime.ILogListener;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.Platform;
import org.eclipse.core.runtime.preferences.InstanceScope;
import org.junit.Test;
import org.osgi.framework.FrameworkUtil;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import name.abuchen.portfolio.PortfolioLog;
import name.abuchen.portfolio.checks.Check;
import name.abuchen.portfolio.model.Account;
import name.abuchen.portfolio.model.AccountTransaction;
import name.abuchen.portfolio.model.Client;
import name.abuchen.portfolio.model.InvestmentPlan;
import name.abuchen.portfolio.model.Portfolio;
import name.abuchen.portfolio.model.PortfolioTransaction;
import name.abuchen.portfolio.model.Security;
import name.abuchen.portfolio.model.Transaction;
import name.abuchen.portfolio.money.Money;
import name.abuchen.portfolio.rest.ApiRoutes;
import name.abuchen.portfolio.rest.FileAccessRegistry;
import name.abuchen.portfolio.rest.testsupport.FakeHost;

@SuppressWarnings("nls")
public class TransactionPatchTest
{
    private static final List<String> TYPES = List.of("buy", "sell", "delivery-inbound", "delivery-outbound",
                    "cash-transfer", "security-transfer", "deposit", "removal", "interest", "interest-charge",
                    "fee", "fee-refund", "tax", "tax-refund", "dividend");
    private final Client client = new Client();
    private final Account account = new Account();
    private final Account secondAccount = new Account();
    private final Portfolio portfolio = new Portfolio();
    private final Portfolio secondPortfolio = new Portfolio();
    private final Security security = new Security();
    private final AtomicInteger dirty = new AtomicInteger();

    public TransactionPatchTest()
    {
        for (var cash : List.of(account, secondAccount))
        {
            cash.setCurrencyCode("EUR");
            cash.setName("Cash");
            client.addAccount(cash);
        }
        for (var investment : List.of(portfolio, secondPortfolio))
        {
            investment.setReferenceAccount(account);
            investment.setName("Investments");
            client.addPortfolio(investment);
        }
        security.setCurrencyCode("EUR");
        security.setName("Instrument");
        client.addSecurity(security);
        client.addPropertyChangeListener("dirty", event -> dirty.incrementAndGet());
    }

    private static JsonObject json(String text)
    {
        return JsonParser.parseString(text).getAsJsonObject();
    }

    private static void reference(JsonObject body, String field, String uuid)
    {
        var ref = new JsonObject();
        ref.addProperty("uuid", uuid);
        body.add(field, ref);
    }

    private Transaction create(String type)
    {
        var body = json("""
                        {"dateTime":"2026-01-02T12:30:00","amount":{"value":100,"currency":"EUR"}}
                        """);
        body.addProperty("type", type);
        if (type.equals("cash-transfer"))
        {
            reference(body, "fromCashAccount", account.getUUID());
            reference(body, "toCashAccount", secondAccount.getUUID());
            body.add("targetAmount", body.get("amount").deepCopy());
        }
        else if (type.equals("security-transfer"))
        {
            reference(body, "fromInvestmentAccount", portfolio.getUUID());
            reference(body, "toInvestmentAccount", secondPortfolio.getUUID());
        }
        else if (!type.startsWith("delivery"))
            reference(body, "cashAccount", account.getUUID());
        if (type.equals("buy") || type.equals("sell") || type.startsWith("delivery"))
            reference(body, "investmentAccount", portfolio.getUUID());
        if (type.equals("buy") || type.equals("sell") || type.startsWith("delivery")
                        || type.equals("security-transfer") || type.equals("dividend"))
        {
            reference(body, "instrument", security.getUUID());
            body.addProperty("shares", 1);
        }
        var uuid = TransactionsHandler.create(client, body, "Original client").get("uuid").getAsString();
        return client.getAllTransactions().stream().map(pair -> (Transaction) pair.getTransaction())
                        .filter(transaction -> uuid.equals(transaction.getUUID())).findFirst().orElseThrow();
    }

    @Test
    public void metadataPatchPreservesHistoricalForeignChargeRounding()
    {
        for (var type : List.of("dividend", "delivery-inbound", "buy"))
        {
            var transaction = create(type);
            security.setCurrencyCode("USD");
            var rate = new BigDecimal("0.7");
            transaction.setAmount(type.equals("dividend") ? 669 : 731);
            if (transaction.getCrossEntry() != null)
                transaction.getCrossEntry().getCrossTransaction(transaction).setAmount(731);
            transaction.addUnit(new Transaction.Unit(Transaction.Unit.Type.GROSS_VALUE,
                            Money.of("EUR", 700), Money.of("USD", 1000), rate));
            transaction.addUnit(new Transaction.Unit(Transaction.Unit.Type.TAX,
                            Money.of("EUR", 31), Money.of("USD", 45), rate));
            var units = transaction.getUnits().toList();
            long amount = transaction.getAmount();
            TransactionsHandler.patch(client, transaction.getUUID(), json("{\"note\":\"edited\"}"));
            assertEquals("edited", transaction.getNote());
            assertEquals(amount, transaction.getAmount());
            assertEquals(units, transaction.getUnits().toList());
            security.setCurrencyCode("EUR");
        }
    }

    @Test
    public void preparesDetachedCopiesWithoutChangingStoredRecords()
    {
        for (var type : TYPES)
        {
            var transaction = create(type);
            transaction.setDateTime(transaction.getDateTime().withNano(123456789));
            var cross = transaction.getCrossEntry();
            var other = cross == null ? null : cross.getCrossTransaction(transaction);
            if (other != null)
            {
                other.setDateTime(transaction.getDateTime());
                other.setSource("Counterpart source");
            }
            var before = TransactionsHandler.get(client, transaction.getUUID());
            int changes = dirty.get();
            var owner = cross == null ? transaction instanceof AccountTransaction ? account : portfolio
                            : cross.getOwner(transaction);
            var target = new TransactionInput().patch(client, new TransactionsHandler.Event(owner, transaction),
                            json("{\"note\":\"edit\"}"));
            assertNotSame(transaction, target.transaction());
            assertSame(owner, target.owner());
            assertSame(transaction.getSecurity(), target.transaction().getSecurity());
            assertEquals(transaction.getDateTime(), target.transaction().getDateTime());
            assertEquals("edit", target.transaction().getNote());
            if (other != null)
            {
                assertNotSame(cross, target.transaction().getCrossEntry());
                var targetOther = target.transaction().getCrossEntry().getCrossTransaction(target.transaction());
                assertNotSame(other, targetOther);
                assertEquals("edit", targetOther.getNote());
                assertEquals("Counterpart source", targetOther.getSource());
                assertEquals(other.getDateTime(), targetOther.getDateTime());
                assertEquals(null, other.getNote());
            }
            assertEquals(before, TransactionsHandler.get(client, transaction.getUUID()));
            assertEquals(changes, dirty.get());
        }
    }

    @Test
    public void explicitUnitsReplaceLegacyCounterpartUnitsOnForexTransfer()
    {
        var transaction = create("cash-transfer");
        var other = transaction.getCrossEntry().getCrossTransaction(transaction);
        secondAccount.setCurrencyCode("USD");
        other.setMonetaryAmount(Money.of("USD", 12500));
        other.addUnit(new Transaction.Unit(Transaction.Unit.Type.GROSS_VALUE, Money.of("USD", 12500),
                        Money.of("EUR", 10000), new BigDecimal("1.25")));
        error(rejected(transaction, json("{\"note\":\"edit\"}")), "units", "units-not-allowed");
        assertEquals(1, other.getUnits().count());

        var patch = json("""
                        {"amount":{"value":200},"targetAmount":{"value":250},
                         "units":[{"type":"gross-value","amount":{"value":200,"currency":"EUR"},
                                   "forex":{"value":250,"currency":"USD"},"exchangeRate":0.8}]}
                        """);
        assertTrue(TransactionsHandler.patch(client, other.getUUID(), patch).changed());
        assertEquals(0, other.getUnits().count());
        assertEquals(1, transaction.getUnits().count());
        assertEquals(20000, transaction.getAmount());
        assertEquals(25000, other.getAmount());
        assertFalse(TransactionsHandler.patch(client, transaction.getUUID(), patch).changed());
    }

    @Test
    public void editsAllTypesThroughEitherUuidKeepingOwnersLinksAndPlanMembership()
    {
        var check = ServiceLoader.load(Check.class, Check.class.getClassLoader()).stream()
                        .filter(provider -> provider.type().getName().equals("name.abuchen.portfolio.checks.impl.CrossEntryCheck"))
                        .findFirst().orElseThrow().get();
        for (var type : TYPES)
        {
            var transaction = create(type);
            var cross = transaction.getCrossEntry();
            var other = cross == null ? transaction : cross.getCrossTransaction(transaction);
            var plan = new InvestmentPlan();
            plan.getTransactions().add(transaction);
            if (other != transaction)
                plan.getTransactions().add(other);
            client.addPlan(plan);
            var members = List.copyOf(plan.getTransactions());
            var pairs = List.copyOf(client.getAllTransactions());
            for (var address : List.of(transaction, other))
            {
                var patch = json("""
                                {"amount":{"value":120},"dateTime":"2026-02-03T10:20:30"}
                                """);
                patch.addProperty("note", UUID.randomUUID().toString());
                if (type.equals("cash-transfer"))
                    patch.add("targetAmount", json("{\"value\":120}"));
                if (transaction.getSecurity() != null)
                    patch.addProperty("shares", 2.12345678);
                int changes = dirty.get();
                var result = TransactionsHandler.patch(client, address.getUUID(), patch);
                assertTrue(result.changed());
                assertEquals(changes + 1, dirty.get());
                assertEquals(transaction.getUUID(), result.entity().get("uuid").getAsString());
                assertEquals(12000, transaction.getAmount());
                assertEquals("Original client", transaction.getSource());
                assertEquals(members, plan.getTransactions());
                assertEquals(pairs.size(), client.getAllTransactions().size());
                for (var pair : pairs)
                    assertTrue(pair.getOwner().getTransactions().contains(pair.getTransaction()));
                assertSame(cross, transaction.getCrossEntry());
                if (cross != null)
                {
                    assertSame(other, cross.getCrossTransaction(transaction));
                    assertSame(cross, other.getCrossEntry());
                    assertEquals(12000, other.getAmount());
                    assertEquals(transaction.getDateTime(), other.getDateTime());
                    assertEquals(transaction.getNote(), other.getNote());
                    assertEquals(result.entity(), TransactionsHandler.get(client, other.getUUID()));
                }
                assertTrue(check.execute(client).isEmpty());
            }
        }
    }

    @Test
    public void refusesIncompleteEventsWithoutChangingTheSurvivingRecord()
    {
        for (var type : List.of("BUY", "SELL", "TRANSFER_IN", "TRANSFER_OUT"))
        {
            for (var cash : List.of(false, true))
            {
                Transaction survivor;
                if (cash)
                {
                    var value = new AccountTransaction(AccountTransaction.Type.valueOf(type));
                    value.setCurrencyCode("EUR");
                    account.addTransaction(value);
                    survivor = value;
                }
                else
                {
                    var value = new PortfolioTransaction(PortfolioTransaction.Type.valueOf(type));
                    portfolio.addTransaction(value);
                    survivor = value;
                }
                survivor.setMonetaryAmount(Money.of("EUR", 1234));
                var before = TransactionsHandler.get(client, survivor.getUUID());
                int changes = dirty.get();
                var failure = assertThrows(ApiException.class,
                                () -> TransactionsHandler.patch(client, survivor.getUUID(), json("{\"note\":\"edit\"}")));
                assertEquals(409, failure.getStatus());
                assertEquals("incomplete-event", failure.getType());
                assertEquals(before, TransactionsHandler.get(client, survivor.getUUID()));
                assertEquals(changes, dirty.get());
            }
        }
    }

    @Test
    public void missingDatesCanBeRepairedButCannotBeLeftInvalid()
    {
        for (var type : TYPES)
        {
            var transaction = create(type);
            transaction.setDateTime(null);
            var cross = transaction.getCrossEntry();
            if (cross != null)
                cross.getCrossTransaction(transaction).setDateTime(null);
            int changes = dirty.get();
            var before = TransactionsHandler.get(client, transaction.getUUID());
            var error = assertThrows(ApiException.class,
                            () -> TransactionsHandler.patch(client, transaction.getUUID(), json("{\"note\":\"edit\"}")));
            assertEquals(422, error.getStatus());
            error(error, "dateTime", "required");
            assertEquals(before, TransactionsHandler.get(client, transaction.getUUID()));
            assertEquals(changes, dirty.get());

            var address = cross == null ? transaction : cross.getCrossTransaction(transaction);
            var result = TransactionsHandler.patch(client, address.getUUID(),
                            json("{\"dateTime\":\"2026-01-02T12:30:00\"}"));
            assertTrue(result.changed());
            assertEquals("2026-01-02T12:30:00", result.entity().get("dateTime").getAsString());
            if (cross != null)
                assertEquals(transaction.getDateTime(), address.getDateTime());
        }
    }

    @Test
    public void emptyAndEquivalentPatchesKeepTimestampsAndDirtyState()
    {
        for (var type : TYPES)
        {
            var transaction = create(type);
            var before = TransactionsHandler.get(client, transaction.getUUID());
            var other = transaction.getCrossEntry() == null ? transaction : transaction.getCrossEntry().getCrossTransaction(transaction);
            var updated = other.getUpdatedAt();
            int changes = dirty.get();
            for (var patch : List.of(json("{}"), json("{\"type\":\"" + type + "\",\"amount\":{\"value\":100.000}}")))
            {
                var result = TransactionsHandler.patch(client, other.getUUID(), patch);
                assertFalse(result.changed());
                assertEquals(before, result.entity());
                assertEquals(updated, other.getUpdatedAt());
                assertEquals(changes, dirty.get());
            }
        }
    }

    @Test
    public void mergesNestedReferencesClearsOptionalValuesAndReplacesUnits()
    {
        var transaction = create("fee");
        var patch = json("{\"note\":\"memo\",\"exDate\":\"2026-01-01T00:00:00\"}");
        reference(patch, "instrument", security.getUUID());
        TransactionsHandler.patch(client, transaction.getUUID(), patch);
        TransactionsHandler.patch(client, transaction.getUUID(), json("{\"instrument\":{},\"amount\":{\"value\":12.34}}"));
        assertSame(security, transaction.getSecurity());
        assertEquals("EUR", transaction.getCurrencyCode());
        var result = TransactionsHandler.patch(client, transaction.getUUID(),
                        json("{\"instrument\":null,\"note\":null,\"exDate\":null,\"source\":null}"));
        for (var field : List.of("instrument", "note", "exDate", "source"))
            assertFalse(result.entity().has(field));
        assertFalse(TransactionsHandler.patch(client, transaction.getUUID(), json("{}")).changed());

        var dividend = create("dividend");
        TransactionsHandler.patch(client, dividend.getUUID(), json("""
                        {"units":[{"type":"tax","amount":{"value":2,"currency":"EUR"}},
                                  {"type":"fee","amount":{"value":1,"currency":"EUR"}}]}
                        """));
        assertEquals(2, dividend.getUnits().count());
        TransactionsHandler.patch(client, dividend.getUUID(), json("""
                        {"units":[{"type":"tax","amount":{"value":4,"currency":"EUR"}}]}
                        """));
        assertEquals(1, dividend.getUnits().count());
        assertEquals(400, dividend.getUnits().findFirst().orElseThrow().getAmount().getAmount());
        TransactionsHandler.patch(client, dividend.getUUID(), json("{\"units\":null}"));
        assertEquals(0, dividend.getUnits().count());
    }

    @Test
    public void rejectsCurrencyChangesWithRetainedChargesWithoutPartialWrites()
    {
        for (var type : List.of("delivery-inbound", "delivery-outbound", "buy", "sell", "dividend"))
        {
            var transaction = create(type);
            TransactionsHandler.patch(client, transaction.getUUID(), json("""
                            {"units":[{"type":"fee","amount":{"value":1,"currency":"EUR"}},
                                      {"type":"tax","amount":{"value":2,"currency":"EUR"}}]}
                            """));
            var failure = rejected(transaction, json("{\"amount\":{\"currency\":\"USD\"}}"));
            error(failure, "units[0].amount.currency", "currency-mismatch");
            error(failure, "units[1].amount.currency", "currency-mismatch");
            error(failure, "units", "gross-value-required");
            if (!type.startsWith("delivery"))
                error(failure, "amount.currency", "currency-mismatch");
        }

        var delivery = create("delivery-inbound");
        delivery.addUnit(new Transaction.Unit(Transaction.Unit.Type.FEE, Money.of("EUR", 100)));
        var result = TransactionsHandler.patch(client, delivery.getUUID(), json("""
                        {"amount":{"currency":"USD"},
                         "units":[{"type":"fee","amount":{"value":1,"currency":"USD"}},
                                   {"type":"gross-value","amount":{"value":99,"currency":"USD"},
                                    "forex":{"value":99,"currency":"EUR"},"exchangeRate":1}]}
                        """));
        assertTrue(result.changed());
        assertEquals("USD", delivery.getCurrencyCode());
        assertEquals(Money.of("USD", 100), delivery.getUnit(Transaction.Unit.Type.FEE).orElseThrow().getAmount());
    }

    @Test
    public void rejectsCurrencyChangesWithRetainedForexChargesWithoutPartialWrites()
    {
        for (var type : List.of("delivery-inbound", "dividend"))
        {
            security.setCurrencyCode("EUR");
            var transaction = create(type);
            security.setCurrencyCode("USD");
            var gross = type.equals("dividend") ? 103 : 97;
            var patch = json("""
                            {"units":[{"type":"fee","amount":{"value":1,"currency":"EUR"}},
                                      {"type":"tax","amount":{"value":2,"currency":"EUR"}},
                                      {"type":"gross-value","amount":{"currency":"EUR"},
                                       "forex":{"currency":"USD"},"exchangeRate":1}]}
                            """);
            var unit = patch.getAsJsonArray("units").get(2).getAsJsonObject();
            unit.getAsJsonObject("amount").addProperty("value", gross);
            unit.getAsJsonObject("forex").addProperty("value", gross);
            TransactionsHandler.patch(client, transaction.getUUID(), patch);
            var failure = rejected(transaction, json("{\"amount\":{\"currency\":\"USD\"}}"));
            error(failure, "units[0].amount.currency", "currency-mismatch");
            error(failure, "units[1].amount.currency", "currency-mismatch");
            error(failure, "units[2].amount.currency", "currency-mismatch");
            error(failure, "units", "gross-value-not-allowed");
        }
    }

    private ApiException rejected(Transaction transaction, JsonObject patch)
    {
        var snapshot = TransactionsHandler.list(client, null, null, null, null, null, null);
        var other = transaction.getCrossEntry() == null ? transaction : transaction.getCrossEntry().getCrossTransaction(transaction);
        var timestamp = other.getUpdatedAt();
        int changes = dirty.get();
        var failure = assertThrows(ApiException.class, () -> TransactionsHandler.patch(client, other.getUUID(), patch));
        assertEquals(422, failure.getStatus());
        assertEquals(snapshot, TransactionsHandler.list(client, null, null, null, null, null, null));
        assertEquals(timestamp, other.getUpdatedAt());
        assertEquals(changes, dirty.get());
        return failure;
    }

    private static void error(ApiException failure, String field, String code)
    {
        assertTrue(failure.getErrors().toString(), failure.getErrors().stream()
                        .anyMatch(error -> error.field().equals(field) && error.code().equals(code)));
    }

    @Test
    public void rejectsReadOnlyUnknownAndImmutableFieldsIncludingNullWithoutPartialWrites()
    {
        for (var type : TYPES)
        {
            var transaction = create(type);
            var current = TransactionsHandler.get(client, transaction.getUUID()).getAsJsonObject();
            for (var field : List.of("uuid", "updatedAt", "grossValue", "fees", "taxes", "typo"))
            {
                var patch = json("{\"note\":\"must not persist\"}");
                patch.add(field, current.has(field) ? current.get(field) : com.google.gson.JsonNull.INSTANCE);
                error(rejected(transaction, patch), field, "unknown-field");
                patch.add(field, com.google.gson.JsonNull.INSTANCE);
                error(rejected(transaction, patch), field, "unknown-field");
            }
            error(rejected(transaction, json("{\"type\":null}")), "type", "immutable-field");
            for (var field : List.of("cashAccount", "investmentAccount", "fromCashAccount", "toCashAccount",
                            "fromInvestmentAccount", "toInvestmentAccount"))
            {
                if (!current.has(field))
                    continue;
                var patch = new JsonObject();
                reference(patch, field, UUID.randomUUID().toString());
                error(rejected(transaction, patch), field, "immutable-field");
                patch.add(field, com.google.gson.JsonNull.INSTANCE);
                error(rejected(transaction, patch), field, "immutable-field");
                patch.add(field, json("{\"name\":null}"));
                error(rejected(transaction, patch), field + ".name", "unknown-field");
            }
        }
        var transaction = create("buy");
        var failure = rejected(transaction, json("""
                        {"amount":{"value":-10,"currency":"XYZ","typo":null},"shares":-2,
                         "grossValue":null,"dateTime":"wrong","instrument":{"name":null}}
                        """));
        error(failure, "amount.typo", "unknown-field");
        error(failure, "grossValue", "unknown-field");
        error(failure, "instrument.name", "unknown-field");
        error(failure, "dateTime", "invalid-date");
        assertTrue(failure.getErrors().size() >= 6);
    }

    @Test
    public void validatesFullTargetForexAndDoesNotAdjustClientNumbers()
    {
        var transaction = create("buy");
        security.setCurrencyCode("USD");
        error(rejected(transaction, json("{\"note\":\"still invalid\"}")), "units", "gross-value-required");
        var patch = json("""
                        {"units":[{"type":"gross-value","amount":{"value":100,"currency":"EUR"},
                                   "forex":{"value":125,"currency":"USD"},"exchangeRate":0.8}]}
                        """);
        TransactionsHandler.patch(client, transaction.getUUID(), patch);
        assertFalse(TransactionsHandler.patch(client, transaction.getUUID(), patch).changed());
        rejected(transaction, json("{\"amount\":{\"value\":101}}"));
        rejected(transaction, json("{\"units\":[]}"));
        assertEquals(10000, transaction.getAmount());
        security.setCurrencyCode("EUR");
        TransactionsHandler.patch(client, transaction.getUUID(), json("{\"units\":[]}"));
    }

    @Test
    public void rejectsHiddenInvalidCounterpartDataInsteadOfDiscardingIt()
    {
        var transaction = create("buy");
        var cash = (AccountTransaction) transaction.getCrossEntry().getCrossTransaction(transaction);
        cash.setExDate(transaction.getDateTime());
        error(rejected(transaction, json("{\"note\":\"edit\"}")), "exDate", "ex-date-not-allowed");
        assertEquals(transaction.getDateTime(), cash.getExDate());
    }

    @Test
    public void omissionPreservesEachLinkedRecordsMetadataAndEmptyNestedPatchesChangeNothing()
    {
        for (var type : List.of("buy", "cash-transfer", "security-transfer"))
        {
            var transaction = create(type);
            var other = transaction.getCrossEntry().getCrossTransaction(transaction);
            other.setNote("Other note");
            other.setSource("Other source");
            transaction.setDateTime(transaction.getDateTime().withNano(123456789));
            other.setDateTime(transaction.getDateTime());
            var timestamp = other.getUpdatedAt();
            var patch = type.equals("cash-transfer") ? json("{\"amount\":{},\"targetAmount\":{}}")
                            : json("{\"amount\":{},\"instrument\":{}}");
            assertFalse(TransactionsHandler.patch(client, other.getUUID(), json("{}")).changed());
            assertFalse(TransactionsHandler.patch(client, other.getUUID(), patch).changed());
            assertEquals(timestamp, other.getUpdatedAt());
            assertEquals("Other note", other.getNote());
            assertEquals("Other source", other.getSource());
            TransactionsHandler.patch(client, other.getUUID(), json("{\"note\":\"Updated note\"}"));
            assertEquals("Updated note", transaction.getNote());
            assertEquals("Updated note", other.getNote());
            assertEquals("Original client", transaction.getSource());
            assertEquals("Other source", other.getSource());
            TransactionsHandler.patch(client, other.getUUID(), json("{\"source\":null}"));
            assertEquals(null, transaction.getSource());
            assertEquals(null, other.getSource());
        }
    }

    @Test
    public void validatesOmittedCounterpartBusinessValuesInsteadOfNormalizingThem()
    {
        var replacement = new Security();
        replacement.setCurrencyCode("EUR");
        replacement.setName("Other instrument");
        client.addSecurity(replacement);
        for (var field : List.of("amount", "currency", "instrument", "dateTime", "shares"))
        {
            var transaction = create("buy");
            var other = transaction.getCrossEntry().getCrossTransaction(transaction);
            switch (field)
            {
                case "amount" -> other.setAmount(20000);
                case "currency" -> other.setCurrencyCode("USD");
                case "instrument" -> other.setSecurity(replacement);
                case "dateTime" -> other.setDateTime(other.getDateTime().withNano(1));
                case "shares" -> other.setShares(1);
                default -> throw new IllegalArgumentException(field);
            }
            rejected(transaction, json("{}"));
            rejected(transaction, json("{\"amount\":{},\"instrument\":{}}"));
            rejected(transaction, json("{\"note\":\"must not hide inconsistency\"}"));
        }
    }

    @Test
    public void updatesForexTransferAndInstrumentAndPreservesOmittedDatePrecision()
    {
        var transfer = create("cash-transfer");
        secondAccount.setCurrencyCode("USD");
        var patch = json("""
                        {"targetAmount":{"value":125,"currency":"USD"},
                         "units":[{"type":"gross-value","amount":{"value":100,"currency":"EUR"},
                                   "forex":{"value":125,"currency":"USD"},"exchangeRate":0.8}]}
                        """);
        var other = transfer.getCrossEntry().getCrossTransaction(transfer);
        assertTrue(TransactionsHandler.patch(client, other.getUUID(), patch).changed());
        assertEquals(12500, other.getAmount());
        assertEquals("USD", other.getCurrencyCode());
        assertFalse(TransactionsHandler.patch(client, other.getUUID(), patch).changed());
        rejected(transfer, json("{\"targetAmount\":{\"value\":126}}"));

        var dividend = (AccountTransaction) create("dividend");
        dividend.setDateTime(dividend.getDateTime().withNano(123456789));
        dividend.setExDate(dividend.getDateTime().minusDays(1));
        var date = dividend.getDateTime();
        var exDate = dividend.getExDate();
        assertFalse(TransactionsHandler.patch(client, dividend.getUUID(), json("{}")).changed());
        var replacement = new Security();
        replacement.setCurrencyCode("EUR");
        replacement.setName("Replacement");
        client.addSecurity(replacement);
        var instrument = new JsonObject();
        reference(instrument, "instrument", replacement.getUUID());
        TransactionsHandler.patch(client, dividend.getUUID(), instrument);
        assertSame(replacement, dividend.getSecurity());
        assertEquals(date, dividend.getDateTime());
        assertEquals(exDate, dividend.getExDate());
    }

    @Test
    public void routeGuardsWritesAndLogsOnlyRealChangesUsingCanonicalUuid() throws Exception
    {
        var node = InstanceScope.INSTANCE.getNode("rest-test-" + UUID.randomUUID());
        var captured = new ArrayList<IStatus>();
        ILogListener listener = (status, plugin) -> captured.add(status);
        var log = Platform.getLog(FrameworkUtil.getBundle(PortfolioLog.class));
        log.addLogListener(listener);
        try
        {
            var path = "/tmp/transaction-patch.portfolio";
            var registry = new FileAccessRegistry(node);
            registry.setEnabled(path, true);
            registry.setAlias(path, "sample");
            var host = new FakeHost(List.of(new FakeHost.FakeOpenFile(path, "Patch test", client)));
            var router = ApiRoutes.create(registry, host, null);
            var transaction = create("buy");
            var other = transaction.getCrossEntry().getCrossTransaction(transaction);
            var uri = "/v1/files/sample/transactions/" + other.getUUID();
            var route = router.match("PATCH", uri);
            var request = new Request("PATCH", uri, route.pathParams(), "{\"note\":\"edit\"}".getBytes(StandardCharsets.UTF_8));
            int changes = dirty.get();
            int logs = captured.size();
            host.setUserEditing(true);
            assertEquals(423, assertThrows(ApiException.class, () -> route.handler().handle(request)).getStatus());
            host.setUserEditing(false);
            registry.setEnabled(path, false);
            assertEquals(404, assertThrows(ApiException.class, () -> route.handler().handle(request)).getStatus());
            registry.setEnabled(path, true);
            var invalid = new Request("PATCH", uri, route.pathParams(), "{\"grossValue\":null}".getBytes(StandardCharsets.UTF_8));
            assertEquals(422, assertThrows(ApiException.class, () -> route.handler().handle(invalid)).getStatus());
            assertEquals(changes, dirty.get());
            assertEquals(logs, captured.size());
            assertEquals(200, route.handler().handle(request).status());
            assertEquals(changes + 1, dirty.get());
            assertEquals(logs + 1, captured.size());
            assertTrue(captured.getLast().getMessage().contains(transaction.getUUID()));
            assertTrue(captured.getLast().getMessage().contains("Patch test"));
            assertEquals(200, route.handler().handle(request).status());
            assertEquals(changes + 1, dirty.get());
            assertEquals(logs + 1, captured.size());
            assertFalse(host.hasAccessedOutsideUIThread());
            assertEquals(404, assertThrows(ApiException.class,
                            () -> TransactionsHandler.patch(client, UUID.randomUUID().toString(), json("{}"))).getStatus());
        }
        finally
        {
            log.removeLogListener(listener);
            node.removeNode();
        }
    }
}
