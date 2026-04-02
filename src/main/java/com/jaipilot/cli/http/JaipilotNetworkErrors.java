package com.jaipilot.cli.http;

import java.io.IOException;
import java.net.ConnectException;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.UnknownHostException;
import java.net.http.HttpTimeoutException;
import java.security.cert.CertPathBuilderException;
import java.security.cert.CertificateException;
import java.util.Locale;
import javax.net.ssl.SSLException;
import javax.net.ssl.SSLHandshakeException;

public final class JaipilotNetworkErrors {

    private JaipilotNetworkErrors() {
    }

    public static IOException wrapIo(
            String action,
            URI uri,
            IOException exception,
            JaipilotHttpClientDiagnostics diagnostics
    ) {
        return new IOException(describe(action, uri, exception, diagnostics), exception);
    }

    public static IllegalStateException wrapRuntime(
            String action,
            URI uri,
            Exception exception,
            JaipilotHttpClientDiagnostics diagnostics
    ) {
        return new IllegalStateException(describe(action, uri, exception, diagnostics), exception);
    }

    public static String describe(
            String action,
            URI uri,
            Throwable failure,
            JaipilotHttpClientDiagnostics diagnostics
    ) {
        String host = uri == null || uri.getHost() == null ? "the configured endpoint" : uri.getHost();
        if (isProxyFailure(failure)) {
            return "JAIPilot could not reach %s while trying to %s because the configured proxy rejected or blocked the connection. "
                    .formatted(host, action)
                    + "Proxy mode: " + diagnostics.proxySummary() + ". "
                    + "Check HTTPS_PROXY/HTTP_PROXY/NO_PROXY or JVM proxy settings and retry.";
        }
        if (isTlsFailure(failure)) {
            return "JAIPilot could not establish a trusted TLS connection to %s while trying to %s. "
                    .formatted(host, action)
                    + "Trust mode: " + diagnostics.trustSummary() + ". "
                    + "JAIPilot does not override trust settings. Ensure this certificate is trusted by your default JVM/OS trust store, "
                    + "then retry.";
        }
        if (findCause(failure, UnknownHostException.class) != null) {
            return "JAIPilot could not resolve %s while trying to %s. ".formatted(host, action)
                    + "Check DNS, network connectivity, or proxy settings. Proxy mode: "
                    + diagnostics.proxySummary() + ".";
        }
        if (findCause(failure, ConnectException.class) != null || findCause(failure, HttpTimeoutException.class) != null) {
            return "JAIPilot could not reach %s while trying to %s. ".formatted(host, action)
                    + "Check network connectivity or proxy settings. Proxy mode: "
                    + diagnostics.proxySummary() + ".";
        }
        return "JAIPilot hit a network error while trying to %s against %s. ".formatted(action, host)
                + "Trust mode: " + diagnostics.trustSummary() + ". "
                + "Proxy mode: " + diagnostics.proxySummary() + ". "
                + "Retry with --verbose for additional details.";
    }

    private static boolean isTlsFailure(Throwable failure) {
        return findCause(failure, SSLHandshakeException.class) != null
                || findCause(failure, CertPathBuilderException.class) != null
                || findCause(failure, CertificateException.class) != null
                || (findCause(failure, SSLException.class) != null && !isProxyFailure(failure));
    }

    private static boolean isProxyFailure(Throwable failure) {
        String message = collectMessages(failure);
        return message.contains("proxy")
                || message.contains("tunnel failed")
                || message.contains("407")
                || message.contains(String.valueOf(HttpURLConnection.HTTP_PROXY_AUTH));
    }

    private static String collectMessages(Throwable failure) {
        StringBuilder value = new StringBuilder();
        Throwable current = failure;
        while (current != null) {
            if (current.getMessage() != null && !current.getMessage().isBlank()) {
                if (value.length() > 0) {
                    value.append(' ');
                }
                value.append(current.getMessage().toLowerCase(Locale.ROOT));
            }
            current = current.getCause();
        }
        return value.toString();
    }

    private static <T extends Throwable> T findCause(Throwable failure, Class<T> type) {
        Throwable current = failure;
        while (current != null) {
            if (type.isInstance(current)) {
                return type.cast(current);
            }
            current = current.getCause();
        }
        return null;
    }
}
