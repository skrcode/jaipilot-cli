package com.jaipilot.cli.commands;

import com.jaipilot.cli.http.JaipilotHttpClientFactory;
import com.jaipilot.cli.testutil.HttpsTestServer;
import com.sun.net.httpserver.HttpServer;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.net.InetSocketAddress;
import java.util.Map;
import java.util.Properties;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import picocli.CommandLine;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DoctorCommandTest {

    private HttpServer websiteServer;
    private HttpServer backendServer;

    @AfterEach
    void tearDown() {
        if (websiteServer != null) {
            websiteServer.stop(0);
        }
        if (backendServer != null) {
            backendServer.stop(0);
        }
    }

    @Test
    void doctorReportsHealthyEndpoints() throws Exception {
        websiteServer = startHttpServer("website ok");
        backendServer = startHttpServer("backend ok");

        StringWriter outBuffer = new StringWriter();
        DoctorCommand command = new DoctorCommand(
                new JaipilotHttpClientFactory(Map.of(), new Properties(), null),
                baseUrl(websiteServer),
                baseUrl(backendServer)
        );

        int exitCode = new CommandLine(command)
                .setOut(new PrintWriter(outBuffer, true))
                .setErr(new PrintWriter(new StringWriter(), true))
                .execute();

        String output = outBuffer.toString();
        assertEquals(0, exitCode);
        assertTrue(output.contains("JAIPilot doctor"));
        assertTrue(output.contains("Website: OK"));
        assertTrue(output.contains("Backend: OK"));
        assertTrue(output.contains("Result: OK"));
    }

    @Test
    void doctorReportsFriendlyTlsFailureMessage() throws Exception {
        StringWriter outBuffer = new StringWriter();
        try (HttpsTestServer server = HttpsTestServer.start(httpsServer -> httpsServer.createContext(
                "/",
                exchange -> HttpsTestServer.writeText(exchange, "ok")
        ))) {
            DoctorCommand command = new DoctorCommand(
                    new JaipilotHttpClientFactory(Map.of(), new Properties(), null),
                    server.baseUrl(),
                    server.baseUrl()
            );

            int exitCode = new CommandLine(command)
                    .setOut(new PrintWriter(outBuffer, true))
                    .setErr(new PrintWriter(new StringWriter(), true))
                    .execute();

            String output = outBuffer.toString();
            assertEquals(CommandLine.ExitCode.SOFTWARE, exitCode);
            assertTrue(output.contains("trusted TLS connection"));
            assertTrue(output.contains("Remediation:"));
            assertTrue(output.contains("default JVM/OS trust store"));
            assertFalse(output.contains("PKIX"));
        }
    }

    private HttpServer startHttpServer(String body) throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> HttpsTestServer.writeText(exchange, body));
        server.start();
        return server;
    }

    private String baseUrl(HttpServer server) {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }
}
