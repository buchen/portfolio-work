package name.abuchen.portfolio.rest.internal;

import java.text.MessageFormat;
import java.util.List;

import name.abuchen.portfolio.PortfolioLog;
import name.abuchen.portfolio.rest.Messages;

/** Identifies API transaction changes in the application's log without copying request data. */
public final class TransactionChangeLog
{
    private TransactionChangeLog()
    {
    }

    public static void recordCreation(String fileLabel, String uuid)
    {
        PortfolioLog.info(MessageFormat.format(Messages.MsgApiTransactionCreated, uuid, fileLabel), List.of());
    }

    public static void recordDeletion(String fileLabel, String uuid)
    {
        PortfolioLog.info(MessageFormat.format(Messages.MsgApiTransactionDeleted, uuid, fileLabel), List.of());
    }

    public static void recordChange(String fileLabel, String uuid)
    {
        PortfolioLog.info(MessageFormat.format(Messages.MsgApiTransactionChanged, uuid, fileLabel), List.of());
    }
}
