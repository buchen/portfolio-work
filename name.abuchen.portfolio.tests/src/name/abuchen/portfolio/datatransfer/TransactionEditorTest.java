package name.abuchen.portfolio.datatransfer;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import java.time.LocalDateTime;
import java.util.List;

import org.junit.Test;

import name.abuchen.portfolio.model.Account;
import name.abuchen.portfolio.model.AccountTransaction;
import name.abuchen.portfolio.model.BuySellEntry;
import name.abuchen.portfolio.model.InvestmentPlan;
import name.abuchen.portfolio.model.Portfolio;
import name.abuchen.portfolio.model.PortfolioTransaction;
import name.abuchen.portfolio.model.Security;
import name.abuchen.portfolio.model.Transaction;
import name.abuchen.portfolio.money.Money;

@SuppressWarnings("nls")
public class TransactionEditorTest
{
    private final Account account = new Account();
    private final Portfolio portfolio = new Portfolio();
    private final Security security = new Security();

    public TransactionEditorTest()
    {
        account.setCurrencyCode("EUR");
        portfolio.setReferenceAccount(account);
        security.setCurrencyCode("EUR");
    }

    private BuySellEntry entry()
    {
        var entry = new BuySellEntry(PortfolioTransaction.Type.BUY);
        entry.setAccount(account);
        entry.setPortfolio(portfolio);
        entry.setSecurity(security);
        entry.setDate(LocalDateTime.of(2026, 1, 2, 12, 0));
        entry.setMonetaryAmount(Money.of("EUR", 10000));
        entry.setShares(100000000);
        return entry;
    }

    @Test
    public void copiesEitherSideAndPreservesInvalidDataForValidation()
    {
        var entry = entry();
        var cash = entry.getAccountTransaction();
        cash.setExDate(cash.getDateTime().withNano(123));
        cash.setShares(42);
        cash.setSource("Imported source");
        cash.addUnit(new Transaction.Unit(Transaction.Unit.Type.FEE, Money.of("EUR", 100)));
        for (var original : List.of(cash, entry.getPortfolioTransaction()))
        {
            var copy = TransactionEditor.copyOf(original);
            var copiedEntry = (BuySellEntry) copy.getCrossEntry();
            assertNotSame(original, copy);
            assertNotSame(entry, copiedEntry);
            assertSame(entry.getOwner(original), copiedEntry.getOwner(copy));
            var copiedCash = copiedEntry.getAccountTransaction();
            assertEquals(AccountTransaction.Type.BUY, copiedCash.getType());
            assertEquals(cash.getExDate(), copiedCash.getExDate());
            assertEquals(42, copiedCash.getShares());
            assertEquals("Imported source", copiedCash.getSource());
            assertEquals(cash.getUpdatedAt(), copiedCash.getUpdatedAt());
            assertEquals(cash.getUnits().toList(), copiedCash.getUnits().toList());
            assertFalse(TransactionEditor.validate(copiedEntry.getOwner(copy), copy).isEmpty());
            copiedCash.clearUnits();
            copiedCash.setAmount(20000);
            assertEquals(1, cash.getUnits().count());
            assertEquals(10000, cash.getAmount());
            assertTrue(account.getTransactions().isEmpty());
            assertTrue(portfolio.getTransactions().isEmpty());
        }
    }

    @Test
    public void appliesValidatedChangesInPlaceAndDoesNotTouchEquivalentState()
    {
        var existing = entry();
        existing.insert();
        var original = existing.getPortfolioTransaction();
        var cash = existing.getAccountTransaction();
        var plan = new InvestmentPlan();
        plan.getTransactions().add(original);
        plan.getTransactions().add(cash);
        var timestamp = original.getUpdatedAt();
        var cashTimestamp = cash.getUpdatedAt();
        var target = entry();
        var unchanged = TransactionEditor.apply(portfolio, original, portfolio, target.getPortfolioTransaction());
        assertTrue(unchanged.errors().isEmpty());
        assertFalse(unchanged.changed());
        assertEquals(timestamp, original.getUpdatedAt());
        assertEquals(cashTimestamp, cash.getUpdatedAt());

        target.setMonetaryAmount(Money.of("EUR", 20000));
        target.setNote("edited");
        var changed = TransactionEditor.apply(portfolio, original, portfolio, target.getPortfolioTransaction());
        assertTrue(changed.errors().isEmpty());
        assertTrue(changed.changed());
        assertSame(original, portfolio.getTransactions().getFirst());
        assertSame(cash, account.getTransactions().getFirst());
        assertSame(existing, original.getCrossEntry());
        assertSame(existing, cash.getCrossEntry());
        assertEquals(List.of(original, cash), plan.getTransactions());
        assertEquals(20000, original.getAmount());
        assertEquals(20000, cash.getAmount());
        assertEquals("edited", cash.getNote());
    }

    @Test
    public void refusesOwnerTypeAndInvalidTargetChangesBeforeTouchingEitherRecord()
    {
        var existing = entry();
        existing.insert();
        var original = existing.getPortfolioTransaction();
        var timestamp = original.getUpdatedAt();
        var cashTimestamp = existing.getAccountTransaction().getUpdatedAt();
        for (var scenario : List.of("owner", "type", "invalid", "unowned"))
        {
            var target = entry();
            target.setNote("must not be applied");
            var owner = portfolio;
            if (scenario.equals("owner"))
                target.setAccount(new Account());
            else if (scenario.equals("type"))
                target.setType(PortfolioTransaction.Type.SELL);
            else if (scenario.equals("unowned"))
            {
                owner = new Portfolio();
                target.setPortfolio(owner);
            }
            else
                target.getAccountTransaction().setAmount(20000);
            var result = TransactionEditor.apply(owner, original, owner, target.getPortfolioTransaction());
            assertFalse(result.changed());
            assertFalse(result.errors().isEmpty());
            assertTrue(result.errors().stream().allMatch(status -> status.getMessage() == null
                            && status.getRuleCode() != null && status.getField() != null));
            assertEquals(timestamp, original.getUpdatedAt());
            assertEquals(cashTimestamp, existing.getAccountTransaction().getUpdatedAt());
            assertEquals(10000, original.getAmount());
            assertEquals(10000, existing.getAccountTransaction().getAmount());
            assertSame(existing, original.getCrossEntry());
        }
    }
}
