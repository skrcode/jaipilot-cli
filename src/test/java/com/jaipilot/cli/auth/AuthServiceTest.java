package com.jaipilot.cli.auth;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

class AuthServiceTest {

    @TempDir
    Path tempDir;

    private HttpServer server;

    @AfterEach
    void tearDown() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    void ensureFreshAccessTokenReturnsStoredTokenWhenStillValid() {
        CredentialsStore credentialsStore = new CredentialsStore(tempDir.resolve("credentials.json"));
        credentialsStore.save(new TokenInfo(
                "access-token",
                "refresh-token",
                (System.currentTimeMillis() / 1_000L) + 3_600L,
                "user@example.com"
        ));

        AuthService authService = new AuthService(credentialsStore, HttpClient.newHttpClient(), "http://127.0.0.1:9");

        assertEquals("access-token", authService.ensureFreshAccessToken());
    }

    @Test
    void ensureFreshAccessTokenRefreshesExpiredCredentials() throws Exception {
        CredentialsStore credentialsStore = new CredentialsStore(tempDir.resolve("credentials.json"));
        credentialsStore.save(new TokenInfo("old-access", "refresh-token", 1L, "user@example.com"));
        server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/plugin-refresh", exchange -> {
            writeJson(
                    exchange,
                    "{\"access_token\":\"new-access\",\"refresh_token\":\"new-refresh\",\"expires_at\":9999999999,\"email\":\"fresh@example.com\"}"
            );
        });
        server.start();

        AuthService authService = new AuthService(
                credentialsStore,
                HttpClient.newHttpClient(),
                "http://127.0.0.1:" + server.getAddress().getPort()
        );

        assertEquals("new-access", authService.ensureFreshAccessToken());
        assertEquals(
                new TokenInfo("new-access", "new-refresh", 9_999_999_999L, "fresh@example.com"),
                credentialsStore.load()
        );
    }

    @Test
    void ensureFreshAccessTokenReturnsNullWhenRefreshFails() throws Exception {
        CredentialsStore credentialsStore = new CredentialsStore(tempDir.resolve("credentials.json"));
        credentialsStore.save(new TokenInfo("old-access", "refresh-token", 1L, "user@example.com"));
        server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/plugin-refresh", exchange -> {
            exchange.sendResponseHeaders(401, -1);
            exchange.close();
        });
        server.start();

        AuthService authService = new AuthService(
                credentialsStore,
                HttpClient.newHttpClient(),
                "http://127.0.0.1:" + server.getAddress().getPort()
        );

        assertNull(authService.ensureFreshAccessToken());
    }

    @Test
    void startLoginStoresCredentialsFromBrowserCallback() throws Exception {
        CredentialsStore credentialsStore = new CredentialsStore(tempDir.resolve("credentials.json"));
        server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/plugin-login", exchange -> {
            Map<String, String> params = splitQuery(exchange.getRequestURI().getRawQuery());
            String redirectUri = params.get("redirect_uri");
            String state = params.get("state");
            String location = redirectUri
                    + "?state=" + URLEncoder.encode(state, StandardCharsets.UTF_8)
                    + "&access_token=access-token"
                    + "&refresh_token=refresh-token"
                    + "&expires_at=9999999999"
                    + "&email=" + URLEncoder.encode("user@example.com", StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Location", location);
            exchange.sendResponseHeaders(302, -1);
            exchange.close();
        });
        server.start();

        AuthService authService = new AuthService(
                credentialsStore,
                HttpClient.newHttpClient(),
                "http://127.0.0.1:" + server.getAddress().getPort()
        );

        StringWriter outputBuffer = new StringWriter();
        StringWriter errorBuffer = new StringWriter();
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            Future<TokenInfo> loginFuture = executor.submit(() -> authService.startLogin(
                    Duration.ofSeconds(10),
                    new PrintWriter(outputBuffer, true),
                    new PrintWriter(errorBuffer, true)
            ));

            String loginUrl = waitForLoginUrl(outputBuffer, Duration.ofSeconds(5));
            assertNotNull(loginUrl);

            HttpResponse<String> response = HttpClient.newBuilder()
                    .followRedirects(HttpClient.Redirect.ALWAYS)
                    .build()
                    .send(
                            HttpRequest.newBuilder(URI.create(loginUrl)).GET().build(),
                            HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8)
                    );

            TokenInfo tokenInfo = loginFuture.get(10, TimeUnit.SECONDS);
            TokenInfo storedToken = credentialsStore.load();

            assertEquals(new TokenInfo("access-token", "refresh-token", 9_999_999_999L, "user@example.com"), tokenInfo);
            assertEquals(tokenInfo, storedToken);
            assertTrue(response.body().contains("JAIPilot is connected to the CLI"));
            assertTrue(response.body().contains("CLI sign in complete"));
            assertTrue(response.body().contains("Signed in as"));
            assertTrue(response.body().contains("user@example.com"));
            assertTrue(response.body().contains("switch back to your terminal"));
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void startLoginAcceptsPostedCallbackPayload() throws Exception {
        CredentialsStore credentialsStore = new CredentialsStore(tempDir.resolve("credentials.json"));
        AuthService authService = new AuthService(credentialsStore, HttpClient.newHttpClient(), "http://127.0.0.1:9");

        StringWriter outputBuffer = new StringWriter();
        StringWriter errorBuffer = new StringWriter();
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            Future<TokenInfo> loginFuture = executor.submit(() -> authService.startLogin(
                    Duration.ofSeconds(10),
                    new PrintWriter(outputBuffer, true),
                    new PrintWriter(errorBuffer, true)
            ));

            String loginUrl = waitForLoginUrl(outputBuffer, Duration.ofSeconds(5));
            assertNotNull(loginUrl);

            Map<String, String> loginParams = splitQuery(URI.create(loginUrl).getRawQuery());
            String redirectUri = loginParams.get("redirect_uri");
            String state = loginParams.get("state");
            String requestBody = """
                    {
                      "state":"%s",
                      "access_token":"posted-access",
                      "refresh_token":"posted-refresh",
                      "expires_at":"9999999999",
                      "email":"posted@example.com"
                    }
                    """.formatted(state);

            HttpClient.newHttpClient().send(
                    HttpRequest.newBuilder(URI.create(redirectUri))
                            .header("Content-Type", "application/json")
                            .POST(HttpRequest.BodyPublishers.ofString(requestBody, StandardCharsets.UTF_8))
                            .build(),
                    HttpResponse.BodyHandlers.discarding()
            );

            TokenInfo tokenInfo = loginFuture.get(10, TimeUnit.SECONDS);
            assertEquals(
                    new TokenInfo("posted-access", "posted-refresh", 9_999_999_999L, "posted@example.com"),
                    tokenInfo
            );
            assertEquals(tokenInfo, credentialsStore.load());
        } finally {
            executor.shutdownNow();
        }
    }

    private void writeJson(HttpExchange exchange, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(200, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }

    private String waitForLoginUrl(StringWriter outputBuffer, Duration timeout) throws InterruptedException {
        long deadlineNanos = System.nanoTime() + timeout.toNanos();
        while (System.nanoTime() < deadlineNanos) {
            String[] lines = outputBuffer.toString().split("\\R");
            for (String line : lines) {
                if (line.startsWith("http://") || line.startsWith("https://")) {
                    return line;
                }
            }
            Thread.sleep(25L);
        }
        return null;
    }

    private Map<String, String> splitQuery(String query) {
        Map<String, String> values = new LinkedHashMap<>();
        if (query == null || query.isBlank()) {
            return values;
        }
        for (String pair : query.split("&")) {
            int equalsIndex = pair.indexOf('=');
            if (equalsIndex < 0) {
                continue;
            }
            String key = URLDecoder.decode(pair.substring(0, equalsIndex), StandardCharsets.UTF_8);
            String value = URLDecoder.decode(pair.substring(equalsIndex + 1), StandardCharsets.UTF_8);
            values.put(key, value);
        }
        return values;
    }
}
