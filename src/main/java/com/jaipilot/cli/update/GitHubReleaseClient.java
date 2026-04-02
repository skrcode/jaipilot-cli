package com.jaipilot.cli.update;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jaipilot.cli.JaiPilotVersionProvider;
import com.jaipilot.cli.http.JaipilotHttpClientFactory;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;

final class GitHubReleaseClient implements ReleaseClient {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private static final String DEFAULT_REPO = "JAIPilot/jaipilot-cli";
    private static final String DEFAULT_API_BASE = "https://api.github.com";
    private static final String DEFAULT_DOWNLOAD_BASE = "https://github.com";

    private final JaipilotHttpClientFactory httpClientFactory;
    private final String repo;
    private final String apiBase;
    private final String downloadBase;

    GitHubReleaseClient() {
        this(
                new JaipilotHttpClientFactory(),
                DEFAULT_REPO,
                DEFAULT_API_BASE,
                DEFAULT_DOWNLOAD_BASE
        );
    }

    GitHubReleaseClient(
            JaipilotHttpClientFactory httpClientFactory,
            String repo,
            String apiBase,
            String downloadBase
    ) {
        this.httpClientFactory = httpClientFactory;
        this.repo = repo;
        this.apiBase = trimTrailingSlash(apiBase);
        this.downloadBase = trimTrailingSlash(downloadBase);
    }

    @Override
    public String fetchLatestVersion(Duration timeout) {
        URI uri = URI.create(apiBase + "/repos/" + repo + "/releases/latest");
        HttpResponse<String> response = send(
                uri,
                timeout,
                HttpResponse.BodyHandlers.ofString()
        );
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new IllegalStateException("Update check failed with HTTP " + response.statusCode() + ".");
        }
        try {
            JsonNode json = OBJECT_MAPPER.readTree(response.body());
            String tagName = json.path("tag_name").asText(null);
            if (tagName == null || tagName.isBlank()) {
                throw new IllegalStateException("Update check did not return a usable release version.");
            }
            return VersionComparator.normalize(tagName);
        } catch (IOException exception) {
            throw new IllegalStateException("Failed to parse the latest release metadata.", exception);
        }
    }

    @Override
    public URI archiveUri(String version, String platform) {
        String normalizedVersion = VersionComparator.normalize(version);
        return URI.create(
                downloadBase
                        + "/"
                        + repo
                        + "/releases/download/v"
                        + normalizedVersion
                        + "/jaipilot-"
                        + normalizedVersion
                        + "-"
                        + platform
                        + ".tar.gz"
        );
    }

    @Override
    public URI checksumUri(String version, String platform) {
        return URI.create(archiveUri(version, platform).toString() + ".sha256");
    }

    @Override
    public void download(URI source, Path destination, Duration timeout) {
        try {
            Files.createDirectories(destination.getParent());
        } catch (IOException exception) {
            throw new IllegalStateException("Failed to prepare the update download directory.", exception);
        }

        if ("file".equalsIgnoreCase(source.getScheme())) {
            try {
                Files.copy(Path.of(source), destination, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                return;
            } catch (IOException exception) {
                throw new IllegalStateException("Failed to read the local update asset: " + source, exception);
            }
        }

        HttpResponse<Path> response = send(
                source,
                timeout,
                HttpResponse.BodyHandlers.ofFile(destination)
        );
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new IllegalStateException("Update download failed with HTTP " + response.statusCode() + " for " + source + ".");
        }
    }

    private <T> HttpResponse<T> send(URI uri, Duration timeout, HttpResponse.BodyHandler<T> bodyHandler) {
        Duration effectiveTimeout = timeout == null || timeout.isZero() || timeout.isNegative()
                ? Duration.ofSeconds(10)
                : timeout;
        HttpClient httpClient = httpClientFactory.create(effectiveTimeout);
        HttpRequest request = HttpRequest.newBuilder(uri)
                .timeout(effectiveTimeout)
                .header("Accept", "application/vnd.github+json")
                .header("User-Agent", "jaipilot-cli/" + JaiPilotVersionProvider.resolveVersion())
                .GET()
                .build();
        try {
            return httpClient.send(request, bodyHandler);
        } catch (IOException exception) {
            throw new IllegalStateException("Failed to reach the update server.", exception);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("The update request was interrupted.", exception);
        }
    }

    private static String trimTrailingSlash(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("Update endpoint base URL must not be blank.");
        }
        return value.endsWith("/") ? value.substring(0, value.length() - 1) : value;
    }
}
