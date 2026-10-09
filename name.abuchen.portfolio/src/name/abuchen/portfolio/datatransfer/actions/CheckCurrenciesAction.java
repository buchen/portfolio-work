package name.abuchen.portfolio.datatransfer.actions;

import java.text.MessageFormat;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import name.abuchen.portfolio.Messages;
import name.abuchen.portfolio.datatransfer.ImportAction;
import name.abuchen.portfolio.model.Account;
import name.abuchen.portfolio.model.AccountTransaction;
import name.abuchen.portfolio.model.AccountTransferEntry;
import name.abuchen.portfolio.model.BuySellEntry;
import name.abuchen.portfolio.model.Portfolio;
import name.abuchen.portfolio.model.PortfolioTransaction;
import name.abuchen.portfolio.model.PortfolioTransferEntry;
import name.abuchen.portfolio.model.Security;
import name.abuchen.portfolio.model.Transaction;
import name.abuchen.portfolio.model.Transaction.Unit;
import name.abuchen.portfolio.money.CurrencyUnit;
import name.abuchen.portfolio.money.Money;
import name.abuchen.portfolio.money.MoneyCollectors;
import name.abuchen.portfolio.money.Values;

public class CheckCurrenciesAction implements ImportAction
{
    private static final Set<AccountTransaction.Type> TRANSACTIONS_WO_UNITS = EnumSet.of(AccountTransaction.Type.BUY,
                    AccountTransaction.Type.SELL, AccountTransaction.Type.TRANSFER_IN);

    @Override
    public Status process(Security security)
    {
        String currency = security.getCurrencyCode();
        CurrencyUnit unit = CurrencyUnit.getInstance(currency);
        return unit != null ? Status.OK_STATUS
                        : new Status(Status.Code.ERROR,
                                        MessageFormat.format(Messages.MsgCheckUnsupportedCurrency, currency),
                                        "unsupported-currency", "currencyCode"); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Override
    public Status process(AccountTransaction transaction, Account account)
    {
        return validate(transaction, account, false).stream().findFirst().orElse(Status.OK_STATUS);
    }

    public List<Status> validate(AccountTransaction transaction, Account account)
    {
        return validate(transaction, account, true);
    }

    private List<Status> validate(AccountTransaction transaction, Account account, boolean collectAll)
    {
        var errors = new ArrayList<Status>();
        if (account != null && !account.getCurrencyCode().equals(transaction.getCurrencyCode()))
            errors.add(new Status(Status.Code.ERROR,
                            MessageFormat.format(Messages.MsgCheckTransactionCurrencyDoesNotMatchAccount,
                                            transaction.getCurrencyCode(), account.getCurrencyCode()),
                            "currency-mismatch", "amount.currency")); //$NON-NLS-1$ //$NON-NLS-2$

        if (!collectAll && !errors.isEmpty())
            return errors;

        if (transaction.getSecurity() != null)
        {
            if (TRANSACTIONS_WO_UNITS.contains(transaction.getType()))
            {
                // for buy/sell and transfer out transactions, the units are
                // maintained in the portfolio transaction, not the account
                // transaction.

                if (transaction.getUnits().findAny().isPresent())
                    errors.add(new Status(Status.Code.ERROR, MessageFormat
                                    .format(Messages.MsgCheckTransactionMustNotHaveGrossAmount, transaction.getType()),
                                    "units-not-allowed", "units")); //$NON-NLS-1$ //$NON-NLS-2$
            }
            else
            {
                checkGrossValueAndUnitsAgainstSecurity(transaction, errors, collectAll);
            }
        }

        return List.copyOf(errors);
    }

    @Override
    public Status process(PortfolioTransaction transaction, Portfolio portfolio)
    {
        return validate(transaction, portfolio, false).stream().findFirst().orElse(Status.OK_STATUS);
    }

    public List<Status> validate(PortfolioTransaction transaction, Portfolio portfolio)
    {
        return validate(transaction, portfolio, true);
    }

    private List<Status> validate(PortfolioTransaction transaction, Portfolio portfolio, boolean collectAll)
    {
        var errors = new ArrayList<Status>();
        Security security = transaction.getSecurity();
        if (security == null)
            errors.add(new Status(Status.Code.ERROR,
                            MessageFormat.format(Messages.MsgCheckMissingSecurity, transaction.getType().toString()),
                            "instrument-required", "instrument")); //$NON-NLS-1$ //$NON-NLS-2$

        if (security != null)
            checkGrossValueAndUnitsAgainstSecurity(transaction, errors, collectAll);

        if (!collectAll && !errors.isEmpty())
            return errors;

        // Entry validation reports retained units with incompatible currencies
        // separately. They cannot participate in a monetary sum.
        boolean canSumCharges = !collectAll || transaction.getUnits()
                        .filter(u -> u.getType() == Unit.Type.TAX || u.getType() == Unit.Type.FEE)
                        .allMatch(u -> u.getAmount().getCurrencyCode().equals(transaction.getCurrencyCode()));
        if (canSumCharges && (transaction.getType() == PortfolioTransaction.Type.DELIVERY_INBOUND
                        || transaction.getType() == PortfolioTransaction.Type.BUY))
        {
            // tax + fees must be < than transaction amount
            Money taxAndFees = transaction.getUnits() //
                            .filter(u -> u.getType() == Unit.Type.TAX || u.getType() == Unit.Type.FEE) //
                            .map(Unit::getAmount) //
                            .collect(MoneyCollectors.sum(transaction.getCurrencyCode()));

            if (!transaction.getMonetaryAmount().isGreaterOrEqualTo(taxAndFees))
                errors.add(new Status(Status.Code.ERROR, MessageFormat.format(Messages.MsgCheckTaxAndFeesTooHigh,
                                Values.Money.format(transaction.getMonetaryAmount()), Values.Money.format(taxAndFees)),
                                "fees-and-taxes-exceed-amount", "units")); //$NON-NLS-1$ //$NON-NLS-2$
        }

        return List.copyOf(errors);
    }

    @Override
    public Status process(BuySellEntry entry, Account account, Portfolio portfolio)
    {
        AccountTransaction t = entry.getAccountTransaction();
        Status status = process(t, account);
        if (status.getCode() != Status.Code.OK)
            return status;
        return process(entry.getPortfolioTransaction(), portfolio);
    }

    @Override
    public Status process(AccountTransferEntry entry, Account source, Account target)
    {
        AccountTransaction t = entry.getSourceTransaction();
        Status status = process(t, source);
        if (status.getCode() != Status.Code.OK)
            return status;
        return process(entry.getTargetTransaction(), target);
    }

    @Override
    public Status process(PortfolioTransferEntry entry, Portfolio source, Portfolio target)
    {
        PortfolioTransaction t = entry.getSourceTransaction();
        Status status = process(t, source);
        if (status.getCode() != Status.Code.OK)
            return status;
        return process(entry.getTargetTransaction(), target);
    }

    private void checkGrossValueAndUnitsAgainstSecurity(Transaction transaction, List<Status> errors, boolean collectAll)
    {
        String securityCurrency = transaction.getSecurity().getCurrencyCode();
        if (securityCurrency == null)
        {
            errors.add(new Status(Status.Code.ERROR, Messages.MsgCheckSecurityWithoutCurrency,
                            "instrument-currency-required", "instrument")); //$NON-NLS-1$ //$NON-NLS-2$
            return;
        }

        if (securityCurrency.equals(transaction.getCurrencyCode()))
        {
            // then gross value unit must not be set
            Optional<Unit> grossValue = transaction.getUnit(Transaction.Unit.Type.GROSS_VALUE);
            if (grossValue.isPresent())
            {
                String grossValueCurrencyCode = grossValue.get().getForex() != null
                                ? grossValue.get().getForex().getCurrencyCode() : ""; //$NON-NLS-1$
                errors.add(new Status(Status.Code.ERROR, MessageFormat.format(Messages.MsgCheckGrossValueUnitNotValid,
                                grossValueCurrencyCode, securityCurrency),
                                "gross-value-not-allowed", "units")); //$NON-NLS-1$ //$NON-NLS-2$
            }

            if (!collectAll && !errors.isEmpty())
                return;

            // then other units must not have any forex information
            Optional<Unit> unit = transaction.getUnits().filter(u -> u.getForex() != null).findAny();
            if (unit.isPresent())
                errors.add(new Status(Status.Code.ERROR, MessageFormat.format(Messages.MsgCheckUnitForexNotValid,
                                Values.Money.format(unit.get().getForex())),
                                "forex-not-allowed", "units")); //$NON-NLS-1$ //$NON-NLS-2$
        }
        else
        {
            // then gross value must be set
            Optional<Unit> grossValue = transaction.getUnit(Transaction.Unit.Type.GROSS_VALUE);
            if (!grossValue.isPresent())
            {
                errors.add(new Status(Status.Code.ERROR, MessageFormat.format(Messages.MsgCheckGrossValueUnitMissing,
                                transaction.getCurrencyCode(), securityCurrency),
                                "gross-value-required", "units")); //$NON-NLS-1$ //$NON-NLS-2$
            }
            else
            {

                // then gross value forex must match security
                String forex = grossValue.get().getForex() != null ? grossValue.get().getForex().getCurrencyCode() : null;
                if (!securityCurrency.equals(forex))
                    errors.add(new Status(Status.Code.ERROR, MessageFormat.format(Messages.MsgCheckGrossValueUnitForexMismatch,
                                    forex, securityCurrency), "forex-currency-mismatch", "units")); //$NON-NLS-1$ //$NON-NLS-2$
            }

            if (!collectAll && !errors.isEmpty())
                return;

            // then other units must have matching currency (if they have forex)
            Optional<Unit> unit = transaction.getUnits() //
                            .filter(u -> u.getForex() != null) //
                            .filter(u -> !u.getForex().getCurrencyCode().equals(securityCurrency)) //
                            .findAny();
            if (unit.isPresent())
                errors.add(new Status(Status.Code.ERROR, MessageFormat.format(Messages.MsgCheckUnitForexMismatch,
                                Values.Money.format(unit.get().getForex()), securityCurrency),
                                "forex-currency-mismatch", "units")); //$NON-NLS-1$ //$NON-NLS-2$
        }

    }

}
