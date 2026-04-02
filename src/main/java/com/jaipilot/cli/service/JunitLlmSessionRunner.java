package com.jaipilot.cli.service;

import com.jaipilot.cli.backend.JunitLlmBackendClient;
import com.jaipilot.cli.files.ProjectFileService;
import com.jaipilot.cli.model.FetchJobResponse;
import com.jaipilot.cli.model.InvokeJunitLlmRequest;
import com.jaipilot.cli.model.InvokeJunitLlmResponse;
import com.jaipilot.cli.model.JunitLlmSessionRequest;
import com.jaipilot.cli.model.JunitLlmSessionResult;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

public final class JunitLlmSessionRunner {

    private static final int MAX_INTERACTIONS = 10;
    private static final int MAX_FETCH_ATTEMPTS = 120;
    private static final long FETCH_DELAY_MILLIS = 1_000L;
    private static final String DEFAULT_MOCKITO_VERSION = "5.11.0";

    private final JunitLlmBackendClient backendClient;
    private final ProjectFileService fileService;
    private final UsedContextClassPathCache usedContextClassPathCache;
    private final JunitLlmConsoleLogger consoleLogger;

    public JunitLlmSessionRunner(
            JunitLlmBackendClient backendClient,
            ProjectFileService fileService,
            UsedContextClassPathCache usedContextClassPathCache,
            JunitLlmConsoleLogger consoleLogger
    ) {
        this.backendClient = backendClient;
        this.fileService = fileService;
        this.usedContextClassPathCache = usedContextClassPathCache;
        this.consoleLogger = consoleLogger;
    }

    public JunitLlmSessionResult run(JunitLlmSessionRequest sessionRequest) throws Exception {
        String cutCode = fileService.readFile(sessionRequest.cutPath());
        String cutName = fileService.stripJavaExtension(sessionRequest.cutPath().getFileName().toString());
        String testClassName = fileService.stripJavaExtension(sessionRequest.outputPath().getFileName().toString());
        Path cacheKeyPath = cacheKeyPath(sessionRequest);
        List<String> importedContextPaths = fileService.resolveImportedContextClassPaths(
                sessionRequest.projectRoot(),
                sessionRequest.cutPath()
        );
        List<String> cachedContextPaths = usedContextClassPathCache.read(cacheKeyPath);
        consoleLogger.announceCacheRead(cacheKeyPath, cachedContextPaths);

        String currentSessionId = blankToNull(sessionRequest.sessionId());
        String currentTestCode = normalizeNullableText(sessionRequest.newTestClassCode());
        List<String> requestedContextClasses = List.of();

        for (int interaction = 1; interaction <= MAX_INTERACTIONS; interaction++) {
            consoleLogger.announceStatus(sessionRequest.operation());
            InvokeJunitLlmRequest invokeRequest = new InvokeJunitLlmRequest(
                    currentSessionId,
                    sessionRequest.operation().apiValue(),
                    cutName,
                    testClassName,
                    DEFAULT_MOCKITO_VERSION,
                    cutCode,
                    buildCachedContextClasses(sessionRequest.projectRoot(), importedContextPaths, cachedContextPaths),
                    normalizeText(sessionRequest.initialTestClassCode()),
                    requestedContextClasses,
                    normalizeText(currentTestCode),
                    blankToNull(sessionRequest.clientLogs())
            );
            InvokeJunitLlmResponse invokeResponse = backendClient.invoke(invokeRequest);
            currentSessionId = firstNonBlank(invokeResponse.sessionId(), currentSessionId);

            FetchJobResponse fetchJobResponse = pollJob(invokeResponse.jobId());
            currentSessionId = mergeSessionId(currentSessionId, fetchJobResponse);

            FetchJobResponse.FetchJobOutput output = requireOutput(fetchJobResponse);
            List<String> usedContextClassPaths = output.usedContextClassPaths() == null
                    ? List.of()
                    : output.usedContextClassPaths();
            usedContextClassPathCache.write(cacheKeyPath, usedContextClassPaths);
            cachedContextPaths = usedContextClassPaths;
            if (output.finalTestFile() != null && !output.finalTestFile().isBlank()) {
                currentTestCode = output.finalTestFile();
            }

            List<String> requiredContextPaths = output.requiredContextClassPaths();
            if (requiredContextPaths != null && !requiredContextPaths.isEmpty()) {
                printContextPaths(requiredContextPaths);
                requestedContextClasses = fileService.readRequestedContextSources(
                        sessionRequest.projectRoot(),
                        sessionRequest.cutPath(),
                        requiredContextPaths
                );
                continue;
            }

            if (currentTestCode == null || currentTestCode.isBlank()) {
                throw new IllegalStateException("Backend did not return a test file.");
            }

            fileService.writeFile(sessionRequest.outputPath(), currentTestCode);
            return new JunitLlmSessionResult(
                    currentSessionId,
                    sessionRequest.outputPath(),
                    usedContextClassPaths
            );
        }

        throw new IllegalStateException("Exceeded the maximum number of backend interactions.");
    }

    private FetchJobResponse pollJob(String jobId) throws Exception {
        if (jobId == null || jobId.isBlank()) {
            throw new IllegalStateException("Backend did not return a job.");
        }
        for (int attempt = 1; attempt <= MAX_FETCH_ATTEMPTS; attempt++) {
            FetchJobResponse response = backendClient.fetchJob(jobId);
            String status = normalizeStatus(response.status());

            if (isDone(status)) {
                return response;
            }
            if (isFailure(status)) {
                throw new IllegalStateException(firstNonBlank(
                        response.errorMessage(),
                        "Backend job failed."
                ));
            }
            Thread.sleep(FETCH_DELAY_MILLIS);
        }
        throw new IllegalStateException("Timed out while waiting for backend response.");
    }

    private FetchJobResponse.FetchJobOutput requireOutput(FetchJobResponse response) {
        if (response.output() == null) {
            throw new IllegalStateException(firstNonBlank(
                    response.errorMessage(),
                    "Backend response was empty."
            ));
        }
        return response.output();
    }

    private String mergeSessionId(String currentSessionId, FetchJobResponse response) {
        if (response.output() == null) {
            return currentSessionId;
        }
        return firstNonBlank(response.output().sessionId(), currentSessionId);
    }

    private boolean isDone(String status) {
        return "done".equals(status) || "completed".equals(status) || "success".equals(status);
    }

    private boolean isFailure(String status) {
        return "error".equals(status) || "failed".equals(status) || "cancelled".equals(status);
    }

    private String normalizeStatus(String status) {
        return status == null ? "" : status.trim().toLowerCase();
    }

    private String normalizeText(String value) {
        return value == null ? "" : value;
    }

    private String normalizeNullableText(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value;
    }

    private String blankToNull(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value;
    }

    private String firstNonBlank(String primary, String fallback) {
        if (primary != null && !primary.isBlank()) {
            return primary;
        }
        if (fallback != null && !fallback.isBlank()) {
            return fallback;
        }
        return null;
    }

    private void printContextPaths(List<String> requiredContextPaths) {
        for (String requiredContextPath : requiredContextPaths) {
            consoleLogger.announceRequiredContextPath(requiredContextPath);
        }
    }

    private Path cacheKeyPath(JunitLlmSessionRequest sessionRequest) {
        return sessionRequest.cutPath().toAbsolutePath().normalize();
    }

    private List<String> buildCachedContextClasses(
            Path projectRoot,
            List<String> importedContextPaths,
            List<String> cachedContextPaths
    ) {
        Set<String> contextPaths = new LinkedHashSet<>(importedContextPaths);
        contextPaths.addAll(cachedContextPaths);
        return fileService.readCachedContextEntries(projectRoot, List.copyOf(contextPaths));
    }
}
