package com.jaipilot.cli.backend;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.jaipilot.cli.model.FetchJobResponse;
import com.jaipilot.cli.model.InvokeJunitLlmRequest;
import com.jaipilot.cli.model.InvokeJunitLlmResponse;
import java.io.IOException;
import java.io.PrintWriter;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

public final class HttpJunitLlmBackendClient implements JunitLlmBackendClient {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private static final String INVOKE_PATH = "/functions/v1/invoke-junit-llm-cli";
    private static final String FETCH_JOB_PATH = "/functions/v1/fetch-job-cli?id=";

    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;
    private final String backendUrl;
    private final String jwtToken;

    public HttpJunitLlmBackendClient(String backendUrl, String jwtToken) {
        this(
                HttpClient.newBuilder()
                        .connectTimeout(Duration.ofSeconds(20))
                        .build(),
                OBJECT_MAPPER,
                backendUrl,
                jwtToken
        );
    }

    HttpJunitLlmBackendClient(
            HttpClient httpClient,
            ObjectMapper objectMapper,
            String backendUrl,
            String jwtToken
    ) {
        if (backendUrl == null || backendUrl.isBlank()) {
            throw new IllegalArgumentException("backendUrl is required");
        }
        if (jwtToken == null || jwtToken.isBlank()) {
            throw new IllegalArgumentException("jwtToken is required");
        }
        this.httpClient = httpClient;
        this.objectMapper = objectMapper;
        this.backendUrl = trimTrailingSlash(backendUrl);
        this.jwtToken = jwtToken.trim();
    }

    @Override
    public InvokeJunitLlmResponse invoke(InvokeJunitLlmRequest request) throws IOException, InterruptedException {
        String url = backendUrl + INVOKE_PATH;
        String body = buildInvokeRequestBody(request);

        HttpRequest httpRequest = baseRequest(url)
                .timeout(Duration.ofSeconds(60))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                .build();
        HttpResponse<String> response = httpClient.send(
                httpRequest,
                HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8)
        );
        ensureSuccessful(response);
        return objectMapper.readValue(response.body(), InvokeJunitLlmResponse.class);
    }

    @Override
    public FetchJobResponse fetchJob(String jobId) throws IOException, InterruptedException {
        String url = backendUrl + FETCH_JOB_PATH + URLEncoder.encode(jobId, StandardCharsets.UTF_8);

        HttpRequest httpRequest = baseRequest(url)
                .timeout(Duration.ofSeconds(30))
                .GET()
                .build();
        HttpResponse<String> response = httpClient.send(
                httpRequest,
                HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8)
        );
        ensureSuccessful(response);
        return parseFetchJobResponse(response.body());
    }

    private FetchJobResponse parseFetchJobResponse(String body) throws IOException {
        JsonNode root = objectMapper.readTree(body);
        String status = textValue(root, "status");
        String error = textValue(root, "error");

        FetchJobResponse.FetchJobOutput output = null;
        String rawOutput = null;
        JsonNode outputNode = root.get("output");
        if (outputNode != null && !outputNode.isNull()) {
            if (outputNode.isObject()) {
                output = objectMapper.treeToValue(outputNode, FetchJobResponse.FetchJobOutput.class);
            } else if (outputNode.isTextual()) {
                rawOutput = outputNode.asText();
                String trimmed = rawOutput.trim();
                if (trimmed.startsWith("{") && trimmed.endsWith("}")) {
                    try {
                        output = objectMapper.readValue(trimmed, FetchJobResponse.FetchJobOutput.class);
                    } catch (IOException ignored) {
                        // Leave rawOutput populated so callers can surface the backend error.
                    }
                }
            } else {
                rawOutput = outputNode.toString();
            }
        }

        return new FetchJobResponse(status, output, error, rawOutput);
    }

    private String buildInvokeRequestBody(InvokeJunitLlmRequest request) throws IOException {
        ObjectNode root = objectMapper.createObjectNode();
        putOptionalString(root, "sessionId", request.sessionId());
        putString(root, "type", request.type());
        putString(root, "cutName", request.cutName());
        putString(root, "testClassName", request.testClassName());
        putString(root, "mockitoVersion", request.mockitoVersion());
        putString(root, "cutCode", request.cutCode());
        putStringArray(root, "cachedContextClasses", request.cachedContextClasses());
        putString(root, "initialTestClassCode", request.initialTestClassCode());
        putStringArray(root, "contextClasses", request.contextClasses());
        putString(root, "newTestClassCode", request.newTestClassCode());
        putNullableString(root, "clientLogs", request.clientLogs());
        return objectMapper.writeValueAsString(root);
    }

    private HttpRequest.Builder baseRequest(String url) {
        return HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("Accept", "application/json")
                .header("Authorization", "Bearer " + jwtToken);
    }

    private void ensureSuccessful(HttpResponse<String> response) {
        if (response.statusCode() / 100 == 2) {
            return;
        }
        throw new IllegalStateException(extractErrorMessage(response.body()));
    }

    private static String trimTrailingSlash(String value) {
        if (value.endsWith("/")) {
            return value.substring(0, value.length() - 1);
        }
        return value;
    }

    private static String textValue(JsonNode node, String fieldName) {
        if (node == null || fieldName == null) {
            return null;
        }
        JsonNode child = node.get(fieldName);
        if (child == null || child.isNull()) {
            return null;
        }
        if (child.isTextual()) {
            return child.asText();
        }
        return child.toString();
    }

    private String extractErrorMessage(String body) {
        try {
            JsonNode root = objectMapper.readTree(body);
            String error = textValue(root, "error");
            if (error != null && !error.isBlank()) {
                return error;
            }
        } catch (IOException ignored) {
            // Fall through to the raw body.
        }
        if (body != null && !body.isBlank()) {
            return body.trim();
        }
        return "Backend request failed.";
    }

    private static void putString(ObjectNode node, String fieldName, String value) {
        node.put(fieldName, value);
    }

    private static void putOptionalString(ObjectNode node, String fieldName, String value) {
        if (value == null) {
            return;
        }
        node.put(fieldName, value);
    }

    private static void putNullableString(ObjectNode node, String fieldName, String value) {
        if (value == null) {
            node.putNull(fieldName);
            return;
        }
        node.put(fieldName, value);
    }

    private static void putStringArray(ObjectNode node, String fieldName, java.util.List<String> values) {
        ArrayNode arrayNode = node.putArray(fieldName);
        if (values == null) {
            return;
        }
        for (String value : values) {
            if (value != null) {
                arrayNode.add(value);
            }
        }
    }
}
