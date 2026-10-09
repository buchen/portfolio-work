package name.abuchen.portfolio.rest.internal;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import java.math.BigDecimal;
import java.time.LocalDateTime;

import org.junit.Test;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import name.abuchen.portfolio.model.Account;
import name.abuchen.portfolio.model.AccountTransaction;
import name.abuchen.portfolio.model.AccountTransferEntry;
import name.abuchen.portfolio.model.BuySellEntry;
import name.abuchen.portfolio.model.Client;
import name.abuchen.portfolio.model.CrossEntry;
import name.abuchen.portfolio.model.Portfolio;
import name.abuchen.portfolio.model.PortfolioTransaction;
import name.abuchen.portfolio.model.PortfolioTransferEntry;
import name.abuchen.portfolio.model.Security;
import name.abuchen.portfolio.model.Transaction;
import name.abuchen.portfolio.money.Money;

@SuppressWarnings("nls")
public class TransactionsTest
{
    private final Client client = new Client();
    private final Account cash = account("EUR");
    private final Account target = account("USD");
    private final Portfolio portfolio = portfolio();
    private final Portfolio other = portfolio();
    private final Security security = security();
    private final LocalDateTime date = LocalDateTime.of(2026, 1, 2, 12, 30);

    private Account account(String currency)
    {
        var account = new Account();
        account.setName(currency);
        account.setCurrencyCode(currency);
        client.addAccount(account);
        return account;
    }

    private Portfolio portfolio()
    {
        var value = new Portfolio();
        value.setName("Depot");
        client.addPortfolio(value);
        return value;
    }

    private Security security()
    {
        var value = new Security();
        value.setName("Security");
        value.setCurrencyCode("USD");
        client.addSecurity(value);
        return value;
    }

    private JsonArray list(String type, String from, String to, String instrument, String account, String investment)
    {
        return TransactionsHandler.list(client, type, from, to, instrument, account, investment).getAsJsonObject()
                        .getAsJsonArray("items");
    }

    private JsonArray list()
    {
        return list(null, null, null, null, null, null);
    }

    private JsonObject get(Transaction transaction)
    {
        return TransactionsHandler.get(client, transaction.getUUID()).getAsJsonObject();
    }

    private BuySellEntry buy()
    {
        var entry = new BuySellEntry(portfolio, cash);
        entry.setType(PortfolioTransaction.Type.BUY);
        entry.setDate(date);
        entry.setSecurity(security);
        entry.setShares(123456789);
        entry.setCurrencyCode("EUR");
        entry.setAmount(10100);
        entry.getPortfolioTransaction().addUnit(new Transaction.Unit(Transaction.Unit.Type.FEE, Money.of("EUR", 100)));
        entry.getPortfolioTransaction().addUnit(new Transaction.Unit(Transaction.Unit.Type.GROSS_VALUE,
                        Money.of("EUR", 10000), Money.of("USD", 12500), new BigDecimal("0.8")));
        entry.insert();
        return entry;
    }

    @Test
    public void singleRecordLookupStopsAtTheFirstMatchingEvent()
    {
        var entry = buy();
        portfolio.addTransaction(new PortfolioTransaction()
        {
            @Override
            public CrossEntry getCrossEntry()
            {
                throw new AssertionError("Lookup must not visit records after the matching event");
            }
        });

        var json = get(entry.getPortfolioTransaction());
        assertEquals(entry.getPortfolioTransaction().getUUID(), json.get("uuid").getAsString());
        assertEquals(json, get(entry.getAccountTransaction()));
    }

    @Test
    public void foldsBuyAndPreservesForexUnits()
    {
        var entry = buy();
        assertEquals(1, list().size());
        var json = get(entry.getPortfolioTransaction());
        assertEquals(json, get(entry.getAccountTransaction()));
        assertEquals(entry.getPortfolioTransaction().getUUID(), json.get("uuid").getAsString());
        assertEquals("2026-01-02T12:30:00", json.get("dateTime").getAsString());
        assertEquals(new BigDecimal("1.23456789"), json.get("shares").getAsBigDecimal());
        assertEquals(100, json.getAsJsonObject("grossValue").get("value").getAsInt());
        assertEquals(1, json.getAsJsonObject("fees").get("value").getAsInt());
        var forex = json.getAsJsonArray("units").get(1).getAsJsonObject();
        assertEquals(new BigDecimal("0.8"), forex.get("exchangeRate").getAsBigDecimal());
        assertEquals(125, forex.getAsJsonObject("forex").get("value").getAsInt());
    }

    @Test
    public void foldsTransfersAndFiltersEitherEndpoint()
    {
        var transfer = new AccountTransferEntry(cash, target);
        transfer.setDate(date);
        transfer.getSourceTransaction().setMonetaryAmount(Money.of("EUR", 10000));
        transfer.getTargetTransaction().setMonetaryAmount(Money.of("USD", 12500));
        transfer.getSourceTransaction().addUnit(new Transaction.Unit(Transaction.Unit.Type.GROSS_VALUE,
                        Money.of("EUR", 10000), Money.of("USD", 12500), new BigDecimal("0.8")));
        transfer.insert();
        var move = new PortfolioTransferEntry(portfolio, other);
        move.setSecurity(security);
        move.setDate(date);
        move.setShares(100000000);
        move.setCurrencyCode("USD");
        move.setAmount(12500);
        move.insert();
        assertEquals(2, list().size());
        assertEquals(get(transfer.getSourceTransaction()), get(transfer.getTargetTransaction()));
        assertEquals(get(move.getSourceTransaction()), get(move.getTargetTransaction()));
        assertEquals(125, get(transfer.getSourceTransaction()).getAsJsonObject("targetAmount").get("value").getAsInt());
        for (var account : new Account[] { cash, target })
            assertEquals(1, list(null, null, null, null, account.getUUID(), null).size());
        for (var depot : new Portfolio[] { portfolio, other })
            assertEquals(1, list(null, null, null, null, null, depot.getUUID()).size());
    }

    @Test
    public void filtersCombineAndDatesIncludeWholeDay()
    {
        buy();
        assertEquals(1, list("buy,sell", "2026-01-02", "2026-01-02", security.getUUID(), cash.getUUID(), portfolio.getUUID()).size());
        assertEquals(0, list("sell", null, null, null, null, null).size());
        assertEquals(0, list(null, "2026-01-03", null, null, null, null).size());
        assertEquals(0, list(null, null, "2026-01-01", null, null, null).size());
        assertEquals(0, list(null, null, null, null, target.getUUID(), null).size());
        assertEquals(0, list(null, null, null, null, cash.getUUID(), other.getUUID()).size());
    }

    @Test
    public void rejectsInvalidFiltersTogether()
    {
        var error = assertThrows(ApiException.class,
                        () -> list("BUY,", "bad-date", "2026-02-30", "missing", "missing", "missing"));
        assertEquals(400, error.getStatus());
        assertEquals(7, error.getErrors().size());
        assertThrows(ApiException.class, () -> list(null, "2026-02-01", "2026-01-01", null, null, null));
        assertThrows(ApiException.class, () -> list("buy,", null, null, null, null, null));
    }

    @Test
    public void ordersByDateThenUuidAndReadsSingleRecords()
    {
        var first = buy();
        var delivery = new PortfolioTransaction();
        delivery.setType(PortfolioTransaction.Type.DELIVERY_INBOUND);
        delivery.setDateTime(date);
        delivery.setCurrencyCode("USD");
        delivery.setSecurity(security);
        portfolio.addTransaction(delivery);
        var dividend = new AccountTransaction();
        dividend.setType(AccountTransaction.Type.DIVIDENDS);
        dividend.setDateTime(date.minusDays(1));
        dividend.setExDate(date.minusDays(2));
        dividend.setCurrencyCode("EUR");
        dividend.setSecurity(security);
        dividend.setAmount(100);
        cash.addTransaction(dividend);
        var items = list();
        assertEquals("dividend", items.get(0).getAsJsonObject().get("type").getAsString());
        var uuids = java.util.stream.Stream.of(first.getPortfolioTransaction().getUUID(), delivery.getUUID()).sorted().toList();
        assertEquals(uuids.get(0), items.get(1).getAsJsonObject().get("uuid").getAsString());
        assertEquals(uuids.get(1), items.get(2).getAsJsonObject().get("uuid").getAsString());
        assertEquals("2025-12-31T12:30:00", get(dividend).get("exDate").getAsString());
        assertFalse(get(delivery).has("cashAccount"));
    }

    @Test
    public void cashTypesAndSecurityDisposalsKeepGrossDirection()
    {
        var cashTypes = new AccountTransaction.Type[] { AccountTransaction.Type.DEPOSIT,
                        AccountTransaction.Type.REMOVAL, AccountTransaction.Type.INTEREST,
                        AccountTransaction.Type.INTEREST_CHARGE, AccountTransaction.Type.FEES,
                        AccountTransaction.Type.FEES_REFUND, AccountTransaction.Type.TAXES,
                        AccountTransaction.Type.TAX_REFUND };
        var names = new String[] { "deposit", "removal", "interest", "interest-charge", "fee", "fee-refund", "tax", "tax-refund" };
        for (int i = 0; i < cashTypes.length; i++)
        {
            var transaction = new AccountTransaction();
            transaction.setType(cashTypes[i]);
            transaction.setDateTime(date);
            transaction.setMonetaryAmount(Money.of("EUR", 10000));
            cash.addTransaction(transaction);
            var json = get(transaction);
            assertEquals(names[i], json.get("type").getAsString());
            assertEquals(100, json.getAsJsonObject("grossValue").get("value").getAsInt());
        }
        var sale = buy();
        sale.setType(PortfolioTransaction.Type.SELL);
        var json = get(sale.getAccountTransaction());
        assertEquals("sell", json.get("type").getAsString());
        assertEquals(102, json.getAsJsonObject("grossValue").get("value").getAsInt());
        var delivery = new PortfolioTransaction();
        delivery.setType(PortfolioTransaction.Type.DELIVERY_OUTBOUND);
        delivery.setDateTime(date);
        delivery.setSecurity(security);
        delivery.setMonetaryAmount(Money.of("USD", 10000));
        delivery.addUnit(new Transaction.Unit(Transaction.Unit.Type.TAX, Money.of("USD", 100)));
        portfolio.addTransaction(delivery);
        json = get(delivery);
        assertEquals("delivery-outbound", json.get("type").getAsString());
        assertEquals(101, json.getAsJsonObject("grossValue").get("value").getAsInt());
    }

    @Test
    public void unlinkedRecordsRemainReadableAndFilterable()
    {
        buy();
        var types = new String[] { "buy", "sell", "cash-transfer", "cash-transfer",
                        "buy", "sell", "security-transfer", "security-transfer" };
        var fields = new String[] { "cashAccount", "cashAccount", "toCashAccount", "fromCashAccount",
                        "investmentAccount", "investmentAccount", "toInvestmentAccount", "fromInvestmentAccount" };
        for (int i = 0; i < types.length; i++)
        {
            Transaction transaction;
            var modelType = new String[] { "BUY", "SELL", "TRANSFER_IN", "TRANSFER_OUT" }[i % 4];
            if (i < 4)
            {
                var value = new AccountTransaction();
                value.setType(AccountTransaction.Type.valueOf(modelType));
                value.setCurrencyCode("EUR");
                cash.addTransaction(value);
                transaction = value;
            }
            else
            {
                var value = new PortfolioTransaction();
                value.setType(PortfolioTransaction.Type.valueOf(modelType));
                portfolio.addTransaction(value);
                transaction = value;
            }
            transaction.setDateTime(date);
            transaction.setSecurity(security);
            transaction.setShares(100000000);
            transaction.setMonetaryAmount(Money.of("EUR", 1234));
            var json = get(transaction);
            assertEquals(types[i], json.get("type").getAsString());
            assertEquals("missing-counterpart", json.get("integrity").getAsString());
            assertEquals(transaction.getUUID(), json.get("uuid").getAsString());
            assertEquals(i < 4 ? cash.getUUID() : portfolio.getUUID(),
                            json.getAsJsonObject(fields[i]).get("uuid").getAsString());
            assertEquals(new BigDecimal("12.34"), json.getAsJsonObject("amount").get("value").getAsBigDecimal());
            for (var field : java.util.Set.of("cashAccount", "investmentAccount", "fromCashAccount", "toCashAccount",
                            "fromInvestmentAccount", "toInvestmentAccount", "targetAmount"))
                assertEquals(field, field.equals(fields[i]), json.has(field));
            assertTrue(list(types[i], "2026-01-02", "2026-01-02", security.getUUID(),
                            i < 4 ? cash.getUUID() : null, i < 4 ? null : portfolio.getUUID()).contains(json));
        }
        assertEquals(9, list().size());
    }

    @Test
    public void missingDatesSortLastAndDoNotMatchDateFilters()
    {
        var dated = buy();
        var undated = buy();
        undated.setDate(null);
        var cashOnly = new AccountTransaction();
        cashOnly.setType(AccountTransaction.Type.DEPOSIT);
        cashOnly.setMonetaryAmount(Money.of("EUR", 100));
        cash.addTransaction(cashOnly);

        var items = list();
        assertEquals(3, items.size());
        assertEquals(dated.getPortfolioTransaction().getUUID(), items.get(0).getAsJsonObject().get("uuid").getAsString());
        var uuids = java.util.stream.Stream.of(undated.getPortfolioTransaction().getUUID(), cashOnly.getUUID()).sorted().toList();
        for (int i = 0; i < uuids.size(); i++)
        {
            assertEquals(uuids.get(i), items.get(i + 1).getAsJsonObject().get("uuid").getAsString());
            assertTrue(items.get(i + 1).getAsJsonObject().get("dateTime").isJsonNull());
        }
        assertTrue(get(cashOnly).get("dateTime").isJsonNull());
        assertTrue(get(undated.getAccountTransaction()).get("dateTime").isJsonNull());
        assertEquals(1, list(null, "2026-01-02", null, null, null, null).size());
        assertEquals(1, list(null, null, "2026-01-02", null, null, null).size());
        assertEquals(1, list(null, "2026-01-02", "2026-01-02", null, null, null).size());
    }

    @Test
    public void emptyFileAndMissingEvent()
    {
        assertEquals(0, list().size());
        assertEquals(404, assertThrows(ApiException.class, () -> TransactionsHandler.get(client, "missing")).getStatus());
    }
}
