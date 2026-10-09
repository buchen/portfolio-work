package name.abuchen.portfolio.datatransfer;

import static org.hamcrest.CoreMatchers.hasItem;
import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.MatcherAssert.assertThat;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

import org.junit.Test;

import name.abuchen.portfolio.datatransfer.ImportAction.Status;
import name.abuchen.portfolio.datatransfer.actions.CheckForexGrossValueAction;
import name.abuchen.portfolio.model.Account;
import name.abuchen.portfolio.model.AccountTransaction;
import name.abuchen.portfolio.model.AccountTransferEntry;
import name.abuchen.portfolio.model.BuySellEntry;
import name.abuchen.portfolio.model.Portfolio;
import name.abuchen.portfolio.model.PortfolioTransaction;
import name.abuchen.portfolio.model.PortfolioTransferEntry;
import name.abuchen.portfolio.model.Security;
import name.abuchen.portfolio.model.Transaction.Unit;
import name.abuchen.portfolio.money.Money;

@SuppressWarnings("nls")
public class TransactionEntryRulesTest
{
    private static final LocalDateTime DATE = LocalDateTime.of(2026, 1, 2, 12, 0);
    private final TransactionEntryRules rules = TransactionRules.entryProfile();

    @Test
    public void testCollectsIndependentErrorsWithoutOwnersOrModelDefaults()
    {
        var transaction = new AccountTransaction();
        var errors = rules.validate(transaction, null);
        assertError(errors, "owner-required", "cashAccount");
        assertError(errors, "date-required", "dateTime");
        assertError(errors, "type-required", "type");
        assertError(errors, "unsupported-currency", "amount.currency");
        assertThat(errors.stream().allMatch(status -> status.getMessage() == null), is(true));
    }

    @Test
    public void testCashTypesAndUnitRestrictions()
    {
        var account = account("EUR");
        for (var type : List.of(AccountTransaction.Type.DEPOSIT, AccountTransaction.Type.REMOVAL,
                        AccountTransaction.Type.INTEREST, AccountTransaction.Type.INTEREST_CHARGE,
                        AccountTransaction.Type.FEES, AccountTransaction.Type.FEES_REFUND,
                        AccountTransaction.Type.TAXES, AccountTransaction.Type.TAX_REFUND,
                        AccountTransaction.Type.DIVIDENDS))
        {
            var transaction = cash(type);
            if (type == AccountTransaction.Type.DIVIDENDS)
                transaction.setSecurity(security("EUR"));
            assertThat(rules.validate(transaction, account).isEmpty(), is(true));
            transaction.addUnit(new Unit(Unit.Type.FEE, Money.of("EUR", 10)));
            transaction.addUnit(new Unit(Unit.Type.TAX, Money.of("EUR", 10)));
            var errors = rules.validate(transaction, account);
            assertThat(hasCode(errors, "fees-not-allowed"), is(type != AccountTransaction.Type.DIVIDENDS));
            assertThat(hasCode(errors, "taxes-not-allowed"), is(type != AccountTransaction.Type.DIVIDENDS
                            && type != AccountTransaction.Type.INTEREST));
        }
    }

    @Test
    public void testExDateUsesLocalDateAndAllowsOptionalSecurity()
    {
        var transaction = cash(AccountTransaction.Type.TAXES);
        transaction.setExDate(DATE.withHour(23));
        assertError(rules.validate(transaction, account("EUR")), "ex-date-not-allowed", "exDate");
        transaction.setSecurity(security("EUR"));
        assertThat(rules.validate(transaction, account("EUR")).isEmpty(), is(true));
        transaction.setExDate(DATE.plusDays(1));
        assertError(rules.validate(transaction, account("EUR")), "ex-date-after-date", "exDate");
    }

    @Test
    public void testFeeSharesRemainAcceptedOnlyByImporter()
    {
        var transaction = cash(AccountTransaction.Type.FEES);
        transaction.setSecurity(security("EUR"));
        transaction.setShares(100);
        var account = account("EUR");
        assertThat(TransactionRules.importProfile().stream().allMatch(action -> action.process(transaction, account)
                        .getCode() == Status.Code.OK), is(true));
        assertError(rules.validate(transaction, account), "shares-not-allowed", "shares");
    }

    @Test
    public void testCashGrossPositiveButNetMayBeZero()
    {
        // AccountTransactionModel.calculateStatus rejects zero gross for every
        // cash type, even a statement's zero interest or fee booking.
        for (var type : List.of(AccountTransaction.Type.DEPOSIT, AccountTransaction.Type.REMOVAL,
                        AccountTransaction.Type.INTEREST, AccountTransaction.Type.INTEREST_CHARGE,
                        AccountTransaction.Type.FEES, AccountTransaction.Type.FEES_REFUND,
                        AccountTransaction.Type.TAXES, AccountTransaction.Type.TAX_REFUND,
                        AccountTransaction.Type.DIVIDENDS))
        {
            var transaction = cash(type);
            if (type == AccountTransaction.Type.DIVIDENDS)
                transaction.setSecurity(security("EUR"));
            transaction.setAmount(0);
            assertError(rules.validate(transaction, account("EUR")), "gross-value-required", "amount");
            transaction.setAmount(-1);
            transaction.setShares(-1);
            var errors = rules.validate(transaction, account("EUR"));
            assertError(errors, "negative-value", "amount");
            assertError(errors, "negative-value", "shares");
        }

        var transaction = cash(AccountTransaction.Type.DIVIDENDS);
        transaction.setSecurity(security("EUR"));
        transaction.setAmount(0);
        assertError(rules.validate(transaction, account("EUR")), "gross-value-required", "amount");
        transaction.addUnit(new Unit(Unit.Type.TAX, Money.of("EUR", 100)));
        assertThat(rules.validate(transaction, account("EUR")).isEmpty(), is(true));
    }

    @Test
    public void testNegativeNetCannotBeHiddenByPositiveGross()
    {
        for (var type : List.of(AccountTransaction.Type.INTEREST, AccountTransaction.Type.DIVIDENDS))
        {
            var transaction = cash(type);
            if (type == AccountTransaction.Type.DIVIDENDS)
                transaction.setSecurity(security("EUR"));
            transaction.setAmount(0);
            transaction.addUnit(new Unit(Unit.Type.TAX, Money.of("EUR", 100)));
            assertThat(rules.validate(transaction, account("EUR")).isEmpty(), is(true));
            transaction.setAmount(-1);
            assertError(rules.validate(transaction, account("EUR")), "negative-value", "amount");
        }
    }

    @Test
    public void testNegativeInvestmentAmountsAndShares()
    {
        var account = account("EUR");
        var portfolio = new Portfolio();
        portfolio.setReferenceAccount(account);
        for (var type : List.of(PortfolioTransaction.Type.DELIVERY_INBOUND, PortfolioTransaction.Type.DELIVERY_OUTBOUND))
        {
            var transaction = delivery(type);
            transaction.setAmount(-1);
            transaction.setShares(-1);
            var errors = rules.validate(transaction, portfolio);
            assertError(errors, "negative-value", "amount");
            assertError(errors, "negative-value", "shares");
        }
        for (var type : List.of(PortfolioTransaction.Type.BUY, PortfolioTransaction.Type.SELL))
        {
            var entry = new BuySellEntry(type);
            entry.setDate(DATE);
            entry.setCurrencyCode("EUR");
            entry.setSecurity(security("EUR"));
            entry.setAmount(-1);
            entry.setShares(-1);
            var errors = rules.validate(entry, account, portfolio);
            assertError(errors, "negative-value", "amount");
            assertError(errors, "negative-value", "shares");
        }
    }

    @Test
    public void testNegativeValuesOnEitherTransferHalf()
    {
        var cash = new AccountTransferEntry();
        cash.setDate(DATE);
        cash.setCurrencyCode("EUR");
        for (var transaction : List.of(cash.getSourceTransaction(), cash.getTargetTransaction()))
        {
            cash.setAmount(100);
            transaction.setAmount(-1);
            transaction.setShares(-1);
            var errors = rules.validate(cash, account("EUR"), account("EUR"));
            assertError(errors, "negative-value", transaction == cash.getSourceTransaction() ? "amount" : "targetAmount");
            assertError(errors, "negative-value", "shares");
            transaction.setShares(0);
        }

        var security = new PortfolioTransferEntry();
        security.setDate(DATE);
        security.setCurrencyCode("EUR");
        security.setSecurity(security("EUR"));
        for (var transaction : List.of(security.getSourceTransaction(), security.getTargetTransaction()))
        {
            security.setAmount(100);
            security.setShares(100);
            transaction.setAmount(-1);
            transaction.setShares(-1);
            var errors = rules.validate(security, new Portfolio(), new Portfolio());
            assertError(errors, "negative-value", "amount");
            assertError(errors, "negative-value", "shares");
        }
    }

    @Test
    public void testNegativeUnitValuesAndNonPositiveRates()
    {
        for (var type : Unit.Type.values())
        {
            assertError(rules.validateUnit(type, Money.of("EUR", -1), null, null, "EUR", "units[0]"),
                            "negative-value", "units[0].amount");
            assertError(rules.validateUnit(type, Money.of("EUR", 1), Money.of("USD", -1), BigDecimal.ONE,
                            "EUR", "units[0]"), "negative-value", "units[0].forex");
            for (var rate : List.of(BigDecimal.ZERO, BigDecimal.ONE.negate()))
                assertError(rules.validateUnit(type, Money.of("EUR", 1), Money.of("USD", 1), rate,
                                "EUR", "units[0]"), "exchange-rate-required", "units[0].exchangeRate");
        }
    }

    @Test
    public void testDeliveryRequiresSharesAndReferenceAccount()
    {
        var transaction = delivery(PortfolioTransaction.Type.DELIVERY_OUTBOUND);
        var portfolio = new Portfolio();
        transaction.setShares(0);
        transaction.setAmount(0);
        var errors = rules.validate(transaction, portfolio);
        assertError(errors, "reference-account-required", "investmentAccount");
        assertError(errors, "shares-required", "shares");
        portfolio.setReferenceAccount(account("EUR"));
        transaction.setShares(100);
        assertThat(rules.validate(transaction, portfolio).isEmpty(), is(true));
        transaction.addUnit(new Unit(Unit.Type.FEE, Money.of("EUR", 100)));
        assertThat(rules.validate(transaction, portfolio).isEmpty(), is(true));
        transaction.setType(PortfolioTransaction.Type.DELIVERY_INBOUND);
        assertError(rules.validate(transaction, portfolio), "amount-required", "amount");
        assertError(rules.validate(transaction, portfolio), "gross-value-required", "amount");
    }

    @Test
    public void testZeroValueOutboundDeliveryInInstrumentAndForeignCurrency()
    {
        var portfolio = new Portfolio();
        portfolio.setReferenceAccount(account("EUR"));
        for (var currency : List.of("EUR", "TWD"))
        {
            var transaction = delivery(PortfolioTransaction.Type.DELIVERY_OUTBOUND);
            transaction.setSecurity(security(currency));
            transaction.setAmount(0);
            transaction.setShares(11140000);
            if (!"EUR".equals(currency))
            {
                assertError(rules.validate(transaction, portfolio), "gross-value-required", "units");
                transaction.addUnit(new Unit(Unit.Type.GROSS_VALUE, Money.of("EUR", 0), Money.of(currency, 0),
                                new BigDecimal("0.03")));
            }
            assertThat(rules.validate(transaction, portfolio).isEmpty(), is(true));
            transaction.setAmount(-1);
            assertError(rules.validate(transaction, portfolio), "negative-value", "amount");
            transaction.setAmount(0);
            transaction.setShares(0);
            assertError(rules.validate(transaction, portfolio), "shares-required", "shares");
            transaction.setShares(11140000);
            transaction.setType(PortfolioTransaction.Type.DELIVERY_INBOUND);
            var errors = rules.validate(transaction, portfolio);
            assertError(errors, "amount-required", "amount");
            assertError(errors, "gross-value-required", "amount");
            if (!"EUR".equals(currency))
                assertError(errors, "gross-value-required", "units");
        }
    }

    @Test
    public void testBuySellZeroNetAndUnattachedOwners()
    {
        var entry = new BuySellEntry(PortfolioTransaction.Type.SELL);
        entry.setDate(DATE);
        entry.setCurrencyCode("EUR");
        entry.setSecurity(security("EUR"));
        entry.setShares(100);
        entry.setAmount(0);
        entry.getPortfolioTransaction().addUnit(new Unit(Unit.Type.FEE, Money.of("EUR", 100)));
        assertError(rules.validate(entry, account("EUR"), new Portfolio()), "use-outbound-delivery", "amount");
        entry.setAmount(100);
        assertThat(rules.validate(entry, account("EUR"), new Portfolio()).isEmpty(), is(true));
        entry.setType(PortfolioTransaction.Type.BUY);
        assertError(rules.validate(entry, account("EUR"), new Portfolio()), "gross-value-required", "amount");
        entry.setAmount(101);
        assertThat(rules.validate(entry, account("EUR"), new Portfolio()).isEmpty(), is(true));
    }

    @Test
    public void testStrictGrossAgreementDoesNotTightenImportProfile()
    {
        var transaction = cash(AccountTransaction.Type.DIVIDENDS);
        transaction.setSecurity(security("USD"));
        transaction.setAmount(10001);
        transaction.addUnit(new Unit(Unit.Type.GROSS_VALUE, Money.of("EUR", 10000), Money.of("USD", 10000), BigDecimal.ONE));
        assertThat(new CheckForexGrossValueAction().process(transaction, account("EUR")).getCode(), is(Status.Code.OK));
        assertError(rules.validate(transaction, account("EUR")), "gross-value-mismatch", "units");
        transaction.setAmount(10000);
        assertThat(rules.validate(transaction, account("EUR")).isEmpty(), is(true));
    }

    @Test
    public void testBuySellCashHalfHasNeitherSharesNorExDate()
    {
        var entry = new BuySellEntry(PortfolioTransaction.Type.BUY);
        entry.setDate(DATE);
        entry.setCurrencyCode("EUR");
        entry.setSecurity(security("EUR"));
        entry.setShares(100);
        entry.setAmount(100);
        assertThat(rules.validate(entry, account("EUR"), new Portfolio()).isEmpty(), is(true));
        entry.getAccountTransaction().setShares(100);
        entry.getAccountTransaction().setExDate(DATE);
        var errors = rules.validate(entry, account("EUR"), new Portfolio());
        assertError(errors, "shares-not-allowed", "shares");
        assertError(errors, "ex-date-not-allowed", "exDate");
    }

    @Test
    public void testSaturatedForexConversionIsRejectedOnlyByEntry()
    {
        var rate = BigDecimal.valueOf(2);
        var amount = Money.of("EUR", Long.MAX_VALUE);
        var forex = Money.of("USD", Long.MAX_VALUE);
        assertThat(Unit.isWithinRoundingTolerance(amount, forex, rate), is(true));
        assertThat(CheckForexGrossValueAction.isWithinEntryTolerance(Long.MAX_VALUE, Long.MAX_VALUE, rate), is(false));
        assertError(rules.validateUnit(Unit.Type.GROSS_VALUE, amount, forex, rate, "EUR", "units[0]"),
                        "amount-out-of-range", "units[0]");

        var transaction = cash(AccountTransaction.Type.DIVIDENDS);
        transaction.setSecurity(security("USD"));
        transaction.setAmount(Long.MAX_VALUE);
        transaction.addUnit(new Unit(Unit.Type.GROSS_VALUE, amount, forex, rate));
        assertError(rules.validate(transaction, account("EUR")), "forex-amount-mismatch", "units");
        transaction.setAmount(0);
        transaction.addUnit(new Unit(Unit.Type.FEE, amount, forex, rate));
        assertError(rules.validate(transaction, account("EUR")), "amount-out-of-range", "units[1]");
    }

    @Test
    public void testEntryForexToleranceIsNarrowerThanUnitTolerance()
    {
        var transaction = cash(AccountTransaction.Type.DIVIDENDS);
        transaction.setSecurity(security("USD"));
        transaction.setAmount(10002);
        transaction.addUnit(new Unit(Unit.Type.GROSS_VALUE, Money.of("EUR", 10002), Money.of("USD", 10000), BigDecimal.ONE));
        assertThat(new CheckForexGrossValueAction().process(transaction, account("EUR")).getCode(), is(Status.Code.OK));
        assertError(rules.validate(transaction, account("EUR")), "forex-amount-mismatch", "units");
        assertThat(CheckForexGrossValueAction.isWithinEntryTolerance(10001, 10000, BigDecimal.ONE), is(true));
    }

    @Test
    public void testCurrencyErrorsAreCollectedAlongsideOwnerCurrencyMismatch()
    {
        var transaction = cash(AccountTransaction.Type.DIVIDENDS);
        transaction.setSecurity(security("USD"));
        var errors = rules.validate(transaction, account("GBP"));
        assertError(errors, "currency-mismatch", "amount.currency");
        assertError(errors, "gross-value-required", "units");
    }

    @Test
    public void testCashTransferSameOwnerAmountsAndForexDirection()
    {
        var source = account("EUR");
        var target = account("USD");
        var entry = new AccountTransferEntry();
        entry.setDate(DATE);
        entry.getSourceTransaction().setMonetaryAmount(Money.of("EUR", 10000));
        entry.getTargetTransaction().setMonetaryAmount(Money.of("USD", 20000));
        entry.getSourceTransaction().addUnit(new Unit(Unit.Type.GROSS_VALUE, Money.of("EUR", 10000),
                        Money.of("USD", 20000), new BigDecimal("0.5")));
        assertThat(rules.validate(entry, source, target).isEmpty(), is(true));
        entry.getTargetTransaction().setAmount(20001);
        assertError(rules.validate(entry, source, target), "forex-mismatch", "units");
        entry.setCurrencyCode("EUR");
        entry.getSourceTransaction().clearUnits();
        entry.setAmount(10000);
        assertError(rules.validate(entry, source, source), "same-owner", "toCashAccount");
        assertThat(rules.validate(entry, source, account("EUR")).isEmpty(), is(true));
        entry.getTargetTransaction().setAmount(0);
        var errors = rules.validate(entry, source, target);
        assertError(errors, "amount-required", "targetAmount");
        assertError(errors, "amount-mismatch", "targetAmount");
        assertError(errors, "currency-mismatch", "targetAmount.currency");
        entry.getTargetTransaction().setCurrencyCode("XYZ");
        assertError(rules.validate(entry, source, target), "unsupported-currency", "targetAmount.currency");
    }

    @Test
    public void testSecurityTransferUsesInstrumentCurrencyAndPositiveValues()
    {
        var entry = new PortfolioTransferEntry();
        entry.setDate(DATE);
        entry.setSecurity(security("EUR"));
        entry.setCurrencyCode("EUR");
        entry.setAmount(100);
        entry.setShares(100);
        var source = new Portfolio();
        assertThat(rules.validate(entry, source, new Portfolio()).isEmpty(), is(true));
        assertError(rules.validate(entry, source, source), "same-owner", "toInvestmentAccount");
        entry.setCurrencyCode("USD");
        entry.setShares(0);
        entry.setAmount(0);
        var errors = rules.validate(entry, source, new Portfolio());
        assertError(errors, "currency-mismatch", "amount.currency");
        assertError(errors, "shares-required", "shares");
        assertError(errors, "amount-required", "amount");
    }

    @Test
    public void testInvalidUnitsCanBeCheckedBeforeConstruction()
    {
        var errors = rules.validateUnit(Unit.Type.GROSS_VALUE, Money.of("USD", -10), null, BigDecimal.ZERO,
                        "EUR", "units[0]");
        assertError(errors, "negative-value", "units[0].amount");
        assertError(errors, "currency-mismatch", "units[0].amount.currency");
        assertError(errors, "forex-required", "units[0].forex");
        assertError(errors, "exchange-rate-required", "units[0].exchangeRate");
    }

    @Test
    public void testDerivedGrossCannotOverflow()
    {
        var transaction = cash(AccountTransaction.Type.DIVIDENDS);
        transaction.setSecurity(security("EUR"));
        transaction.setAmount(Long.MAX_VALUE);
        transaction.addUnit(new Unit(Unit.Type.TAX, Money.of("EUR", 1)));
        assertError(rules.validate(transaction, account("EUR")), "amount-out-of-range", "amount");
    }

    @Test
    public void testRetiredReferencesAreNotInvalid()
    {
        var transaction = delivery(PortfolioTransaction.Type.DELIVERY_INBOUND);
        var portfolio = new Portfolio();
        var account = account("EUR");
        account.setRetired(true);
        portfolio.setReferenceAccount(account);
        portfolio.setRetired(true);
        transaction.getSecurity().setRetired(true);
        assertThat(rules.validate(transaction, portfolio).isEmpty(), is(true));
    }

    @Test
    public void testTotalsSumIndependentlyRoundedForeignCharges()
    {
        var transaction = delivery(PortfolioTransaction.Type.DELIVERY_INBOUND);
        transaction.setSecurity(security("USD"));
        transaction.setAmount(102);
        var rate = new BigDecimal("0.5");
        transaction.addUnit(new Unit(Unit.Type.GROSS_VALUE, Money.of("EUR", 100), Money.of("USD", 200), rate));
        transaction.addUnit(new Unit(Unit.Type.FEE, Money.of("EUR", 1), Money.of("USD", 1), rate));
        transaction.addUnit(new Unit(Unit.Type.TAX, Money.of("EUR", 1), Money.of("USD", 1), rate));
        var portfolio = new Portfolio();
        portfolio.setReferenceAccount(account("EUR"));
        assertThat(rules.validate(transaction, portfolio).isEmpty(), is(true));

        var dividend = cash(AccountTransaction.Type.DIVIDENDS);
        dividend.setSecurity(transaction.getSecurity());
        dividend.setAmount(98);
        dividend.addUnits(transaction.getUnits());
        assertThat(rules.validate(dividend, account("EUR")).isEmpty(), is(true));
    }

    @Test
    public void testForeignChargesAcceptDecimalAndHistoricalRounding()
    {
        var rate = new BigDecimal("0.7");
        var portfolio = new Portfolio();
        portfolio.setReferenceAccount(account("EUR"));
        for (var type : List.of(Unit.Type.FEE, Unit.Type.TAX))
        {
            for (var converted : List.of(31L, 32L))
            {
                var transaction = delivery(PortfolioTransaction.Type.DELIVERY_INBOUND);
                transaction.setSecurity(security("USD"));
                transaction.setAmount(700 + converted);
                transaction.addUnit(new Unit(Unit.Type.GROSS_VALUE, Money.of("EUR", 700), Money.of("USD", 1000), rate));
                // 45 * 0.7 is just below 31.5 in binary floating-point arithmetic.
                transaction.addUnit(new Unit(type, Money.of("EUR", converted), Money.of("USD", 45), rate));
                var securityErrors = rules.validate(transaction, portfolio);

                var dividend = cash(AccountTransaction.Type.DIVIDENDS);
                dividend.setSecurity(transaction.getSecurity());
                dividend.setAmount(700 - converted);
                dividend.addUnits(transaction.getUnits());
                var cashErrors = rules.validate(dividend, account("EUR"));

                for (var errors : List.of(securityErrors, cashErrors))
                    assertThat(errors.isEmpty(), is(true));
            }
        }
    }

    @Test
    public void testChargeRoundingDoesNotAllowArbitraryMinorUnitOffsets()
    {
        var rate = new BigDecimal("0.7");
        for (var converted : List.of(314L, 316L))
        {
            var dividend = cash(AccountTransaction.Type.DIVIDENDS);
            dividend.setSecurity(security("USD"));
            dividend.setAmount(7000 - converted);
            dividend.addUnit(new Unit(Unit.Type.GROSS_VALUE, Money.of("EUR", 7000), Money.of("USD", 10000), rate));
            // Unit's broad import tolerance permits these offsets; entry rules
            // accept only decimal or the exact historical double conversion.
            dividend.addUnit(new Unit(Unit.Type.TAX, Money.of("EUR", converted), Money.of("USD", 450), rate));
            assertError(rules.validate(dividend, account("EUR")), "forex-amount-mismatch", "units");
        }
    }

    @Test
    public void testLargeForeignChargeAcceptsDialogAndDecimalRounding()
    {
        var rate = new BigDecimal("0.7");
        var fee = 1_234_567_890_123_456_789L;
        // Beyond double precision the dialog result is 40 minor units below
        // the exact HALF_UP result; both must pass and nothing may overflow.
        var dialog = Math.round(fee * rate.doubleValue());
        assertThat(dialog, is(864_197_523_086_419_712L));
        for (var converted : List.of(dialog, 864_197_523_086_419_752L, 864_197_523_086_419_751L))
        {
            var dividend = cash(AccountTransaction.Type.DIVIDENDS);
            dividend.setSecurity(security("USD"));
            dividend.setAmount(1_400_000_000_000_000_000L - converted);
            dividend.addUnit(new Unit(Unit.Type.GROSS_VALUE, Money.of("EUR", 1_400_000_000_000_000_000L),
                            Money.of("USD", 2_000_000_000_000_000_000L), rate));
            dividend.addUnit(new Unit(Unit.Type.FEE, Money.of("EUR", converted), Money.of("USD", fee), rate));
            var errors = rules.validate(dividend, account("EUR"));
            if (converted == 864_197_523_086_419_751L)
                assertError(errors, "forex-amount-mismatch", "units");
            else
                assertThat(errors.isEmpty(), is(true));
        }
    }

    @Test
    public void testIncompatibleRetainedUnitsPreserveIndependentErrors()
    {
        var transaction = delivery(PortfolioTransaction.Type.DELIVERY_INBOUND);
        transaction.addUnit(new Unit(Unit.Type.FEE, Money.of("EUR", 10)));
        transaction.setCurrencyCode("USD");
        transaction.setShares(0);
        var errors = rules.validate(transaction, null);
        assertError(errors, "currency-mismatch", "units[0].amount.currency");
        assertError(errors, "gross-value-required", "units");
        assertError(errors, "shares-required", "shares");
        assertError(errors, "owner-required", "investmentAccount");

        transaction.setSecurity(null);
        assertError(rules.validate(transaction, null), "instrument-required", "instrument");
    }

    private static Account account(String currency)
    {
        var account = new Account();
        account.setCurrencyCode(currency);
        return account;
    }

    private static Security security(String currency)
    {
        var security = new Security();
        security.setCurrencyCode(currency);
        return security;
    }

    private static AccountTransaction cash(AccountTransaction.Type type)
    {
        var transaction = new AccountTransaction();
        transaction.setType(type);
        transaction.setDateTime(DATE);
        transaction.setMonetaryAmount(Money.of("EUR", 100));
        return transaction;
    }

    private static PortfolioTransaction delivery(PortfolioTransaction.Type type)
    {
        var transaction = new PortfolioTransaction();
        transaction.setType(type);
        transaction.setSecurity(security("EUR"));
        transaction.setShares(100);
        transaction.setDateTime(DATE);
        transaction.setMonetaryAmount(Money.of("EUR", 100));
        return transaction;
    }

    private static boolean hasCode(List<Status> errors, String code)
    {
        return errors.stream().anyMatch(status -> code.equals(status.getRuleCode()));
    }

    private static void assertError(List<Status> errors, String code, String field)
    {
        assertThat(errors.stream().map(status -> status.getRuleCode() + ":" + status.getField()).toList(),
                        hasItem(code + ":" + field));
    }
}
