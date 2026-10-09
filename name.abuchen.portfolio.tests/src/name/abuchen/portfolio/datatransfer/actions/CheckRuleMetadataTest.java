package name.abuchen.portfolio.datatransfer.actions;

import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.CoreMatchers.nullValue;
import static org.hamcrest.MatcherAssert.assertThat;

import java.math.BigDecimal;

import org.junit.Test;

import name.abuchen.portfolio.Messages;
import name.abuchen.portfolio.datatransfer.ImportAction.Status;
import name.abuchen.portfolio.model.Account;
import name.abuchen.portfolio.model.AccountTransaction;
import name.abuchen.portfolio.model.Portfolio;
import name.abuchen.portfolio.model.PortfolioTransaction;
import name.abuchen.portfolio.model.Security;
import name.abuchen.portfolio.model.Transaction.Unit;
import name.abuchen.portfolio.money.Money;

@SuppressWarnings("nls")
public class CheckRuleMetadataTest
{
    @Test
    public void testLegacyStatusesHaveNoRuleMetadata()
    {
        var status = new Status(Status.Code.WARNING, "message");
        assertThat(status.getCode(), is(Status.Code.WARNING));
        assertThat(status.getMessage(), is("message"));
        assertThat(status.getRuleCode(), nullValue());
        assertThat(status.getField(), nullValue());
        assertThat(Status.OK_STATUS.getRuleCode(), nullValue());
        assertThat(Status.OK_STATUS.getField(), nullValue());
    }

    @Test
    public void testDateAndTypeMetadata()
    {
        var transaction = new AccountTransaction();
        var status = new CheckTransactionDateAction().process(transaction, new Account());
        assertMetadata(status, "date-required", "dateTime");
        assertThat(status.getMessage(), is(Messages.IssueTransactionWithoutDate));

        transaction.setType(AccountTransaction.Type.BUY);
        assertMetadata(new CheckValidTypesAction().process(transaction, new Account()), "invalid-type", "type");
        var delivery = delivery();
        delivery.setType(PortfolioTransaction.Type.BUY);
        assertMetadata(new CheckValidTypesAction().process(delivery, new Portfolio()), "invalid-type", "type");
    }

    @Test
    public void testSecurityAndSharesMetadata()
    {
        var action = new CheckSecurityRelatedValuesAction();
        var transaction = new AccountTransaction();
        var account = new Account();
        transaction.setType(AccountTransaction.Type.DEPOSIT);
        transaction.setSecurity(new Security("Security", "EUR"));
        assertMetadata(action.process(transaction, account), "instrument-not-allowed", "instrument");

        transaction.setSecurity(null);
        transaction.setShares(1);
        assertMetadata(action.process(transaction, account), "shares-not-allowed", "shares");

        transaction.setType(AccountTransaction.Type.DIVIDENDS);
        assertMetadata(action.process(transaction, account), "instrument-required", "instrument");
    }

    @Test
    public void testAccountCurrencyAndUnitsMetadata()
    {
        var action = new CheckCurrenciesAction();
        assertMetadata(action.process(new Security("Security", "INVALID")), "unsupported-currency", "currencyCode");

        var account = new Account();
        account.setCurrencyCode("EUR");
        var transaction = new AccountTransaction();
        transaction.setType(AccountTransaction.Type.BUY);
        transaction.setMonetaryAmount(Money.of("USD", 100));
        assertMetadata(action.process(transaction, account), "currency-mismatch", "amount.currency");

        transaction.setMonetaryAmount(Money.of("EUR", 100));
        transaction.setSecurity(new Security("Security", "EUR"));
        transaction.addUnit(new Unit(Unit.Type.FEE, Money.of("EUR", 1)));
        assertMetadata(action.process(transaction, account), "units-not-allowed", "units");
    }

    @Test
    public void testPortfolioSecurityAndFeesMetadata()
    {
        var action = new CheckCurrenciesAction();
        var transaction = delivery();
        var portfolio = new Portfolio();
        transaction.setSecurity(null);
        assertMetadata(action.process(transaction, portfolio), "instrument-required", "instrument");

        transaction.setSecurity(new Security("Security", null));
        assertMetadata(action.process(transaction, portfolio), "instrument-currency-required", "instrument");

        transaction.setSecurity(new Security("Security", "EUR"));
        transaction.addUnit(new Unit(Unit.Type.FEE, Money.of("EUR", 101)));
        assertMetadata(action.process(transaction, portfolio), "fees-and-taxes-exceed-amount", "units");
    }

    @Test
    public void testSameCurrencyForexMetadata()
    {
        var action = new CheckCurrenciesAction();
        var transaction = delivery();
        var portfolio = new Portfolio();
        transaction.addUnit(forexUnit(Unit.Type.GROSS_VALUE, "USD"));
        assertMetadata(action.process(transaction, portfolio), "gross-value-not-allowed", "units");

        transaction.removeUnits(Unit.Type.GROSS_VALUE);
        transaction.addUnit(forexUnit(Unit.Type.FEE, "USD"));
        assertMetadata(action.process(transaction, portfolio), "forex-not-allowed", "units");
    }

    @Test
    public void testForeignCurrencyForexMetadata()
    {
        var action = new CheckCurrenciesAction();
        var transaction = delivery();
        transaction.setSecurity(new Security("Security", "USD"));
        var portfolio = new Portfolio();
        assertMetadata(action.process(transaction, portfolio), "gross-value-required", "units");

        transaction.addUnit(forexUnit(Unit.Type.GROSS_VALUE, "GBP"));
        assertMetadata(action.process(transaction, portfolio), "forex-currency-mismatch", "units");

        transaction.removeUnits(Unit.Type.GROSS_VALUE);
        transaction.addUnit(forexUnit(Unit.Type.GROSS_VALUE, "USD"));
        transaction.addUnit(forexUnit(Unit.Type.FEE, "GBP"));
        assertMetadata(action.process(transaction, portfolio), "forex-currency-mismatch", "units");
    }

    @Test
    public void testGrossValueMismatchMetadata()
    {
        var transaction = delivery();
        transaction.addUnit(forexUnit(Unit.Type.GROSS_VALUE, "USD"));
        transaction.setMonetaryAmount(Money.of("EUR", 200));
        assertMetadata(new CheckForexGrossValueAction().process(transaction, new Portfolio()),
                        "gross-value-mismatch", "units");
    }

    private PortfolioTransaction delivery()
    {
        var transaction = new PortfolioTransaction();
        transaction.setType(PortfolioTransaction.Type.DELIVERY_INBOUND);
        transaction.setMonetaryAmount(Money.of("EUR", 100));
        transaction.setSecurity(new Security("Security", "EUR"));
        return transaction;
    }

    private Unit forexUnit(Unit.Type type, String currency)
    {
        return new Unit(type, Money.of("EUR", 100), Money.of(currency, 100), BigDecimal.ONE);
    }

    private void assertMetadata(Status status, String ruleCode, String field)
    {
        assertThat(status.getCode(), is(Status.Code.ERROR));
        assertThat(status.getRuleCode(), is(ruleCode));
        assertThat(status.getField(), is(field));
    }
}
