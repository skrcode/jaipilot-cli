package com.jaipilot.cli.http;

import java.net.InetSocketAddress;
import java.net.Proxy;
import java.net.ProxySelector;
import java.net.SocketAddress;
import java.net.URI;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertEquals;

class JaipilotHttpClientFactoryTest {

    @Test
    void environmentProxyTakesPrecedenceAndHonorsNoProxy() {
        Properties systemProperties = new Properties();
        systemProperties.setProperty("https.proxyHost", "jvm-proxy.local");
        JaipilotHttpClientFactory factory = new JaipilotHttpClientFactory(
                Map.of(
                        "HTTPS_PROXY", "http://env-proxy.local:8443",
                        "NO_PROXY", "127.0.0.1,.corp.local"
                ),
                systemProperties,
                new StaticProxySelector("jvm-proxy.local", 9443)
        );

        List<Proxy> selected = factory.proxySelector().select(URI.create("https://api.example.com"));
        InetSocketAddress address = (InetSocketAddress) selected.get(0).address();

        assertEquals(JaipilotHttpClientDiagnostics.ProxyMode.ENVIRONMENT, factory.diagnostics().proxyMode());
        assertEquals("env-proxy.local", address.getHostString());
        assertEquals(8443, address.getPort());
        assertEquals(List.of(Proxy.NO_PROXY), factory.proxySelector().select(URI.create("https://service.corp.local")));
    }

    @Test
    void diagnosticsUseJvmProxyModeWhenProxyPropertiesArePresent() {
        Properties systemProperties = new Properties();
        systemProperties.setProperty("https.proxyHost", "jvm-proxy.local");

        JaipilotHttpClientFactory factory = new JaipilotHttpClientFactory(
                Map.of(),
                systemProperties,
                new StaticProxySelector("jvm-proxy.local", 9443)
        );

        assertEquals(JaipilotHttpClientDiagnostics.ProxyMode.JVM_PROPERTIES, factory.diagnostics().proxyMode());
    }

    private static final class StaticProxySelector extends ProxySelector {

        private final Proxy proxy;

        private StaticProxySelector(String host, int port) {
            this.proxy = new Proxy(Proxy.Type.HTTP, new InetSocketAddress(host, port));
        }

        @Override
        public List<Proxy> select(URI uri) {
            return List.of(proxy);
        }

        @Override
        public void connectFailed(URI uri, SocketAddress sa, java.io.IOException ioe) {
            // No-op for tests.
        }
    }
}
