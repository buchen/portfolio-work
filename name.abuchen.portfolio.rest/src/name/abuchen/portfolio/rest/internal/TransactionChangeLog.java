package name.abuchen.portfolio.rest.internal;

import java.text.MessageFormat;
import java.util.List;

import name.abuchen.portfolio.PortfolioLog;
import name.abuchen.portfolio.rest.Messages;

/** Identifies API-created records in the application's log without copying request data. */
public final class TransactionChangeLog
{
    private TransactionChangeLog()
    {
    }

    public static void recordCreation(String fileLabel, String uuid)
    {
        PortfolioLog.info(MessageFormat.format(Messages.MsgApiTransactionCreated, uuid, fileLabel), List.of());
    }
}
