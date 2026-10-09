package name.abuchen.portfolio.rest.internal;

import static org.junit.Assert.assertEquals;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import org.eclipse.core.runtime.preferences.InstanceScope;
import org.junit.Test;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import name.abuchen.portfolio.model.Account;
import name.abuchen.portfolio.model.Client;
import name.abuchen.portfolio.rest.ApiRoutes;
import name.abuchen.portfolio.rest.ClientStore;
import name.abuchen.portfolio.rest.FileAccessRegistry;
import name.abuchen.portfolio.rest.PairingService;
import name.abuchen.portfolio.rest.RestApiServer;
import name.abuchen.portfolio.rest.internal.mcp.McpDispatch;
import name.abuchen.portfolio.rest.internal.mcp.McpTools;
import name.abuchen.portfolio.rest.testsupport.FakeHost;

@SuppressWarnings("nls")
public class TransactionSourceTest
{
    @Test
    public void sourceDefaultsToAuthenticatedClientOnTheWire() throws Exception
    {
        var client = new Client();
        var account = new Account();
        account.setName("Cash");
        account.setCurrencyCode("EUR");
        client.addAccount(account);

        var node = InstanceScope.INSTANCE.getNode("rest-test-" + UUID.randomUUID());
        var registry = new FileAccessRegistry(node);
        var path = "/tmp/source-test.portfolio";
        registry.setEnabled(path, true);
        registry.setAlias(path, "sample");
        var host = new FakeHost(List.of(new FakeHost.FakeOpenFile(path, "Source test", client)));
        var store = new ClientStore(Path.of("target", "source-test-" + UUID.randomUUID()));
        var token = store.addSessionClient("Paired client");
        var server = new RestApiServer(0, store::authenticate,
                        ApiRoutes.create(registry, host, new PairingService(store, host)));
        try
        {
            server.start();
            var body = JsonParser.parseString("""
                            {"type":"deposit","dateTime":"2026-01-02T12:30:00",
                             "amount":{"value":12.34,"currency":"EUR"},"cashAccount":{"uuid":"%s"}}
                            """.formatted(account.getUUID())).getAsJsonObject();
            var http = HttpClient.newHttpClient();
            var uri = URI.create("http://127.0.0.1:" + server.getPort() + "/v1/files/sample/transactions");
            var response = http.send(HttpRequest.newBuilder(uri).header("Authorization", "Bearer " + token)
                            .header("X-Client-Name", "Untrusted header").header("User-Agent", "Untrusted agent")
                            .POST(HttpRequest.BodyPublishers.ofString(body.toString())).build(),
                            HttpResponse.BodyHandlers.ofString());
            assertEquals(response.body(), 201, response.statusCode());
            assertEquals("Paired client", account.getTransactions().get(0).getSource());
            assertEquals("Paired client", JsonParser.parseString(response.body()).getAsJsonObject().get("source").getAsString());

            body.addProperty("source", "Statement import");
            var explicit = http.send(HttpRequest.newBuilder(uri).header("Authorization", "Bearer " + token)
                            .POST(HttpRequest.BodyPublishers.ofString(body.toString())).build(),
                            HttpResponse.BodyHandlers.ofString());
            assertEquals(explicit.body(), 201, explicit.statusCode());
            assertEquals("Statement import", account.getTransactions().get(1).getSource());
        }
        finally
        {
            server.stop();
            node.removeNode();
        }
    }

    @Test
    public void mcpDispatchPreservesCallerIdentity() throws Exception
    {
        var router = new Router();
        var received = new AtomicReference<Request>();
        router.add("GET", "/v1/files/{file}/instruments", request -> {
            received.set(request);
            return Response.json(200, new JsonObject());
        });
        var arguments = new JsonObject();
        arguments.addProperty("file", "sample");
        var caller = new Request("POST", "/mcp", Map.of(), Map.of(), new byte[0], Request.Authorization.VALID,
                        "Connector", "Paired client");
        McpDispatch.call(router, McpTools.byName("pp_list_instruments").orElseThrow(), arguments, caller);
        assertEquals("Paired client", received.get().clientName());
        assertEquals(caller.authorization(), received.get().authorization());
        assertEquals(caller.userAgent(), received.get().userAgent());
    }
}
