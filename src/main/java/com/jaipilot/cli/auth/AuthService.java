package com.jaipilot.cli.auth;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.awt.Desktop;
import java.awt.GraphicsEnvironment;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PrintWriter;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicReference;

public final class AuthService {

    static final long REFRESH_SKEW_SECONDS = 60L;

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private static final String CALLBACK_PATH = "/auth/callback";
    private static final String LOGIN_PATH = "/plugin-login";
    private static final String REFRESH_PATH = "/plugin-refresh";
    private static final String LOGIN_SUCCESS_TEMPLATE_RESOURCE = "/templates/jaipilot-login-success.html";
    private static final String LOGIN_SUCCESS_EMAIL_PLACEHOLDER = "{{SIGNED_IN_EMAIL}}";
    private static final String LOGIN_SUCCESS_HISTORY_PLACEHOLDER = "{{HISTORY_CLEANUP_SCRIPT}}";
    private static final String HTML_CONTENT_TYPE = "text/html; charset=utf-8";
    private static final String JSON_CONTENT_TYPE = "application/json";
    private static final String CACHE_CONTROL_NO_STORE = "no-store";
    private static final String CALLBACK_METHODS = "GET, POST";
    private static final String HISTORY_CLEANUP_SCRIPT = """
            <script>
              if (window.history && window.history.replaceState) {
                window.history.replaceState({}, document.title, window.location.pathname);
              }
            </script>
            """;
    private static final String FRAGMENT_BRIDGE_SCRIPT = """
            <script>
              (function() {
                var hash = window.location.hash ? window.location.hash.substring(1) : "";
                if (!hash) {
                  return;
                }

                var payload = {};
                new URLSearchParams(hash).forEach(function(value, key) {
                  payload[key] = value;
                });

                fetch(window.location.pathname, {
                  method: "POST",
                  headers: {"Content-Type": "application/json"},
                  body: JSON.stringify(payload)
                }).then(function(response) {
                  return response.text();
                }).then(function(html) {
                  if (window.history && window.history.replaceState) {
                    window.history.replaceState({}, document.title, window.location.pathname);
                  }
                  document.open();
                  document.write(html);
                  document.close();
                }).catch(function() {
                  var status = document.getElementById("status");
                  if (status) {
                    status.innerHTML = "<h2>JAIPilot login failed</h2><p>Return to your terminal for details.</p>";
                  }
                });
              })();
            </script>
            """;

    private final CredentialsStore credentialsStore;
    private final HttpClient httpClient;
    private final String websiteBase;

    public AuthService(CredentialsStore credentialsStore) {
        this(
                credentialsStore,
                HttpClient.newBuilder()
                        .connectTimeout(Duration.ofSeconds(20))
                        .build(),
                resolveWebsiteBase()
        );
    }

    AuthService(CredentialsStore credentialsStore, HttpClient httpClient, String websiteBase) {
        this.credentialsStore = credentialsStore;
        this.httpClient = httpClient;
        this.websiteBase = trimTrailingSlash(websiteBase);
    }

    public TokenInfo startLogin(Duration timeout, PrintWriter out, PrintWriter err) {
        AtomicReference<TokenInfo> tokenReference = new AtomicReference<>();
        AtomicReference<String> errorReference = new AtomicReference<>();
        CountDownLatch latch = new CountDownLatch(1);

        try {
            HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            String state = UUID.randomUUID().toString();
            int port = server.getAddress().getPort();
            String redirectUri = "http://127.0.0.1:" + port + CALLBACK_PATH;
            String loginUrl = buildLoginUrl(redirectUri, state);

            server.createContext(CALLBACK_PATH, exchange -> {
                boolean callbackCompleted = false;
                try {
                    callbackCompleted = handleLoginCallback(exchange, state, tokenReference, errorReference);
                } catch (Exception exception) {
                    errorReference.compareAndSet(null, "Failed to process the login callback.");
                    if (!exchange.getResponseHeaders().containsKey("Content-Type")) {
                        writeHtml(exchange, 500, errorHtml("JAIPilot login failed. Callback handling error."));
                    }
                    callbackCompleted = true;
                } finally {
                    exchange.close();
                    if (callbackCompleted) {
                        latch.countDown();
                        server.stop(0);
                    }
                }
            });
            server.start();

            out.println("Opening browser for JAIPilot login...");
            out.println("If the browser does not open, visit:");
            out.println(loginUrl);
            out.flush();
            openBrowser(loginUrl);

            boolean completed = latch.await(timeout.toMillis(), java.util.concurrent.TimeUnit.MILLISECONDS);
            if (!completed) {
                server.stop(0);
                throw new IllegalStateException("Login timed out after " + timeout.getSeconds() + " seconds.");
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Login was interrupted.", exception);
        } catch (IOException exception) {
            throw new IllegalStateException("Failed to start login callback server.", exception);
        }

        if (errorReference.get() != null) {
            err.println("ERROR: " + errorReference.get());
            err.flush();
            throw new IllegalStateException(errorReference.get());
        }

        TokenInfo tokenInfo = tokenReference.get();
        if (tokenInfo == null || tokenInfo.accessToken() == null || tokenInfo.accessToken().isBlank()) {
            throw new IllegalStateException("Login did not return usable credentials.");
        }
        return tokenInfo;
    }

    public String ensureFreshAccessToken() {
        TokenInfo tokenInfo = credentialsStore.load();
        if (tokenInfo == null || tokenInfo.accessToken() == null || tokenInfo.accessToken().isBlank()) {
            return null;
        }
        if (!tokenInfo.isExpired(REFRESH_SKEW_SECONDS)) {
            return tokenInfo.accessToken();
        }

        TokenInfo refreshedToken = refresh(tokenInfo);
        if (refreshedToken == null) {
            return null;
        }
        credentialsStore.save(refreshedToken);
        return refreshedToken.accessToken();
    }

    private boolean handleLoginCallback(
            HttpExchange exchange,
            String expectedState,
            AtomicReference<TokenInfo> tokenReference,
            AtomicReference<String> errorReference
    ) throws IOException {
        String requestMethod = exchange.getRequestMethod();
        if ("GET".equalsIgnoreCase(requestMethod)) {
            return handleLoginCallbackGet(exchange, expectedState, tokenReference, errorReference);
        }
        if ("POST".equalsIgnoreCase(requestMethod)) {
            return handleLoginCallbackPost(exchange, expectedState, tokenReference, errorReference);
        }

        exchange.getResponseHeaders().add("Allow", CALLBACK_METHODS);
        exchange.sendResponseHeaders(405, -1);
        return false;
    }

    private boolean handleLoginCallbackGet(
            HttpExchange exchange,
            String expectedState,
            AtomicReference<TokenInfo> tokenReference,
            AtomicReference<String> errorReference
    ) throws IOException {
        Map<String, String> params = splitQuery(exchange.getRequestURI().getRawQuery());
        if (params.isEmpty()) {
            writeHtml(exchange, 200, fragmentBridgeHtml());
            return false;
        }
        return completeLogin(exchange, expectedState, params, tokenReference, errorReference);
    }

    private boolean handleLoginCallbackPost(
            HttpExchange exchange,
            String expectedState,
            AtomicReference<TokenInfo> tokenReference,
            AtomicReference<String> errorReference
    ) throws IOException {
        Map<String, String> params = readPostedParams(exchange);
        return completeLogin(exchange, expectedState, params, tokenReference, errorReference);
    }

    private boolean completeLogin(
            HttpExchange exchange,
            String expectedState,
            Map<String, String> params,
            AtomicReference<TokenInfo> tokenReference,
            AtomicReference<String> errorReference
    ) throws IOException {
        if (!expectedState.equals(params.get("state"))) {
            errorReference.set("Login callback state mismatch.");
            writeHtml(exchange, 400, errorHtml("JAIPilot login failed. State mismatch."));
            return true;
        }

        String accessToken = params.getOrDefault("access_token", "");
        String refreshToken = params.getOrDefault("refresh_token", "");
        if (accessToken.isBlank() || refreshToken.isBlank()) {
            errorReference.set("Login callback did not include both access and refresh tokens.");
            writeHtml(exchange, 400, errorHtml("JAIPilot login failed. Missing token data."));
            return true;
        }

        TokenInfo tokenInfo = tokenInfoFrom(params);
        credentialsStore.save(tokenInfo);
        tokenReference.set(tokenInfo);
        writeHtml(exchange, 200, successHtml(tokenInfo.email()));
        return true;
    }

    private TokenInfo refresh(TokenInfo tokenInfo) {
        try {
            String requestBody = OBJECT_MAPPER.writeValueAsString(Map.of("refresh_token", tokenInfo.refreshToken()));
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(websiteBase + REFRESH_PATH))
                    .timeout(Duration.ofSeconds(20))
                    .header("Accept", JSON_CONTENT_TYPE)
                    .header("Content-Type", JSON_CONTENT_TYPE)
                    .POST(HttpRequest.BodyPublishers.ofString(requestBody, StandardCharsets.UTF_8))
                    .build();
            HttpResponse<String> response = httpClient.send(
                    request,
                    HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8)
            );
            if (response.statusCode() / 100 != 2) {
                return null;
            }

            JsonNode json = OBJECT_MAPPER.readTree(response.body());
            String accessToken = json.path("access_token").asText("");
            if (accessToken.isBlank()) {
                return null;
            }

            String refreshToken = json.path("refresh_token").asText(tokenInfo.refreshToken());
            long expiresAt = json.path("expires_at").asLong(tokenInfo.expiresAtEpochSeconds());
            String email = json.path("email").asText(tokenInfo.email());
            return new TokenInfo(accessToken, refreshToken, expiresAt, email);
        } catch (IOException | InterruptedException exception) {
            if (exception instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            return null;
        }
    }

    private void writeHtml(HttpExchange exchange, int statusCode, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", HTML_CONTENT_TYPE);
        exchange.getResponseHeaders().add("Cache-Control", CACHE_CONTROL_NO_STORE);
        exchange.sendResponseHeaders(statusCode, bytes.length);
        try (OutputStream outputStream = exchange.getResponseBody()) {
            outputStream.write(bytes);
        }
    }

    private Map<String, String> readPostedParams(HttpExchange exchange) throws IOException {
        String requestBody;
        try (InputStream inputStream = exchange.getRequestBody()) {
            requestBody = new String(inputStream.readAllBytes(), StandardCharsets.UTF_8);
        }
        if (requestBody.isBlank()) {
            return Map.of();
        }

        String contentType = exchange.getRequestHeaders().getFirst("Content-Type");
        if (contentType != null && contentType.toLowerCase(Locale.ROOT).contains(JSON_CONTENT_TYPE)) {
            JsonNode json = OBJECT_MAPPER.readTree(requestBody);
            Map<String, String> values = new LinkedHashMap<>();
            json.fields().forEachRemaining(entry -> values.put(entry.getKey(), entry.getValue().asText("")));
            return values;
        }
        return splitQuery(requestBody);
    }

    private String buildLoginUrl(String redirectUri, String state) {
        return websiteBase + LOGIN_PATH
                + "?redirect_uri=" + URLEncoder.encode(redirectUri, StandardCharsets.UTF_8)
                + "&state=" + URLEncoder.encode(state, StandardCharsets.UTF_8);
    }

    private TokenInfo tokenInfoFrom(Map<String, String> params) {
        return new TokenInfo(
                params.getOrDefault("access_token", ""),
                params.getOrDefault("refresh_token", ""),
                parseLong(params.get("expires_at"), 0L),
                params.getOrDefault("email", "")
        );
    }

    private static Map<String, String> splitQuery(String query) {
        Map<String, String> values = new LinkedHashMap<>();
        if (query == null || query.isBlank()) {
            return values;
        }
        for (String pair : query.split("&")) {
            int equalsIndex = pair.indexOf('=');
            if (equalsIndex < 0) {
                continue;
            }
            String key = urlDecode(pair.substring(0, equalsIndex));
            String value = urlDecode(pair.substring(equalsIndex + 1));
            values.put(key, value);
        }
        return values;
    }

    private static String urlDecode(String value) {
        return URLDecoder.decode(value, StandardCharsets.UTF_8);
    }

    private static long parseLong(String value, long defaultValue) {
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException exception) {
            return defaultValue;
        }
    }

    private static void openBrowser(String url) {
        try {
            if (!GraphicsEnvironment.isHeadless() && Desktop.isDesktopSupported()) {
                Desktop.getDesktop().browse(URI.create(url));
            }
        } catch (Exception ignored) {
            // The URL is also printed to the terminal for manual login.
        }
    }

    private static String successHtml(String email) {
        return renderLoginSuccessHtml(email);
    }

    private static String errorHtml(String message) {
        return htmlPage(
                "JAIPilot Login Error",
                """
                %s
                <h2>JAIPilot login failed</h2>
                <p>%s</p>
                <p>Return to your terminal for details.</p>
                """.formatted(HISTORY_CLEANUP_SCRIPT, escapeHtml(message))
        );
    }

    private static String fragmentBridgeHtml() {
        return htmlPage(
                "JAIPilot Connecting",
                """
                <div id="status">
                  <h2>Finishing JAIPilot login...</h2>
                  <p>Waiting for token data from the browser redirect.</p>
                </div>
                %s
                """.formatted(FRAGMENT_BRIDGE_SCRIPT)
        );
    }

    private static String renderLoginSuccessHtml(String email) {
        String safeEmail = escapeHtml(email == null ? "" : email);
        String template = defaultLoginSuccessTemplate();
        try (InputStream inputStream = AuthService.class.getResourceAsStream(LOGIN_SUCCESS_TEMPLATE_RESOURCE)) {
            if (inputStream != null) {
                template = new String(inputStream.readAllBytes(), StandardCharsets.UTF_8);
            }
        } catch (IOException ignored) {
            // Fall back to the built-in template if the resource cannot be loaded.
        }
        return template
                .replace(LOGIN_SUCCESS_HISTORY_PLACEHOLDER, HISTORY_CLEANUP_SCRIPT)
                .replace(LOGIN_SUCCESS_EMAIL_PLACEHOLDER, safeEmail);
    }

    private static String defaultLoginSuccessTemplate() {
        return """
                <!doctype html>
                <html lang="en">
                <head>
                  <meta charset="utf-8" />
                  <meta name="viewport" content="width=device-width, initial-scale=1" />
                  <title>JAIPilot Connected</title>
                  <style>
                    :root {
                      --color-primary: #6366F1;
                      --color-accent: #8B5CF6;
                      --color-base-100: #09090B;
                      --color-base-200: #18181B;
                      --color-base-300: #27272A;
                      --color-base-content: #FAFAFA;
                      --color-success: #10B981;
                      --color-muted: #A1A1AA;
                    }
                    * { box-sizing: border-box; }
                    html, body { height: 100%; }
                    body {
                      margin: 0;
                      color: var(--color-base-content);
                      font-family: Inter, "Avenir Next", "Segoe UI", system-ui, -apple-system, sans-serif;
                      background:
                        radial-gradient(60rem 32rem at 5% -5%, color-mix(in srgb, var(--color-primary) 30%, transparent), transparent 65%),
                        radial-gradient(52rem 28rem at 100% 100%, color-mix(in srgb, var(--color-accent) 26%, transparent), transparent 70%),
                        linear-gradient(180deg, var(--color-base-100), var(--color-base-200));
                      display: grid;
                      place-items: center;
                      padding: 24px;
                    }
                    .panel {
                      width: min(860px, 100%);
                      border: 1px solid color-mix(in srgb, var(--color-base-300) 65%, white 10%);
                      border-radius: 24px;
                      background: color-mix(in srgb, var(--color-base-100) 82%, #1f2937 18%);
                      box-shadow: 0 28px 70px rgba(0, 0, 0, 0.45);
                      overflow: hidden;
                    }
                    .shell {
                      position: relative;
                      display: grid;
                      gap: 16px;
                      padding: 34px;
                      background:
                        linear-gradient(145deg, rgba(99, 102, 241, 0.08), rgba(139, 92, 246, 0.05)),
                        rgba(9, 9, 11, 0.84);
                      backdrop-filter: blur(6px);
                    }
                    .badge {
                      width: fit-content;
                      display: inline-flex;
                      align-items: center;
                      gap: 8px;
                      border-radius: 999px;
                      padding: 7px 13px;
                      font-size: 12px;
                      font-weight: 600;
                      letter-spacing: 0.04em;
                      text-transform: uppercase;
                      color: #D1FAE5;
                      border: 1px solid rgba(16, 185, 129, 0.35);
                      background: rgba(16, 185, 129, 0.14);
                    }
                    .badge::before {
                      content: "";
                      width: 8px;
                      height: 8px;
                      border-radius: 50%;
                      background: var(--color-success);
                      box-shadow: 0 0 0 4px rgba(16, 185, 129, 0.22);
                    }
                    h1 {
                      margin: 4px 0 0;
                      font-size: clamp(30px, 4vw, 44px);
                      letter-spacing: -0.03em;
                      line-height: 1.08;
                    }
                    p {
                      margin: 0;
                      color: #D4D4D8;
                      font-size: 16px;
                      line-height: 1.65;
                      max-width: 62ch;
                    }
                    .meta {
                      margin-top: 6px;
                      border: 1px solid rgba(63, 63, 70, 0.95);
                      border-radius: 14px;
                      background: rgba(24, 24, 27, 0.82);
                      padding: 14px 16px;
                    }
                    .meta-label {
                      color: var(--color-muted);
                      font-size: 12px;
                      margin-bottom: 4px;
                      text-transform: uppercase;
                      letter-spacing: 0.08em;
                    }
                    .meta-value {
                      color: var(--color-base-content);
                      font-weight: 600;
                      font-size: 15px;
                      word-break: break-word;
                    }
                    .hint {
                      font-size: 13px;
                      color: var(--color-muted);
                    }
                    @media (max-width: 640px) {
                      .shell { padding: 24px 20px; }
                      h1 { font-size: clamp(26px, 7vw, 34px); }
                      p { font-size: 15px; }
                    }
                  </style>
                </head>
                <body>
                  {{HISTORY_CLEANUP_SCRIPT}}
                  <main class="panel" role="main" aria-label="JAIPilot CLI Login Success">
                    <section class="shell">
                      <span class="badge">CLI sign in complete</span>
                      <h1>JAIPilot is connected to the CLI</h1>
                      <p>
                        Sign in is complete. You can now return to your terminal and continue using JAIPilot from the command line.
                      </p>

                      <div class="meta">
                        <div class="meta-label">Signed in as</div>
                        <div class="meta-value">%s</div>
                      </div>

                      <p class="hint">You can now safely close this tab and switch back to your terminal</p>
                    </section>
                  </main>
                </body>
                </html>
                """;
    }

    private static String htmlPage(String title, String body) {
        return """
                <!doctype html>
                <html lang="en">
                <head>
                  <meta charset="utf-8">
                  <meta name="viewport" content="width=device-width, initial-scale=1" />
                  <title>%s</title>
                </head>
                <body style="font-family: 'Segoe UI', sans-serif; padding: 24px;">
                  %s
                </body>
                </html>
                """.formatted(escapeHtml(title), body);
    }

    private static String escapeHtml(String value) {
        return value
                .replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;")
                .replace("'", "&#39;");
    }

    private static String resolveWebsiteBase() {
        String override = System.getenv("JAIPILOT_WEBSITE_BASE");
        if (override == null || override.isBlank()) {
            override = System.getProperty("jaipilot.website.base");
        }
        if (override != null && !override.isBlank()) {
            return override;
        }
        return "https://www.jaipilot.com";
    }

    private static String trimTrailingSlash(String value) {
        if (value.endsWith("/")) {
            return value.substring(0, value.length() - 1);
        }
        return value;
    }
}
