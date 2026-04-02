package com.jaipilot.cli.http;

public record JaipilotHttpClientDiagnostics(
        ProxyMode proxyMode,
        String httpsProxy,
        String httpProxy,
        String noProxy
) {

    public JaipilotHttpClientDiagnostics {
        httpsProxy = normalizeBlank(httpsProxy);
        httpProxy = normalizeBlank(httpProxy);
        noProxy = normalizeBlank(noProxy);
    }

    public static JaipilotHttpClientDiagnostics defaults() {
        return new JaipilotHttpClientDiagnostics(
                ProxyMode.DIRECT,
                null,
                null,
                null
        );
    }

    public String trustSummary() {
        return "Default JVM/OS trust store (no JAIPilot overrides)";
    }

    public String proxySummary() {
        return switch (proxyMode) {
            case ENVIRONMENT -> {
                StringBuilder summary = new StringBuilder("Environment proxies");
                if (httpsProxy != null) {
                    summary.append(" https=").append(httpsProxy);
                }
                if (httpProxy != null) {
                    summary.append(" http=").append(httpProxy);
                }
                if (noProxy != null) {
                    summary.append(" no_proxy=").append(noProxy);
                }
                yield summary.toString();
            }
            case JVM_PROPERTIES -> "JVM proxy properties";
            case SYSTEM -> "System proxy selector (java.net.useSystemProxies=true)";
            case DIRECT -> "Direct network access";
        };
    }

    public enum ProxyMode {
        ENVIRONMENT,
        JVM_PROPERTIES,
        SYSTEM,
        DIRECT
    }

    private static String normalizeBlank(String value) {
        return value == null || value.isBlank() ? null : value;
    }
}
