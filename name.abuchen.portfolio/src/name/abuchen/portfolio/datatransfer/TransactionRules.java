package name.abuchen.portfolio.datatransfer;

import java.util.ArrayList;
import java.util.List;

import name.abuchen.portfolio.datatransfer.actions.CheckCurrenciesAction;
import name.abuchen.portfolio.datatransfer.actions.CheckForexGrossValueAction;
import name.abuchen.portfolio.datatransfer.actions.CheckSecurityRelatedValuesAction;
import name.abuchen.portfolio.datatransfer.actions.CheckTransactionDateAction;
import name.abuchen.portfolio.datatransfer.actions.CheckValidTypesAction;
import name.abuchen.portfolio.datatransfer.actions.DetectDuplicatesAction;
import name.abuchen.portfolio.model.Client;

public final class TransactionRules
{
    private TransactionRules()
    {
    }

    /** Dialog semantics for unattached transactions, separate from import acceptance. */
    public static TransactionEntryRules entryProfile()
    {
        return new TransactionEntryRules();
    }

    /** Validity checks using the importer's acceptance and rounding rules. */
    public static List<ImportAction> importProfile()
    {
        return importProfile(List.of());
    }

    /**
     * Import policy is separate from validity. Preserve its historical position
     * in the pipeline because the review page displays statuses in this order.
     * Duplicate detection must be fresh for each review pass.
     */
    public static List<ImportAction> importPipeline(Client client)
    {
        return importProfile(List.of(new DetectDuplicatesAction(client)));
    }

    private static List<ImportAction> importProfile(List<ImportAction> policies)
    {
        var actions = new ArrayList<ImportAction>();
        actions.add(new CheckTransactionDateAction());
        actions.add(new CheckValidTypesAction());
        actions.add(new CheckSecurityRelatedValuesAction());
        actions.addAll(policies);
        actions.add(new CheckCurrenciesAction());
        actions.add(new CheckForexGrossValueAction());
        return List.copyOf(actions);
    }
}
