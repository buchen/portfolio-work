package name.abuchen.portfolio.rest.internal;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertSame;

import org.junit.Test;

import name.abuchen.portfolio.model.AccountTransaction;
import name.abuchen.portfolio.model.PortfolioTransaction;
import name.abuchen.portfolio.model.Transaction.Unit;

public class TransactionTypeTest
{
    @Test
    public void everyStoredTypeHasAParseableWireName()
    {
        for (var type : AccountTransaction.Type.values())
        {
            var transaction = new AccountTransaction();
            transaction.setType(type);
            var eventType = TransactionType.of(transaction);
            assertSame(eventType, TransactionType.fromWire(eventType.wireName));
            assertEquals(type == AccountTransaction.Type.TRANSFER_IN ? AccountTransaction.Type.TRANSFER_OUT : type,
                            eventType.accountType);
        }
        for (var type : PortfolioTransaction.Type.values())
        {
            var transaction = new PortfolioTransaction();
            transaction.setType(type);
            var eventType = TransactionType.of(transaction);
            assertSame(eventType, TransactionType.fromWire(eventType.wireName));
            assertEquals(type == PortfolioTransaction.Type.TRANSFER_IN ? PortfolioTransaction.Type.TRANSFER_OUT : type,
                            eventType.portfolioType);
        }
    }

    @Test
    public void everyStoredUnitTypeHasAParseableWireName()
    {
        for (var type : Unit.Type.values())
        {
            var name = TransactionUnitType.toWire(type);
            assertNotNull(name);
            assertSame(type, TransactionUnitType.fromWire(name));
        }
    }
}
