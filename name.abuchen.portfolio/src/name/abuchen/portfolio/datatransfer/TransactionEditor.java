package name.abuchen.portfolio.datatransfer;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import name.abuchen.portfolio.datatransfer.ImportAction.Status;
import name.abuchen.portfolio.model.Account;
import name.abuchen.portfolio.model.AccountTransaction;
import name.abuchen.portfolio.model.AccountTransferEntry;
import name.abuchen.portfolio.model.BuySellEntry;
import name.abuchen.portfolio.model.Portfolio;
import name.abuchen.portfolio.model.PortfolioTransaction;
import name.abuchen.portfolio.model.PortfolioTransferEntry;
import name.abuchen.portfolio.model.Transaction;
import name.abuchen.portfolio.model.TransactionOwner;

/** Applies a validated event without replacing records or their plan associations. */
@SuppressWarnings("nls")
public final class TransactionEditor
{
    public record Result(boolean changed, List<Status> errors)
    {
    }

    private TransactionEditor()
    {
    }

    /** Copies both records for editing without attaching them to their owners. */
    public static Transaction copyOf(Transaction transaction)
    {
        Transaction target;
        var cross = transaction.getCrossEntry();
        if (cross instanceof BuySellEntry entry)
        {
            var copy = new BuySellEntry(entry.getPortfolio(), entry.getAccount());
            target = transaction == entry.getPortfolioTransaction() ? copy.getPortfolioTransaction()
                            : copy.getAccountTransaction();
        }
        else if (cross instanceof AccountTransferEntry entry)
        {
            var copy = new AccountTransferEntry(entry.getSourceAccount(), entry.getTargetAccount());
            target = transaction == entry.getSourceTransaction() ? copy.getSourceTransaction() : copy.getTargetTransaction();
        }
        else if (cross instanceof PortfolioTransferEntry entry)
        {
            var copy = new PortfolioTransferEntry(entry.getSourcePortfolio(), entry.getTargetPortfolio());
            target = transaction == entry.getSourceTransaction() ? copy.getSourceTransaction() : copy.getTargetTransaction();
        }
        else
            target = transaction instanceof AccountTransaction ? new AccountTransaction() : new PortfolioTransaction();
        copyRecord(target, transaction);
        if (cross != null)
            copyRecord(target.getCrossEntry().getCrossTransaction(target), cross.getCrossTransaction(transaction));
        return target;
    }

    private static void copyRecord(Transaction target, Transaction source)
    {
        copy(target, source);
        if (source instanceof AccountTransaction cash)
            ((AccountTransaction) target).setType(cash.getType());
        else
            ((PortfolioTransaction) target).setType(((PortfolioTransaction) source).getType());
        target.setUpdatedAt(source.getUpdatedAt());
    }

    public static List<Status> validate(TransactionOwner<?> owner, Transaction transaction)
    {
        var rules = TransactionRules.entryProfile();
        var cross = transaction.getCrossEntry();
        if (cross instanceof BuySellEntry entry)
            return rules.validate(entry, entry.getAccount(), entry.getPortfolio());
        if (cross instanceof AccountTransferEntry entry)
            return rules.validate(entry, entry.getSourceAccount(), entry.getTargetAccount());
        if (cross instanceof PortfolioTransferEntry entry)
            return rules.validate(entry, entry.getSourcePortfolio(), entry.getTargetPortfolio());
        if (transaction instanceof PortfolioTransaction investment && owner instanceof Portfolio portfolio)
            return rules.validate(investment, portfolio);
        if (transaction instanceof AccountTransaction cash && owner instanceof Account account)
            return rules.validate(cash, account);
        return List.of(immutable(ownerField(transaction)));
    }

    /**
     * The target must be an unattached, complete event. Both records are checked
     * before either is changed. Types and owners cannot change during an edit.
     */
    public static Result apply(TransactionOwner<?> owner, Transaction existing, TransactionOwner<?> targetOwner,
                    Transaction target)
    {
        var errors = new ArrayList<Status>();
        var cross = existing.getCrossEntry();
        var targetCross = target.getCrossEntry();
        if (!Objects.equals(type(existing), type(target))
                        || (cross == null ? null : cross.getClass()) != (targetCross == null ? null : targetCross.getClass()))
            errors.add(immutable("type"));
        if (owner != targetOwner || owner == null || !owner.getTransactions().contains(existing))
            errors.add(immutable(ownerField(existing)));
        if (!errors.isEmpty())
            return new Result(false, List.copyOf(errors));
        if (cross != null)
        {
            if (cross.getOwner(existing) != owner || targetCross.getOwner(target) != targetOwner)
                errors.add(immutable(ownerField(existing)));
            if (cross.getCrossOwner(existing) != targetCross.getCrossOwner(target))
                errors.add(immutable(ownerField(cross.getCrossTransaction(existing))));
            if (!Objects.equals(type(cross.getCrossTransaction(existing)), type(targetCross.getCrossTransaction(target))))
                errors.add(immutable("type"));
        }
        errors.addAll(validate(targetOwner, target));
        if (!errors.isEmpty())
            return new Result(false, List.copyOf(errors));

        boolean changed = !same(existing, target);
        if (cross != null)
            changed |= !same(cross.getCrossTransaction(existing), targetCross.getCrossTransaction(target));
        if (changed)
        {
            copy(existing, target);
            if (cross != null)
                copy(cross.getCrossTransaction(existing), targetCross.getCrossTransaction(target));
        }
        return new Result(changed, List.of());
    }

    private static Status immutable(String field)
    {
        return new Status(Status.Code.ERROR, null, "immutable-field", field);
    }

    private static Object type(Transaction transaction)
    {
        return transaction instanceof AccountTransaction cash ? cash.getType()
                        : ((PortfolioTransaction) transaction).getType();
    }

    private static String ownerField(Transaction transaction)
    {
        if (transaction.getCrossEntry() instanceof AccountTransferEntry entry)
            return transaction == entry.getSourceTransaction() ? "fromCashAccount" : "toCashAccount";
        if (transaction.getCrossEntry() instanceof PortfolioTransferEntry entry)
            return transaction == entry.getSourceTransaction() ? "fromInvestmentAccount" : "toInvestmentAccount";
        return transaction instanceof AccountTransaction ? "cashAccount" : "investmentAccount";
    }

    private static boolean same(Transaction left, Transaction right)
    {
        if (!Objects.equals(left.getDateTime(), right.getDateTime())
                        || !Objects.equals(left.getCurrencyCode(), right.getCurrencyCode())
                        || left.getAmount() != right.getAmount() || left.getSecurity() != right.getSecurity()
                        || left.getShares() != right.getShares() || !Objects.equals(left.getNote(), right.getNote())
                        || !Objects.equals(left.getSource(), right.getSource()))
            return false;
        if (left instanceof AccountTransaction cash && right instanceof AccountTransaction target
                        && !Objects.equals(cash.getExDate(), target.getExDate()))
            return false;
        var first = left.getUnits().toList();
        var second = right.getUnits().toList();
        if (first.size() != second.size())
            return false;
        for (int index = 0; index < first.size(); index++)
        {
            var a = first.get(index);
            var b = second.get(index);
            if (a.getType() != b.getType() || !a.getAmount().equals(b.getAmount())
                            || !Objects.equals(a.getForex(), b.getForex())
                            || (a.getExchangeRate() == null ? b.getExchangeRate() != null
                                            : b.getExchangeRate() == null || a.getExchangeRate().compareTo(b.getExchangeRate()) != 0))
                return false;
        }
        return true;
    }

    private static void copy(Transaction existing, Transaction target)
    {
        existing.setDateTime(target.getDateTime());
        existing.setAmount(target.getAmount());
        existing.setCurrencyCode(target.getCurrencyCode());
        existing.setSecurity(target.getSecurity());
        existing.setShares(target.getShares());
        existing.setNote(target.getNote());
        existing.setSource(target.getSource());
        existing.clearUnits();
        existing.addUnits(target.getUnits());
        if (existing instanceof AccountTransaction cash && target instanceof AccountTransaction value)
            cash.setExDate(value.getExDate());
    }
}
