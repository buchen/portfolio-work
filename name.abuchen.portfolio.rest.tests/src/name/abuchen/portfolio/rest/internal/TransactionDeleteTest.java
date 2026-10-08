package name.abuchen.portfolio.rest.internal;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.ServiceLoader;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import org.eclipse.core.runtime.ILogListener;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.Platform;
import org.eclipse.core.runtime.preferences.InstanceScope;
import org.junit.Test;
import org.osgi.framework.FrameworkUtil;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import name.abuchen.portfolio.PortfolioLog;
import name.abuchen.portfolio.checks.Check;
import name.abuchen.portfolio.model.Account;
import name.abuchen.portfolio.model.AccountTransaction;
import name.abuchen.portfolio.model.Client;
import name.abuchen.portfolio.model.InvestmentPlan;
import name.abuchen.portfolio.model.Portfolio;
import name.abuchen.portfolio.model.PortfolioTransaction;
import name.abuchen.portfolio.model.Security;
import name.abuchen.portfolio.model.Transaction;
import name.abuchen.portfolio.money.Money;
import name.abuchen.portfolio.rest.ApiRoutes;
import name.abuchen.portfolio.rest.FileAccessRegistry;
import name.abuchen.portfolio.rest.testsupport.FakeHost;

@SuppressWarnings("nls")
public class TransactionDeleteTest
{
    private static final List<String> TYPES = List.of("buy", "sell", "delivery-inbound", "delivery-outbound",
                    "cash-transfer", "security-transfer", "deposit", "removal", "interest", "interest-charge",
                    "fee", "fee-refund", "tax", "tax-refund", "dividend");

    private final Client client = new Client();
    private final Account account = new Account();
    private final Account targetAccount = new Account();
    private final Portfolio portfolio = new Portfolio();
    private final Portfolio targetPortfolio = new Portfolio();
    private final Security security = new Security();
    private final AtomicInteger dirty = new AtomicInteger();

    public TransactionDeleteTest()
    {
        for (var cash : List.of(account, targetAccount))
        {
            cash.setCurrencyCode("EUR");
            cash.setName("Cash");
            client.addAccount(cash);
        }
        for (var investment : List.of(portfolio, targetPortfolio))
        {
            investment.setReferenceAccount(account);
            investment.setName("Investments");
            client.addPortfolio(investment);
        }
        security.setName("Instrument");
        security.setCurrencyCode("EUR");
        client.addSecurity(security);
        client.addPropertyChangeListener("dirty", event -> dirty.incrementAndGet());
    }

    private void reference(JsonObject body, String field, String uuid)
    {
        var value = new JsonObject();
        value.addProperty("uuid", uuid);
        body.add(field, value);
    }

    private Transaction create(String type)
    {
        var body = JsonParser.parseString("""
                        {"dateTime":"2026-01-02T12:30:00","amount":{"value":12.34,"currency":"EUR"}}
                        """).getAsJsonObject();
        body.addProperty("type", type);
        if (type.equals("cash-transfer"))
        {
            reference(body, "fromCashAccount", account.getUUID());
            reference(body, "toCashAccount", targetAccount.getUUID());
            body.add("targetAmount", body.get("amount").deepCopy());
        }
        else if (type.equals("security-transfer"))
        {
            reference(body, "fromInvestmentAccount", portfolio.getUUID());
            reference(body, "toInvestmentAccount", targetPortfolio.getUUID());
        }
        else if (!type.startsWith("delivery"))
            reference(body, "cashAccount", account.getUUID());
        if (type.equals("buy") || type.equals("sell") || type.startsWith("delivery"))
            reference(body, "investmentAccount", portfolio.getUUID());
        if (type.equals("buy") || type.equals("sell") || type.startsWith("delivery")
                        || type.equals("security-transfer") || type.equals("dividend"))
        {
            reference(body, "instrument", security.getUUID());
            body.addProperty("shares", 1);
        }
        var uuid = TransactionsHandler.create(client, body, "Test").get("uuid").getAsString();
        return client.getAllTransactions().stream().map(pair -> (Transaction) pair.getTransaction())
                        .filter(transaction -> transaction.getUUID().equals(uuid)).findFirst().orElseThrow();
    }

    @Test
    public void deletesOnlyTheSurvivingRecordOfAnIncompleteEvent()
    {
        var retained = create("deposit");
        for (var type : List.of("BUY", "SELL", "TRANSFER_IN", "TRANSFER_OUT"))
        {
            for (var cash : List.of(false, true))
            {
                Transaction survivor;
                if (cash)
                {
                    var value = new AccountTransaction(AccountTransaction.Type.valueOf(type));
                    value.setCurrencyCode("EUR");
                    account.addTransaction(value);
                    survivor = value;
                }
                else
                {
                    var value = new PortfolioTransaction(PortfolioTransaction.Type.valueOf(type));
                    portfolio.addTransaction(value);
                    survivor = value;
                }
                survivor.setMonetaryAmount(Money.of("EUR", 1234));
                int changes = dirty.get();
                assertEquals(survivor.getUUID(), TransactionsHandler.delete(client, survivor.getUUID()));
                assertEquals(changes + 1, dirty.get());
                assertEquals(1, account.getTransactions().size());
                assertEquals(retained, account.getTransactions().get(0));
                assertTrue(portfolio.getTransactions().isEmpty());
            }
        }
    }

    @Test
    public void deletesEveryTypeThroughEitherRecordWithoutTouchingOtherEvents()
    {
        var retained = create("deposit");
        var check = ServiceLoader.load(Check.class, Check.class.getClassLoader()).stream()
                        .filter(provider -> provider.type().getName().equals("name.abuchen.portfolio.checks.impl.CrossEntryCheck"))
                        .findFirst().orElseThrow().get();
        for (var type : TYPES)
        {
            for (var alias : List.of(false, true))
            {
                var transaction = create(type);
                var cross = transaction.getCrossEntry();
                var addressed = alias && cross != null ? cross.getCrossTransaction(transaction) : transaction;
                int before = dirty.get();
                assertEquals(transaction.getUUID(), TransactionsHandler.delete(client, addressed.getUUID()));
                assertEquals(before + 1, dirty.get());
                assertEquals(1, client.getAllTransactions().size());
                assertEquals(List.of(retained), account.getTransactions());
                assertTrue(targetAccount.getTransactions().isEmpty());
                assertTrue(portfolio.getTransactions().isEmpty());
                assertTrue(targetPortfolio.getTransactions().isEmpty());
                assertEquals(retained, client.getAllTransactions().getFirst().getTransaction());
                assertEquals(404, assertThrows(ApiException.class,
                                () -> TransactionsHandler.get(client, transaction.getUUID())).getStatus());
                assertEquals(404, assertThrows(ApiException.class,
                                () -> TransactionsHandler.get(client, addressed.getUUID())).getStatus());
                assertTrue(check.execute(client).isEmpty());
            }
        }
    }

    @Test
    public void routeGuardsPlansBothHalvesAndScopeBeforeMutationAndLogsCanonicalIdentity() throws Exception
    {
        var node = InstanceScope.INSTANCE.getNode("rest-test-" + UUID.randomUUID());
        var captured = new ArrayList<IStatus>();
        ILogListener listener = (status, plugin) -> captured.add(status);
        var log = Platform.getLog(FrameworkUtil.getBundle(PortfolioLog.class));
        log.addLogListener(listener);
        try
        {
            var path = "/tmp/transaction-delete.portfolio";
            var registry = new FileAccessRegistry(node);
            registry.setEnabled(path, true);
            registry.setAlias(path, "sample");
            var host = new FakeHost(List.of(new FakeHost.FakeOpenFile(path, "Delete test", client)));
            var router = ApiRoutes.create(registry, host, null);
            var plan = new InvestmentPlan();
            client.addPlan(plan);
            for (var type : TYPES)
            {
                var transaction = create(type);
                var counterpart = transaction.getCrossEntry() == null ? transaction
                                : transaction.getCrossEntry().getCrossTransaction(transaction);
                var uri = "/v1/files/sample/transactions/" + counterpart.getUUID();
                var route = router.match("DELETE", uri);
                var request = new Request("DELETE", uri, route.pathParams(), new byte[0]);
                var snapshot = TransactionsHandler.list(client, null, null, null, null, null, null);
                int changes = dirty.get();
                int logs = captured.size();
                for (var member : List.of(transaction, counterpart))
                {
                    plan.getTransactions().add(member);
                    var error = assertThrows(ApiException.class, () -> route.handler().handle(request));
                    assertEquals(409, error.getStatus());
                    assertEquals("delete-blocked", error.getType());
                    assertEquals("plans", error.getErrors().getFirst().field());
                    assertEquals(List.of(member), plan.getTransactions());
                    assertEquals(snapshot, TransactionsHandler.list(client, null, null, null, null, null, null));
                    assertEquals(changes, dirty.get());
                    assertEquals(logs, captured.size());
                    plan.getTransactions().clear();
                }
                host.setUserEditing(true);
                assertEquals(423, assertThrows(ApiException.class, () -> route.handler().handle(request)).getStatus());
                host.setUserEditing(false);
                registry.setEnabled(path, false);
                assertEquals(404, assertThrows(ApiException.class, () -> route.handler().handle(request)).getStatus());
                registry.setEnabled(path, true);
                assertEquals(snapshot, TransactionsHandler.list(client, null, null, null, null, null, null));
                assertEquals(changes, dirty.get());
                assertEquals(logs, captured.size());

                var response = route.handler().handle(request);
                assertEquals(204, response.status());
                assertEquals(0, response.body().length);
                assertEquals(changes + 1, dirty.get());
                assertEquals(logs + 1, captured.size());
                var entry = captured.getLast();
                assertEquals(IStatus.INFO, entry.getSeverity());
                assertTrue(entry.getMessage().contains(transaction.getUUID()));
                assertTrue(entry.getMessage().contains("Delete test"));
                assertTrue(client.getAllTransactions().isEmpty());
                assertTrue(account.getTransactions().isEmpty());
                assertTrue(targetAccount.getTransactions().isEmpty());
                assertTrue(portfolio.getTransactions().isEmpty());
                assertTrue(targetPortfolio.getTransactions().isEmpty());
                assertEquals(404, assertThrows(ApiException.class, () -> route.handler().handle(request)).getStatus());
                assertEquals(changes + 1, dirty.get());
                assertEquals(logs + 1, captured.size());
            }
            assertFalse(host.hasAccessedOutsideUIThread());
        }
        finally
        {
            log.removeLogListener(listener);
            node.removeNode();
        }
    }
}
