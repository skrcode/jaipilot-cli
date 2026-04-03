package com.jaipilot.cli.backend;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jaipilot.cli.http.CurlHttpClient;
import com.jaipilot.cli.model.FetchJobResponse;
import com.jaipilot.cli.model.InvokeJunitLlmRequest;
import com.jaipilot.cli.model.InvokeJunitLlmResponse;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HttpJunitLlmBackendClientTest {

    private HttpServer server;

    @AfterEach
    void tearDown() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    void invokeSendsExpectedRequest() throws Exception {
        AtomicReference<String> authorization = new AtomicReference<>();
        AtomicReference<String> accept = new AtomicReference<>();
        AtomicReference<String> contentType = new AtomicReference<>();
        AtomicReference<String> requestBody = new AtomicReference<>();

        server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/functions/v1/invoke-junit-llm-cli", exchange -> {
            authorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
            accept.set(exchange.getRequestHeaders().getFirst("Accept"));
            contentType.set(exchange.getRequestHeaders().getFirst("Content-Type"));
            requestBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            writeJson(exchange, "{\"jobId\":\"job-1\",\"sessionId\":\"session-1\"}");
        });
        server.start();

        HttpJunitLlmBackendClient client = new HttpJunitLlmBackendClient(baseUrl(), "token-123");

        InvokeJunitLlmResponse response = client.invoke(new InvokeJunitLlmRequest(
                null,
                "generate",
                "CrashController",
                "CrashControllerTest",
                "5.11.0",
                "class body",
                List.of("com/example/Helper.java =\npackage com.example;\n"),
                "",
                List.of(),
                "",
                null
        ));

        assertEquals("job-1", response.jobId());
        assertEquals("session-1", response.sessionId());
        assertEquals("Bearer token-123", authorization.get());
        assertEquals("application/json", accept.get());
        assertTrue(contentType.get().startsWith("application/json"));
        assertTrue(requestBody.get().contains("\"type\":\"generate\""));
        assertTrue(requestBody.get().contains("\"cutName\":\"CrashController\""));
        assertTrue(requestBody.get().contains("\"testClassName\":\"CrashControllerTest\""));
        assertTrue(requestBody.get().contains("\"mockitoVersion\":\"5.11.0\""));
        assertTrue(requestBody.get().contains("\"cutCode\":\"class body\""));
        assertTrue(requestBody.get().contains("\"cachedContextClasses\":[\"com/example/Helper.java =\\npackage com.example;\\n\"]"));
        assertTrue(requestBody.get().contains("\"initialTestClassCode\":\"\""));
        assertTrue(requestBody.get().contains("\"contextClasses\":[]"));
        assertTrue(requestBody.get().contains("\"newTestClassCode\":\"\""));
        assertTrue(requestBody.get().contains("\"clientLogs\":null"));
        assertFalse(requestBody.get().contains("attemptNumber"));
        assertFalse(requestBody.get().contains("\"sessionId\""));
        assertFalse(requestBody.get().contains("cut_name"));
        assertFalse(requestBody.get().contains("test_class_name"));
        assertFalse(requestBody.get().contains("cut_code"));
        assertFalse(requestBody.get().contains("initial_testclass_code"));
        assertFalse(requestBody.get().contains("context_classes"));
        assertFalse(requestBody.get().contains("new_testclass_code"));
    }

    @Test
    void fetchJobUsesExpectedQueryParameter() throws Exception {
        AtomicReference<String> query = new AtomicReference<>();

        server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/functions/v1/fetch-job-cli", exchange -> {
            query.set(exchange.getRequestURI().getQuery());
            writeJson(
                    exchange,
                    "{\"status\":\"done\",\"output\":{\"sessionId\":\"session-1\",\"finalTestFile\":\"class body\"}}"
            );
        });
        server.start();

        HttpJunitLlmBackendClient client = new HttpJunitLlmBackendClient(baseUrl(), "token-123");

        FetchJobResponse response = client.fetchJob("job 1");

        assertEquals("id=job+1", query.get());
        assertEquals("done", response.status());
        assertEquals("session-1", response.output().sessionId());
        assertEquals("class body", response.output().finalTestFile());
    }

    @Test
    void fetchJobPreservesPlainStringOutputForErrorResponses() throws Exception {
        server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/functions/v1/fetch-job-cli", exchange -> {
            writeJson(exchange, "{\"status\":\"error\",\"output\":\"backend exploded\"}");
        });
        server.start();

        HttpJunitLlmBackendClient client = new HttpJunitLlmBackendClient(baseUrl(), "token-123");

        FetchJobResponse response = client.fetchJob("job-1");

        assertEquals("error", response.status());
        assertEquals("backend exploded", response.rawOutput());
        assertEquals("backend exploded", response.errorMessage());
    }

    @Test
    void invokeRetriesOnTransientHttpFailures() throws Exception {
        AtomicInteger requestCount = new AtomicInteger();

        server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/functions/v1/invoke-junit-llm-cli", exchange -> {
            int current = requestCount.incrementAndGet();
            if (current < 4) {
                writeJson(exchange, 503, "{\"error\":\"temporarily unavailable\"}");
                return;
            }
            writeJson(exchange, "{\"jobId\":\"job-1\",\"sessionId\":\"session-1\"}");
        });
        server.start();

        HttpJunitLlmBackendClient client = newClientWithoutSleep();

        InvokeJunitLlmResponse response = client.invoke(sampleRequest());

        assertEquals(4, requestCount.get());
        assertEquals("job-1", response.jobId());
    }

    @Test
    void fetchJobRetriesOnTransientHttpFailures() throws Exception {
        AtomicInteger requestCount = new AtomicInteger();

        server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/functions/v1/fetch-job-cli", exchange -> {
            int current = requestCount.incrementAndGet();
            if (current < 3) {
                writeJson(exchange, 429, "{\"error\":\"rate limited\"}");
                return;
            }
            writeJson(
                    exchange,
                    "{\"status\":\"done\",\"output\":{\"sessionId\":\"session-1\",\"finalTestFile\":\"class body\"}}"
            );
        });
        server.start();

        HttpJunitLlmBackendClient client = newClientWithoutSleep();

        FetchJobResponse response = client.fetchJob("job-1");

        assertEquals(3, requestCount.get());
        assertEquals("done", response.status());
        assertEquals("session-1", response.output().sessionId());
    }

    @Test
    void invokeStopsAfterTenRetries() throws Exception {
        AtomicInteger requestCount = new AtomicInteger();

        server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/functions/v1/invoke-junit-llm-cli", exchange -> {
            requestCount.incrementAndGet();
            writeJson(exchange, 503, "{\"error\":\"temporarily unavailable\"}");
        });
        server.start();

        HttpJunitLlmBackendClient client = newClientWithoutSleep();

        IllegalStateException exception = assertThrows(IllegalStateException.class, () -> client.invoke(sampleRequest()));

        assertEquals("temporarily unavailable", exception.getMessage());
        assertEquals(11, requestCount.get());
    }

    private String baseUrl() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    private void writeJson(HttpExchange exchange, String body) throws IOException {
        writeJson(exchange, 200, body);
    }

    private void writeJson(HttpExchange exchange, int statusCode, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(statusCode, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }

    private HttpJunitLlmBackendClient newClientWithoutSleep() {
        return new HttpJunitLlmBackendClient(
                new CurlHttpClient(),
                new ObjectMapper(),
                baseUrl(),
                "token-123",
                millis -> {
                }
        );
    }

    private InvokeJunitLlmRequest sampleRequest() {
        return new InvokeJunitLlmRequest(
                null,
                "generate",
                "CrashController",
                "CrashControllerTest",
                "5.11.0",
                "class body",
                List.of(),
                "",
                List.of(),
                "",
                null
        );
    }
}
