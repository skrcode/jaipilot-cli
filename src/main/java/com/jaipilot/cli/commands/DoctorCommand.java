package com.jaipilot.cli.commands;

import com.jaipilot.cli.JaipilotEndpointConfig;
import com.jaipilot.cli.http.JaipilotHttpClientDiagnostics;
import com.jaipilot.cli.http.JaipilotHttpClientFactory;
import com.jaipilot.cli.http.JaipilotNetworkErrors;
import java.io.PrintWriter;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Option;
import picocli.CommandLine.Spec;

@Command(
        name = "doctor",
        mixinStandardHelpOptions = true,
        description = "Diagnose JAIPilot TLS and proxy configuration."
)
public final class DoctorCommand implements Callable<Integer> {

    @Option(
            names = "--timeout-seconds",
            defaultValue = "10",
            paramLabel = "<seconds>",
            description = "Maximum time to wait for each endpoint check. Default: ${DEFAULT-VALUE}."
    )
    private long timeoutSeconds;

    @Spec
    private CommandSpec spec;

    private final JaipilotHttpClientFactory httpClientFactory;
    private final String websiteBase;
    private final String backendUrl;

    public DoctorCommand() {
        this(
                new JaipilotHttpClientFactory(),
                JaipilotEndpointConfig.resolveWebsiteBase(),
                JaipilotEndpointConfig.resolveBackendUrl()
        );
    }

    DoctorCommand(JaipilotHttpClientFactory httpClientFactory, String websiteBase, String backendUrl) {
        this.httpClientFactory = httpClientFactory;
        this.websiteBase = websiteBase;
        this.backendUrl = backendUrl;
    }

    @Override
    public Integer call() {
        PrintWriter out = spec.commandLine().getOut();
        if (timeoutSeconds < 1) {
            throw new CommandLine.ParameterException(spec.commandLine(), "--timeout-seconds must be greater than zero.");
        }

        JaipilotHttpClientDiagnostics diagnostics;
        HttpClient httpClient;
        try {
            diagnostics = httpClientFactory.diagnostics();
            httpClient = httpClientFactory.create(Duration.ofSeconds(timeoutSeconds));
        } catch (RuntimeException exception) {
            out.println("JAIPilot doctor could not initialize the network stack.");
            out.println(exception.getMessage());
            out.flush();
            return CommandLine.ExitCode.SOFTWARE;
        }

        out.println("JAIPilot doctor");
        out.println("Java version: " + System.getProperty("java.version", "unknown"));
        out.println("Runtime: " + ProcessHandle.current().info().command().orElse("unknown"));
        out.println("Trust: " + diagnostics.trustSummary());
        out.println("Proxy: " + diagnostics.proxySummary());
        out.flush();

        List<String> failures = new ArrayList<>();
        checkEndpoint(httpClient, diagnostics, "Website", websiteBase, failures, out);
        checkEndpoint(httpClient, diagnostics, "Backend", backendUrl, failures, out);

        if (failures.isEmpty()) {
            out.println("Result: OK");
            out.flush();
            return CommandLine.ExitCode.OK;
        }

        out.println("Result: FAILED");
        out.println("Remediation:");
        out.println("- JAIPilot uses the default JVM/OS trust store only. Import your corporate CA into that store if TLS fails.");
        out.println("- Check HTTPS_PROXY/HTTP_PROXY/NO_PROXY or your JVM proxy settings.");
        out.flush();
        return CommandLine.ExitCode.SOFTWARE;
    }

    private void checkEndpoint(
            HttpClient httpClient,
            JaipilotHttpClientDiagnostics diagnostics,
            String label,
            String endpoint,
            List<String> failures,
            PrintWriter out
    ) {
        URI uri = URI.create(endpoint);
        HttpRequest request = HttpRequest.newBuilder(uri)
                .timeout(Duration.ofSeconds(timeoutSeconds))
                .GET()
                .build();
        try {
            HttpResponse<Void> response = httpClient.send(request, HttpResponse.BodyHandlers.discarding());
            out.println(label + ": OK (HTTP " + response.statusCode() + " from " + uri.getHost() + ")");
        } catch (Exception exception) {
            String message = JaipilotNetworkErrors.describe(
                    "check connectivity",
                    uri,
                    exception,
                    diagnostics
            );
            failures.add(message);
            out.println(label + ": FAIL");
            out.println(message);
        }
        out.flush();
    }
}
