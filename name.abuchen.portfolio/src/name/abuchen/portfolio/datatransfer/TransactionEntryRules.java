package name.abuchen.portfolio.datatransfer;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import name.abuchen.portfolio.datatransfer.ImportAction.Status;
import name.abuchen.portfolio.datatransfer.actions.CheckCurrenciesAction;
import name.abuchen.portfolio.datatransfer.actions.CheckForexGrossValueAction;
import name.abuchen.portfolio.datatransfer.actions.CheckSecurityRelatedValuesAction;
import name.abuchen.portfolio.datatransfer.actions.CheckTransactionDateAction;
import name.abuchen.portfolio.datatransfer.actions.CheckValidTypesAction;
import name.abuchen.portfolio.model.Account;
import name.abuchen.portfolio.model.AccountTransaction;
import name.abuchen.portfolio.model.AccountTransferEntry;
import name.abuchen.portfolio.model.BuySellEntry;
import name.abuchen.portfolio.model.Portfolio;
import name.abuchen.portfolio.model.PortfolioTransaction;
import name.abuchen.portfolio.model.PortfolioTransferEntry;
import name.abuchen.portfolio.model.Transaction;
import name.abuchen.portfolio.model.Transaction.Unit;
import name.abuchen.portfolio.money.CurrencyUnit;
import name.abuchen.portfolio.money.Money;
import name.abuchen.portfolio.money.Values;

/**
 * Validation of the state written by transaction dialogs, without attaching it
 * to a client. Owners are explicit so callers can validate before insertion.
 * Results contain all applicable errors and do not contain presentation text.
 * Parsing precision and a separately entered quote are not represented by the
 * model: callers must validate precision before constructing model values.
 */
@SuppressWarnings("nls")
public final class TransactionEntryRules
{
    TransactionEntryRules()
    {
    }

    public List<Status> validate(AccountTransaction transaction, Account account)
    {
        var errors = new Errors();
        errors.require(account != null, "owner-required", "cashAccount");
        common(transaction, errors);
        if (transaction.getType() == null)
            errors.add("type-required", "type");
        else
        {
            errors.add(new CheckValidTypesAction().process(transaction, account));
            errors.add(new CheckSecurityRelatedValuesAction().process(transaction, account));
            // AccountTransactionModel.supportsShares/Fees/TaxUnits.
            errors.require(transaction.getShares() == 0 || transaction.getType() == AccountTransaction.Type.DIVIDENDS,
                            "shares-not-allowed", "shares");
            transaction.getUnits().forEach(unit -> {
                errors.require(unit.getType() != Unit.Type.FEE
                                || transaction.getType() == AccountTransaction.Type.DIVIDENDS,
                                "fees-not-allowed", "units");
                errors.require(unit.getType() != Unit.Type.TAX
                                || transaction.getType() == AccountTransaction.Type.DIVIDENDS
                                || transaction.getType() == AccountTransaction.Type.INTEREST,
                                "taxes-not-allowed", "units");
            });
        }
        // AccountTransactionModel.applyChanges and calculateStatus compare dates,
        // not times; optional-security fee/tax entries can also carry an ex-date.
        errors.require(transaction.getExDate() == null || transaction.getSecurity() != null,
                        "ex-date-not-allowed", "exDate");
        errors.require(transaction.getExDate() == null || transaction.getDateTime() == null
                        || !transaction.getExDate().toLocalDate().isAfter(transaction.getDateTime().toLocalDate()),
                        "ex-date-after-date", "exDate");
        if (usableCurrency(transaction))
        {
            errors.addAll(new CheckCurrenciesAction().validate(transaction, account));
            if (transaction.getType() != null)
                gross(transaction, transaction.getType().isCredit(), errors);
        }
        if (transaction.getSecurity() == null)
            errors.require(transaction.getUnits().noneMatch(unit -> unit.getForex() != null),
                            "forex-not-allowed", "units");
        return errors.result();
    }

    public List<Status> validate(PortfolioTransaction transaction, Portfolio portfolio)
    {
        var errors = new Errors();
        errors.require(portfolio != null, "owner-required", "investmentAccount");
        // SecurityDeliveryModel.applyChanges requires the reference account even
        // though a delivery creates no cash-account record.
        errors.require(portfolio == null || portfolio.getReferenceAccount() != null,
                        "reference-account-required", "investmentAccount");
        if (transaction.getType() != null)
            errors.add(new CheckValidTypesAction().process(transaction, portfolio));
        security(transaction, portfolio, errors);
        return errors.result();
    }

    public List<Status> validate(BuySellEntry entry, Account account, Portfolio portfolio)
    {
        var errors = new Errors();
        errors.require(account != null, "owner-required", "cashAccount");
        errors.require(portfolio != null, "owner-required", "investmentAccount");
        var cash = entry.getAccountTransaction();
        var security = entry.getPortfolioTransaction();
        common(cash, errors);
        errors.require(security.getType() == PortfolioTransaction.Type.BUY
                        || security.getType() == PortfolioTransaction.Type.SELL, "invalid-type", "type");
        security(security, portfolio, errors);
        if (usableCurrency(cash))
            errors.addAll(new CheckCurrenciesAction().validate(cash, account));
        errors.require(cash.getUnits().findAny().isEmpty(), "units-not-allowed", "units");
        // BuySellEntry.setShares writes only the portfolio half.
        errors.require(cash.getShares() == 0, "shares-not-allowed", "shares");
        errors.require(cash.getExDate() == null, "ex-date-not-allowed", "exDate");
        errors.require(cash.getAmount() == security.getAmount(), "amount-mismatch", "amount");
        errors.require(Objects.equals(cash.getCurrencyCode(), security.getCurrencyCode()),
                        "currency-mismatch", "amount.currency");
        errors.require(cash.getType() == (security.getType() == PortfolioTransaction.Type.BUY
                        ? AccountTransaction.Type.BUY : AccountTransaction.Type.SELL), "invalid-type", "type");
        paired(cash, security, errors);
        return errors.result();
    }

    public List<Status> validate(AccountTransferEntry entry, Account source, Account target)
    {
        var errors = new Errors();
        owners(source, target, "fromCashAccount", "toCashAccount", errors);
        var from = entry.getSourceTransaction();
        var to = entry.getTargetTransaction();
        common(from, errors);
        common(to, errors);
        errors.require(from.getType() == AccountTransaction.Type.TRANSFER_OUT
                        && to.getType() == AccountTransaction.Type.TRANSFER_IN, "invalid-type", "type");
        errors.require(from.getAmount() > 0, "amount-required", "amount");
        errors.require(to.getAmount() > 0, "amount-required", "forex");
        errors.require(from.getSecurity() == null && to.getSecurity() == null,
                        "instrument-not-allowed", "instrument");
        errors.require(from.getShares() == 0 && to.getShares() == 0, "shares-not-allowed", "shares");
        errors.require(from.getExDate() == null && to.getExDate() == null, "ex-date-not-allowed", "exDate");
        if (usableCurrency(from))
            errors.addAll(new CheckCurrenciesAction().validate(from, source));
        if (usableCurrency(to))
            errors.addAll(new CheckCurrenciesAction().validate(to, target));
        errors.require(to.getUnits().findAny().isEmpty(), "units-not-allowed", "units");
        errors.require(from.getUnits().allMatch(unit -> unit.getType() == Unit.Type.GROSS_VALUE),
                        "units-not-allowed", "units");
        if (usableCurrency(from) && usableCurrency(to))
        {
            // AccountTransferModel.applyChanges stores the inverse of the dialog
            // rate, and the outgoing unit's forex is the incoming amount.
            if (from.getCurrencyCode().equals(to.getCurrencyCode()))
            {
                errors.require(from.getAmount() == to.getAmount(), "amount-mismatch", "forex");
                errors.require(from.getUnits().findAny().isEmpty(), "forex-not-allowed", "units");
            }
            else
            {
                var gross = from.getUnit(Unit.Type.GROSS_VALUE);
                errors.require(gross.isPresent(), "gross-value-required", "units");
                gross.ifPresent(unit -> {
                    errors.require(unit.getAmount().equals(from.getMonetaryAmount()), "gross-value-mismatch", "units");
                    errors.require(to.getMonetaryAmount().equals(unit.getForex()), "forex-mismatch", "units");
                    if (unit.getExchangeRate() != null && unit.getExchangeRate().signum() > 0)
                        errors.require(CheckForexGrossValueAction.isWithinEntryTolerance(to.getAmount(), from.getAmount(),
                                        BigDecimal.ONE.divide(unit.getExchangeRate(), Values.MC)),
                                        "forex-amount-mismatch", "forex");
                });
            }
        }
        paired(from, to, errors);
        return errors.result();
    }

    public List<Status> validate(PortfolioTransferEntry entry, Portfolio source, Portfolio target)
    {
        var errors = new Errors();
        owners(source, target, "fromInvestmentAccount", "toInvestmentAccount", errors);
        var from = entry.getSourceTransaction();
        var to = entry.getTargetTransaction();
        // SecurityTransferModel writes the same instrument-currency value and
        // shares to both halves; it has no fees, taxes or currency selector.
        for (var transaction : List.of(from, to))
        {
            common(transaction, errors);
            errors.require(transaction.getAmount() > 0, "amount-required", "amount");
            errors.require(transaction.getShares() > 0, "shares-required", "shares");
            errors.require(transaction.getSecurity() != null, "instrument-required", "instrument");
            errors.require(transaction.getUnits().findAny().isEmpty(), "units-not-allowed", "units");
            if (transaction.getSecurity() != null)
                errors.require(Objects.equals(transaction.getCurrencyCode(), transaction.getSecurity().getCurrencyCode()),
                                "currency-mismatch", "amount.currency");
        }
        errors.require(from.getType() == PortfolioTransaction.Type.TRANSFER_OUT
                        && to.getType() == PortfolioTransaction.Type.TRANSFER_IN, "invalid-type", "type");
        errors.require(from.getAmount() == to.getAmount(), "amount-mismatch", "amount");
        errors.require(from.getShares() == to.getShares(), "shares-mismatch", "shares");
        paired(from, to, errors);
        return errors.result();
    }

    /** Validate before Unit's constructor/addUnit can throw, retaining all errors. */
    public List<Status> validateUnit(Unit.Type type, Money amount, Money forex, BigDecimal rate,
                    String transactionCurrency, String field)
    {
        var errors = new Errors();
        errors.require(type != null, "type-required", field + ".type");
        errors.require(amount != null, "amount-required", field + ".amount");
        if (amount != null)
        {
            errors.require(amount.getAmount() >= 0, "negative-value", field + ".amount");
            errors.require(knownCurrency(amount.getCurrencyCode()), "unsupported-currency", field + ".amount.currency");
            errors.require(Objects.equals(transactionCurrency, amount.getCurrencyCode()),
                            "currency-mismatch", field + ".amount.currency");
        }
        errors.require(type != Unit.Type.GROSS_VALUE || forex != null, "forex-required", field + ".forex");
        errors.require(forex == null || rate != null, "exchange-rate-required", field + ".exchangeRate");
        errors.require(rate == null || forex != null, "forex-required", field + ".forex");
        if (forex != null)
        {
            errors.require(forex.getAmount() >= 0, "negative-value", field + ".forex");
            errors.require(knownCurrency(forex.getCurrencyCode()), "unsupported-currency", field + ".forex.currency");
            errors.require(!Objects.equals(transactionCurrency, forex.getCurrencyCode()), "forex-not-allowed", field + ".forex");
        }
        if (rate != null)
            errors.require(rate.signum() > 0, "exchange-rate-required", field + ".exchangeRate");
        if (amount != null && forex != null && rate != null && rate.signum() > 0)
        {
            errors.require(CheckForexGrossValueAction.isConversionWithinRange(BigDecimal.valueOf(forex.getAmount()), rate),
                            "amount-out-of-range", field);
            // Unit's own invariant is also required. The entry tolerance is
            // checked on the transaction's gross (or transfer direction) below.
            errors.require(Unit.isWithinRoundingTolerance(amount, forex, rate), "forex-amount-mismatch", field);
        }
        return errors.result();
    }

    private void security(PortfolioTransaction transaction, Portfolio portfolio, Errors errors)
    {
        common(transaction, errors);
        errors.require(transaction.getType() != null, "type-required", "type");
        errors.require(transaction.getShares() > 0, "shares-required", "shares");
        errors.require(transaction.getSecurity() != null, "instrument-required", "instrument");
        // Outbound deliveries can dispose of worthless holdings without proceeds.
        errors.require(transaction.getAmount() > 0 || transaction.getType() == PortfolioTransaction.Type.DELIVERY_OUTBOUND,
                        transaction.getType() == PortfolioTransaction.Type.SELL ? "use-outbound-delivery" : "amount-required",
                        "amount");
        if (transaction.getType() != null && usableCurrency(transaction))
        {
            if (gross(transaction, !transaction.getType().isPurchase(), errors))
                errors.addAll(new CheckCurrenciesAction().validate(transaction, portfolio));
        }
    }

    private void common(Transaction transaction, Errors errors)
    {
        if (transaction instanceof AccountTransaction account)
            errors.add(new CheckTransactionDateAction().process(account, null));
        else
            errors.add(new CheckTransactionDateAction().process((PortfolioTransaction) transaction, null));
        errors.require(usableCurrency(transaction), "unsupported-currency", "amount.currency");
        errors.require(transaction.getAmount() >= 0, "negative-value", "amount");
        errors.require(transaction.getShares() >= 0, "negative-value", "shares");
        if (transaction.getSecurity() != null)
            errors.add(new CheckCurrenciesAction().process(transaction.getSecurity()));
        var units = transaction.getUnits().toList();
        for (int index = 0; index < units.size(); index++)
        {
            var unit = units.get(index);
            errors.addAll(validateUnit(unit.getType(), unit.getAmount(), unit.getForex(), unit.getExchangeRate(),
                            transaction.getCurrencyCode(), "units[" + index + "]"));
        }
        errors.require(units.stream().filter(unit -> unit.getType() == Unit.Type.GROSS_VALUE).count() <= 1,
                        "duplicate-gross-value", "units");
    }

    private boolean gross(Transaction transaction, boolean addCharges, Errors errors)
    {
        // common() reports incompatible retained units individually. Gross
        // arithmetic is meaningless until their currencies match, but independent
        // currency/security checks must still run.
        if (transaction.getUnits().anyMatch(unit -> !Objects.equals(transaction.getCurrencyCode(),
                        unit.getAmount().getCurrencyCode())))
            return true;

        // BigInteger prevents an overflowing fee sum from appearing to be a
        // valid positive amount before the model's long arithmetic is used.
        var charges = transaction.getUnits().filter(unit -> unit.getType() != Unit.Type.GROSS_VALUE)
                        .map(unit -> BigInteger.valueOf(unit.getAmount().getAmount())).reduce(BigInteger.ZERO, BigInteger::add);
        var gross = BigInteger.valueOf(transaction.getAmount()).add(addCharges ? charges : charges.negate());
        if (charges.bitLength() > 63 || gross.bitLength() > 63)
        {
            errors.add("amount-out-of-range", "amount");
            return false;
        }
        var allowZero = transaction instanceof PortfolioTransaction security
                        && security.getType() == PortfolioTransaction.Type.DELIVERY_OUTBOUND;
        errors.require(gross.signum() > 0 || allowZero && gross.signum() == 0, "gross-value-required", "amount");
        var unit = transaction.getUnit(Unit.Type.GROSS_VALUE);
        if (unit.isPresent())
        {
            var value = unit.get();
            errors.require(value.getForex().getAmount() > 0 || allowZero && value.getForex().getAmount() == 0,
                            "gross-value-required", "units");
            if (transaction instanceof AccountTransaction account)
                errors.add(new CheckForexGrossValueAction(CheckForexGrossValueAction.Profile.ENTRY).process(account, null));
            else
                errors.add(new CheckForexGrossValueAction(CheckForexGrossValueAction.Profile.ENTRY)
                                .process((PortfolioTransaction) transaction, null));
            errors.require(CheckForexGrossValueAction.isWithinEntryTolerance(gross.longValue(), value.getForex().getAmount(),
                            value.getExchangeRate()), "forex-amount-mismatch", "units");
            // Totals use the sum of independently rounded charge units, just as
            // the dialogs write them. Rounding a combined fee/tax sum can differ.
            var forexCharges = transaction.getUnits().filter(item -> item.getType() != Unit.Type.GROSS_VALUE
                            && item.getForex() != null).toList();
            for (var charge : forexCharges)
            {
                errors.require(value.getExchangeRate().compareTo(charge.getExchangeRate()) == 0,
                                "exchange-rate-mismatch", "units");
                errors.require(matchesChargeConversion(charge, value.getExchangeRate()),
                                "forex-amount-mismatch", "units");
            }
        }
        return true;
    }

    private static boolean matchesChargeConversion(Unit charge, BigDecimal rate)
    {
        var amount = charge.getAmount().getAmount();
        var forex = charge.getForex().getAmount();
        // The dialogs convert each charge as Math.round(forex * rate) in double
        // arithmetic; any value they write must be accepted. Clients doing
        // exact arithmetic get the decimal HALF_UP result, which can differ.
        if (amount == Math.round(forex * rate.doubleValue()))
            return true;
        var decimal = BigDecimal.valueOf(forex).multiply(rate).setScale(0, RoundingMode.HALF_UP);
        return decimal.compareTo(BigDecimal.valueOf(amount)) == 0;
    }

    private static void owners(Object source, Object target, String sourceField, String targetField, Errors errors)
    {
        // AccountsMustBeDifferentValidator / PortfoliosMustBeDifferentValidator.
        errors.require(source != null, "owner-required", sourceField);
        errors.require(target != null, "owner-required", targetField);
        errors.require(source == null || source != target, "same-owner", targetField);
    }

    private static void paired(Transaction from, Transaction to, Errors errors)
    {
        errors.require(Objects.equals(from.getDateTime(), to.getDateTime()), "date-mismatch", "dateTime");
        errors.require(from.getSecurity() == to.getSecurity(), "instrument-mismatch", "instrument");
    }

    private static boolean usableCurrency(Transaction transaction)
    {
        return knownCurrency(transaction.getCurrencyCode());
    }

    private static boolean knownCurrency(String currency)
    {
        return currency != null && CurrencyUnit.getInstance(currency) != null;
    }

    private static final class Errors
    {
        private final List<Status> statuses = new ArrayList<>();

        void require(boolean valid, String code, String field)
        {
            if (!valid)
                add(code, field);
        }

        void add(String code, String field)
        {
            if (statuses.stream().noneMatch(status -> code.equals(status.getRuleCode()) && field.equals(status.getField())))
                statuses.add(new Status(Status.Code.ERROR, null, code, field));
        }

        void add(Status status)
        {
            if (status.getCode() != Status.Code.OK)
                add(status.getRuleCode(), status.getField());
        }

        void addAll(List<Status> other)
        {
            other.forEach(this::add);
        }

        List<Status> result()
        {
            return List.copyOf(statuses);
        }
    }
}
