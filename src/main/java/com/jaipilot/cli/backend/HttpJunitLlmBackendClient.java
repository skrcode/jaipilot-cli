package com.jaipilot.cli.backend;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.jaipilot.cli.http.CurlHttpClient;
import com.jaipilot.cli.http.JaipilotNetworkErrors;
import com.jaipilot.cli.model.FetchJobResponse;
import com.jaipilot.cli.model.InvokeJunitLlmRequest;
import com.jaipilot.cli.model.InvokeJunitLlmResponse;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

public final class HttpJunitLlmBackendClient implements JunitLlmBackendClient {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private static final String INVOKE_PATH = "/functions/v1/invoke-junit-llm-cli";
    private static final String FETCH_JOB_PATH = "/functions/v1/fetch-job-cli?id=";
    private static final int MAX_RETRIES = 10;
    private static final long INITIAL_RETRY_DELAY_MILLIS = 100L;
    private static final long MAX_RETRY_DELAY_MILLIS = 2_000L;
    private static final Set<Integer> RETRIABLE_HTTP_STATUS_CODES = Set.of(408, 425, 429);
    private static final Set<Integer> RETRIABLE_CURL_EXIT_CODES = Set.of(5, 6, 7, 18, 28, 52, 55, 56, 92);

    interface SleepStrategy {
        void sleep(long millis) throws InterruptedException;
    }

    private final CurlHttpClient curlHttpClient;
    private final ObjectMapper objectMapper;
    private final String backendUrl;
    private final String jwtToken;
    private final SleepStrategy sleepStrategy;

    public HttpJunitLlmBackendClient(String backendUrl, String jwtToken) {
        this(
                new CurlHttpClient(),
                OBJECT_MAPPER,
                backendUrl,
                jwtToken,
                Thread::sleep
        );
    }

    HttpJunitLlmBackendClient(
            CurlHttpClient curlHttpClient,
            ObjectMapper objectMapper,
            String backendUrl,
            String jwtToken
    ) {
        this(curlHttpClient, objectMapper, backendUrl, jwtToken, Thread::sleep);
    }

    HttpJunitLlmBackendClient(
            CurlHttpClient curlHttpClient,
            ObjectMapper objectMapper,
            String backendUrl,
            String jwtToken,
            SleepStrategy sleepStrategy
    ) {
        if (backendUrl == null || backendUrl.isBlank()) {
            throw new IllegalArgumentException("backendUrl is required");
        }
        if (jwtToken == null || jwtToken.isBlank()) {
            throw new IllegalArgumentException("jwtToken is required");
        }
        this.curlHttpClient = Objects.requireNonNull(curlHttpClient, "curlHttpClient");
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper");
        this.backendUrl = trimTrailingSlash(backendUrl);
        this.jwtToken = jwtToken.trim();
        this.sleepStrategy = Objects.requireNonNull(sleepStrategy, "sleepStrategy");
    }

    @Override
    public InvokeJunitLlmResponse invoke(InvokeJunitLlmRequest request) throws IOException, InterruptedException {
        URI uri = URI.create(backendUrl + INVOKE_PATH);
        String body = buildInvokeRequestBody(request);

        CurlHttpClient.CurlResponse response;
        try {
            response = requestWithRetries(
                    uri,
                    "POST",
                    Map.of(
                            "Accept", "application/json",
                            "Authorization", "Bearer " + jwtToken,
                            "Content-Type", "application/json"
                    ),
                    body,
                    Duration.ofSeconds(60)
            );
        } catch (IOException exception) {
            throw JaipilotNetworkErrors.wrapIo(
                    "invoke the JAIPilot backend",
                    uri,
                    exception
            );
        }
        return objectMapper.readValue(response.body(), InvokeJunitLlmResponse.class);
    }

    @Override
    public FetchJobResponse fetchJob(String jobId) throws IOException, InterruptedException {
        URI uri = URI.create(backendUrl + FETCH_JOB_PATH + URLEncoder.encode(jobId, StandardCharsets.UTF_8));

        CurlHttpClient.CurlResponse response;
        try {
            response = requestWithRetries(
                    uri,
                    "GET",
                    Map.of(
                            "Accept", "application/json",
                            "Authorization", "Bearer " + jwtToken
                    ),
                    null,
                    Duration.ofSeconds(30)
            );
        } catch (IOException exception) {
            throw JaipilotNetworkErrors.wrapIo(
                    "poll the JAIPilot backend",
                    uri,
                    exception
            );
        }
        return parseFetchJobResponse(response.body());
    }

    private CurlHttpClient.CurlResponse requestWithRetries(
            URI uri,
            String method,
            Map<String, String> headers,
            String requestBody,
            Duration timeout
    ) throws IOException, InterruptedException {
        for (int retry = 0; retry <= MAX_RETRIES; retry++) {
            CurlHttpClient.CurlResponse response;
            try {
                response = curlHttpClient.request(method, uri, headers, requestBody, timeout);
            } catch (IOException exception) {
                if (!isRetriable(exception) || retry == MAX_RETRIES) {
                    throw exception;
                }
                sleepBeforeRetry(retry + 1);
                continue;
            }

            if (response.statusCode() / 100 == 2) {
                return response;
            }
            if (isRetriableStatusCode(response.statusCode()) && retry < MAX_RETRIES) {
                sleepBeforeRetry(retry + 1);
                continue;
            }
            throw new IllegalStateException(extractErrorMessage(response.body()));
        }

        throw new IllegalStateException("Backend request failed after retries.");
    }

    private void sleepBeforeRetry(int retryAttempt) throws InterruptedException {
        sleepStrategy.sleep(retryDelayMillis(retryAttempt));
    }

    private long retryDelayMillis(int retryAttempt) {
        long delayMillis = INITIAL_RETRY_DELAY_MILLIS;
        for (int index = 1; index < retryAttempt; index++) {
            delayMillis = Math.min(delayMillis * 2L, MAX_RETRY_DELAY_MILLIS);
        }
        return delayMillis;
    }

    private boolean isRetriableStatusCode(int statusCode) {
        return statusCode >= 500 || RETRIABLE_HTTP_STATUS_CODES.contains(statusCode);
    }

    private boolean isRetriable(IOException exception) {
        CurlHttpClient.CurlException curlException = findCause(exception, CurlHttpClient.CurlException.class);
        if (curlException == null) {
            return true;
        }
        return switch (curlException.kind()) {
            case TIMEOUT -> true;
            case EXECUTION -> {
                Integer exitCode = curlException.exitCode();
                yield exitCode == null || RETRIABLE_CURL_EXIT_CODES.contains(exitCode);
            }
            case MISSING_CURL, INVALID_RESPONSE -> false;
        };
    }

    private <T extends Throwable> T findCause(Throwable failure, Class<T> type) {
        Throwable current = failure;
        while (current != null) {
            if (type.isInstance(current)) {
                return type.cast(current);
            }
            current = current.getCause();
        }
        return null;
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
