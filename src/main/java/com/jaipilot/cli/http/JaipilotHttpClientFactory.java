package com.jaipilot.cli.http;

import java.net.ProxySelector;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Properties;

public final class JaipilotHttpClientFactory {

    private final Map<String, String> environment;
    private final Properties systemProperties;
    private final ProxySelector defaultProxySelector;

    public JaipilotHttpClientFactory() {
        this(System.getenv(), System.getProperties(), ProxySelector.getDefault());
    }

    public JaipilotHttpClientFactory(
            Map<String, String> environment,
            Properties systemProperties,
            ProxySelector defaultProxySelector
    ) {
        this.environment = Map.copyOf(Objects.requireNonNull(environment, "environment"));
        this.systemProperties = copyOf(Objects.requireNonNull(systemProperties, "systemProperties"));
        this.defaultProxySelector = defaultProxySelector;
    }

    public HttpClient create(Duration connectTimeout) {
        ProxyConfiguration proxyConfiguration = resolveProxyConfiguration();
        HttpClient.Builder builder = HttpClient.newBuilder().connectTimeout(connectTimeout);
        if (proxyConfiguration.proxySelector() != null) {
            builder.proxy(proxyConfiguration.proxySelector());
        }
        return builder.build();
    }

    public JaipilotHttpClientDiagnostics diagnostics() {
        return resolveProxyConfiguration().diagnostics();
    }

    ProxySelector proxySelector() {
        return resolveProxyConfiguration().proxySelector();
    }

    private ProxyConfiguration resolveProxyConfiguration() {
        JaipilotProxySelector.ProxySettings settings = JaipilotProxySelector.fromEnvironment(
                environmentValue("HTTPS_PROXY", "https_proxy"),
                environmentValue("HTTP_PROXY", "http_proxy"),
                environmentValue("NO_PROXY", "no_proxy")
        );
        ProxySelector proxySelector = settings.hasConfiguredProxy()
                ? new JaipilotProxySelector(settings, defaultProxySelector)
                : defaultProxySelector;

        JaipilotHttpClientDiagnostics.ProxyMode proxyMode;
        if (settings.hasConfiguredProxy()) {
            proxyMode = JaipilotHttpClientDiagnostics.ProxyMode.ENVIRONMENT;
        } else if (hasJvmProxyProperties()) {
            proxyMode = JaipilotHttpClientDiagnostics.ProxyMode.JVM_PROPERTIES;
        } else if (parseBoolean(systemProperties.getProperty("java.net.useSystemProxies"))) {
            proxyMode = JaipilotHttpClientDiagnostics.ProxyMode.SYSTEM;
        } else {
            proxyMode = JaipilotHttpClientDiagnostics.ProxyMode.DIRECT;
        }

        return new ProxyConfiguration(
                proxySelector,
                new JaipilotHttpClientDiagnostics(
                        proxyMode,
                        settings.httpsProxyDisplay(),
                        settings.httpProxyDisplay(),
                        firstNonBlank(environmentValue("NO_PROXY", "no_proxy"))
                )
        );
    }

    private boolean hasJvmProxyProperties() {
        return firstNonBlank(
                systemProperties.getProperty("https.proxyHost"),
                systemProperties.getProperty("http.proxyHost"),
                systemProperties.getProperty("socksProxyHost")
        ) != null;
    }

    private String environmentValue(String... names) {
        for (String name : names) {
            String value = environment.get(name);
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return null;
    }

    private static boolean parseBoolean(String value) {
        if (value == null) {
            return false;
        }
        return switch (value.trim().toLowerCase(Locale.ROOT)) {
            case "1", "true", "yes", "on" -> true;
            default -> false;
        };
    }

    private static String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return null;
    }

    private static Properties copyOf(Properties properties) {
        Properties copy = new Properties();
        copy.putAll(properties);
        return copy;
    }

    private record ProxyConfiguration(
            ProxySelector proxySelector,
            JaipilotHttpClientDiagnostics diagnostics
    ) {
    }
}
