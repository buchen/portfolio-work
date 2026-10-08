package name.abuchen.portfolio.rest.internal;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import name.abuchen.portfolio.datatransfer.TransactionEditor;
import name.abuchen.portfolio.datatransfer.actions.InsertAction;

import name.abuchen.portfolio.model.Account;
import name.abuchen.portfolio.model.AccountTransaction;
import name.abuchen.portfolio.model.AccountTransferEntry;
import name.abuchen.portfolio.model.BuySellEntry;
import name.abuchen.portfolio.model.Client;
import name.abuchen.portfolio.model.Portfolio;
import name.abuchen.portfolio.model.PortfolioTransaction;
import name.abuchen.portfolio.model.PortfolioTransferEntry;
import name.abuchen.portfolio.model.Security;
import name.abuchen.portfolio.model.Transaction;
import name.abuchen.portfolio.model.TransactionOwner;

/** Stored records folded into the events that the transaction dialogs edit. */
public final class TransactionsHandler
{
    private TransactionsHandler()
    {
    }

    /* package */ record Event(TransactionOwner<?> owner, Transaction transaction)
    {
        String type()
        {
            return TransactionType.of(transaction).wireName;
        }

        boolean unlinked()
        {
            return transaction.getCrossEntry() == null && switch (TransactionType.of(transaction).family)
            {
                case TRADE, CASH_TRANSFER, SECURITY_TRANSFER -> true;
                default -> false;
            };
        }

        boolean involves(Object entity)
        {
            if (entity == null || entity == owner || entity == transaction.getSecurity())
                return true;
            var cross = transaction.getCrossEntry();
            return cross != null && cross.getCrossOwner(transaction) == entity;
        }

        boolean hasUuid(String uuid)
        {
            return transaction.getUUID().equals(uuid) || transaction.getCrossEntry() != null
                            && transaction.getCrossEntry().getCrossTransaction(transaction).getUUID().equals(uuid);
        }
    }

    private static List<Event> collect(Client client)
    {
        var events = new LinkedHashMap<String, Event>();
        events(client).forEach(event -> events.putIfAbsent(event.transaction().getUUID(), event));
        return new ArrayList<>(events.values());
    }

    private static Stream<Event> events(Client client)
    {
        // Client.getAllTransactions excludes cash buy/sell and incoming transfer
        // records by type, even when no counterpart exists to represent them.
        return Stream.<TransactionOwner<?>>concat(client.getPortfolios().stream(), client.getAccounts().stream())
                        .flatMap(owner -> owner.getTransactions().stream().map(transaction -> event(owner, transaction)));
    }

    private static Event find(Client client, String uuid)
    {
        return events(client).filter(event -> event.hasUuid(uuid)).findFirst()
                        .orElseThrow(() -> ApiException.notFound());
    }

    private static Event event(TransactionOwner<?> owner, Transaction transaction)
    {
        var cross = transaction.getCrossEntry();
        if (cross instanceof BuySellEntry entry)
            transaction = entry.getPortfolioTransaction();
        else if (cross instanceof AccountTransferEntry entry)
            transaction = entry.getSourceTransaction();
        else if (cross instanceof PortfolioTransferEntry entry)
            transaction = entry.getSourceTransaction();
        if (cross != null)
            owner = cross.getOwner(transaction);
        return new Event(owner, transaction);
    }

    public static JsonElement get(Client client, String uuid)
    {
        return EntityJson.toJson(find(client, uuid));
    }

    public static JsonObject create(Client client, JsonObject body, String clientName)
    {
        var event = new TransactionInput().parse(client, body, clientName);
        var insert = new InsertAction(client);
        if (event.transaction().getCrossEntry() instanceof BuySellEntry entry)
            insert.process(entry, entry.getAccount(), entry.getPortfolio());
        else if (event.transaction().getCrossEntry() instanceof AccountTransferEntry entry)
            insert.process(entry, entry.getSourceAccount(), entry.getTargetAccount());
        else if (event.transaction().getCrossEntry() instanceof PortfolioTransferEntry entry)
            insert.process(entry, entry.getSourcePortfolio(), entry.getTargetPortfolio());
        else if (event.transaction() instanceof PortfolioTransaction transaction)
            insert.process(transaction, (Portfolio) event.owner());
        else
            insert.process((AccountTransaction) event.transaction(), (Account) event.owner());
        client.markDirty();
        return EntityJson.toJson(event).getAsJsonObject();
    }

    public static String delete(Client client, String uuid)
    {
        var event = find(client, uuid);
        var transaction = event.transaction();
        var counterpart = transaction.getCrossEntry() == null ? null
                        : transaction.getCrossEntry().getCrossTransaction(transaction);

        // Deletion also removes plan membership, which the event representation
        // does not expose. Require that association to be handled in the UI.
        if (client.getPlans().stream().anyMatch(plan -> plan.getTransactions().contains(transaction)
                        || counterpart != null && plan.getTransactions().contains(counterpart)))
            throw ApiException.conflict("delete-blocked", "Transaction belongs to an investment plan", null, //$NON-NLS-1$ //$NON-NLS-2$
                            List.of(new ApiException.FieldError("plans", "referenced", //$NON-NLS-1$ //$NON-NLS-2$
                                            "transaction is used by an investment plan"))); //$NON-NLS-1$

        if (transaction instanceof PortfolioTransaction investment)
            ((Portfolio) event.owner()).deleteTransaction(investment, client);
        else
            ((Account) event.owner()).deleteTransaction((AccountTransaction) transaction, client);
        client.markDirty();
        return transaction.getUUID();
    }

    public record PatchResult(JsonObject entity, boolean changed)
    {
    }

    public static PatchResult patch(Client client, String uuid, JsonObject body)
    {
        var event = find(client, uuid);
        if (event.unlinked())
            throw ApiException.conflict("incomplete-event", "Transaction is missing its counterpart", null, List.of()); //$NON-NLS-1$ //$NON-NLS-2$
        var target = new TransactionInput().patch(client, event, body);
        var result = TransactionEditor.apply(event.owner(), event.transaction(), target.owner(), target.transaction());
        if (!result.errors().isEmpty())
            throw ApiException.validation(result.errors().stream().map(status -> new ApiException.FieldError(
                            status.getField(), status.getRuleCode(), "transaction violates " + status.getRuleCode())).toList()); //$NON-NLS-1$
        if (result.changed())
            client.markDirty();
        return new PatchResult(EntityJson.toJson(event), result.changed());
    }

    public static JsonElement list(Client client, String type, String from, String to, String instrument,
                    String cashAccount, String investmentAccount)
    {
        var errors = new ArrayList<ApiException.FieldError>();
        Set<String> types = new HashSet<>();
        if (type != null)
            for (var value : type.split(",", -1)) //$NON-NLS-1$
            {
                if (TransactionType.fromWire(value) == null)
                    errors.add(new ApiException.FieldError("type", "invalid-value", "unknown transaction type: " + value)); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                else
                    types.add(value);
            }
        LocalDate start = from == null ? null : CalcParams.date("from", from, errors); //$NON-NLS-1$
        LocalDate end = to == null ? null : CalcParams.date("to", to, errors); //$NON-NLS-1$
        if (start != null && end != null && start.isAfter(end))
            errors.add(new ApiException.FieldError("to", "invalid-range", "to must be on or after from")); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        var security = resolve(client.getSecurities(), Security::getUUID, instrument, "instrument", errors); //$NON-NLS-1$
        var account = resolve(client.getAccounts(), Account::getUUID, cashAccount, "cashAccount", errors); //$NON-NLS-1$
        var portfolio = resolve(client.getPortfolios(), Portfolio::getUUID, investmentAccount, "investmentAccount", errors); //$NON-NLS-1$
        if (!errors.isEmpty())
            throw ApiException.badRequest(errors);
        var events = collect(client).stream()
                        .filter(e -> types.isEmpty() || types.contains(e.type()))
                        .filter(e -> start == null || e.transaction().getDateTime() != null
                                        && !e.transaction().getDateTime().toLocalDate().isBefore(start))
                        .filter(e -> end == null || e.transaction().getDateTime() != null
                                        && !e.transaction().getDateTime().toLocalDate().isAfter(end))
                        .filter(e -> e.involves(security) && e.involves(account) && e.involves(portfolio))
                        .sorted(Comparator.comparing((Event e) -> e.transaction().getDateTime(),
                                        Comparator.nullsLast(Comparator.naturalOrder()))
                                        .thenComparing(e -> e.transaction().getUUID())).toList();
        return EntityJson.envelope(events, EntityJson::toJson);
    }

    private static <T> T resolve(List<T> entities, java.util.function.Function<T, String> uuid, String value,
                    String field, List<ApiException.FieldError> errors)
    {
        if (value == null)
            return null;
        var entity = entities.stream().filter(e -> uuid.apply(e).equals(value)).findFirst().orElse(null);
        if (entity == null)
            errors.add(new ApiException.FieldError(field, "unknown-entity", "unknown " + field + " uuid: " + value)); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        return entity;
    }
}
