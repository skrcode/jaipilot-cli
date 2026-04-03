package com.jaipilot.cli.util;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

public final class SensitiveDataRedactor {

    private static final int MAX_BACKEND_LINES = 24;
    private static final int MAX_BACKEND_CHARS = 3_500;
    private static final int MAX_LINE_LENGTH = 220;
    private static final String NO_ERROR_LINES_MESSAGE = "Build failed but no explicit error lines were captured.";
    private static final List<RedactionRule> REDACTION_RULES = List.of(
            rule("(?i)(Authorization\\s*:\\s*Bearer\\s+)([^\\s]+)", "$1[REDACTED]"),
            rule("(?i)(Bearer\\s+)([A-Za-z0-9._~+/=-]+)", "$1[REDACTED]"),
            rule("(?i)(https?://[^\\s:/?#]+:)([^@\\s/]+)(@)", "$1[REDACTED]$3"),
            rule(
                    "(?i)(--(?:api-key|api_key|token|password|passwd|secret|access-token|refresh-token|jwt-token|authorization))(\\s+)([^\\s]+)",
                    "$1$2[REDACTED]"
            ),
            rule(
                    "(?i)(--(?:api-key|api_key|token|password|passwd|secret|access-token|refresh-token|jwt-token|authorization)=)([^\\s]+)",
                    "$1[REDACTED]"
            ),
            rule(
                    "(?i)(-D[^=\\s]*(?:password|passwd|secret|token|api[_-]?key|access[_-]?token|refresh[_-]?token|authorization|jwt)[^=\\s]*=)([^\\s]+)",
                    "$1[REDACTED]"
            ),
            rule(
                    "(?im)(\\b[A-Z0-9_]*(?:TOKEN|PASSWORD|PASSWD|SECRET|API[_-]?KEY|ACCESS[_-]?TOKEN|REFRESH[_-]?TOKEN|JWT)[A-Z0-9_]*=)(\\S+)",
                    "$1[REDACTED]"
            ),
            rule(
                    "(?i)((?:\"|\\b)(?:access_token|refresh_token|api_key|apikey|password|passwd|secret|jwt|jwt_token)(?:\"|\\b)\\s*[:=]\\s*\"?)([^\"\\s,}]+)",
                    "$1[REDACTED]"
            )
    );
    private static final List<Pattern> SIGNAL_PATTERNS = List.of(
            Pattern.compile("(?i)^\\s*\\[error\\]"),
            Pattern.compile("(?i)^\\s*> task .+ failed"),
            Pattern.compile("(?i)^\\s*tests run:\\s*\\d+"),
            Pattern.compile("(?i)^\\s*there were failing tests"),
            Pattern.compile("(?i)^\\s*compilation failure"),
            Pattern.compile("(?i)^\\s*caused by:"),
            Pattern.compile("(?i)\\b(error|errors|failure|failures|failed|exception|assertion|cannot find symbol|package .+ does not exist|symbol:|location:|expected:|but was:|wanted but not invoked|comparison failure|initializationerror|no tests found|timed out)\\b"),
            Pattern.compile("^\\s*\\^\\s*$")
    );
    private static final Pattern STACK_TRACE_LINE = Pattern.compile("^\\s*at\\s+.+");
    private static final Pattern OMITTED_STACK_TRACE_LINE = Pattern.compile("^\\s*\\.\\.\\. \\d+ more\\s*$");

    private SensitiveDataRedactor() {
    }

    public static String redact(String value) {
        if (value == null || value.isBlank()) {
            return value;
        }

        String redacted = value;
        for (RedactionRule redactionRule : REDACTION_RULES) {
            redacted = redactionRule.pattern().matcher(redacted).replaceAll(redactionRule.replacement());
        }
        return redacted;
    }

    public static String redactCommand(List<String> command) {
        if (command == null || command.isEmpty()) {
            return "";
        }
        return redact(String.join(" ", command));
    }

    public static String redactBuildOutput(String value) {
        String redacted = redact(value);
        if (redacted == null || redacted.isBlank()) {
            return redacted;
        }

        List<String> lines = redacted.lines()
                .map(String::stripTrailing)
                .filter(line -> !line.isBlank())
                .toList();
        if (lines.isEmpty()) {
            return "";
        }

        List<String> excerpt = new ArrayList<>();
        int stackTraceLinesOmitted = 0;
        int contextLinesRemaining = 0;

        for (String line : lines) {
            if (isStackTraceNoise(line)) {
                stackTraceLinesOmitted++;
                continue;
            }
            if (isWarningLine(line)) {
                continue;
            }

            boolean signalLine = isSignalLine(line);
            if (signalLine) {
                addLine(excerpt, line);
                contextLinesRemaining = 2;
                continue;
            }

            if (contextLinesRemaining > 0 && !isLowSignalLine(line) && !isWarningLine(line)) {
                addLine(excerpt, line);
                contextLinesRemaining--;
            }
        }

        if (excerpt.isEmpty()) {
            for (String line : lines) {
                if (isStackTraceNoise(line) || isWarningLine(line) || !isSignalLine(line)) {
                    continue;
                }
                addLine(excerpt, line);
            }
        }

        if (excerpt.isEmpty()) {
            return NO_ERROR_LINES_MESSAGE;
        }

        if (stackTraceLinesOmitted > 0) {
            excerpt.add("[stack trace trimmed: " + stackTraceLinesOmitted + " lines omitted]");
        }

        return truncate(String.join(System.lineSeparator(), excerpt), MAX_BACKEND_CHARS);
    }

    private static RedactionRule rule(String regex, String replacement) {
        return new RedactionRule(Pattern.compile(regex), replacement);
    }

    private static boolean isSignalLine(String line) {
        for (Pattern pattern : SIGNAL_PATTERNS) {
            if (pattern.matcher(line).find()) {
                return true;
            }
        }
        return false;
    }

    private static boolean isStackTraceNoise(String line) {
        return STACK_TRACE_LINE.matcher(line).matches() || OMITTED_STACK_TRACE_LINE.matcher(line).matches();
    }

    private static boolean isWarningLine(String line) {
        if (line == null) {
            return false;
        }
        String normalized = line.trim().toLowerCase();
        return normalized.startsWith("[warning]")
                || normalized.startsWith("warning:")
                || normalized.contains(" warning:");
    }

    private static boolean isLowSignalLine(String line) {
        String normalized = line.trim();
        return normalized.startsWith("[INFO]")
                || normalized.startsWith("[DEBUG]")
                || normalized.startsWith("[WARNING]")
                || normalized.startsWith("Downloaded from ")
                || normalized.startsWith("Downloading from ")
                || normalized.startsWith("BUILD SUCCESS");
    }

    private static void addLine(List<String> lines, String line) {
        if (lines.size() >= MAX_BACKEND_LINES) {
            return;
        }
        String normalized = normalizeLineLength(line);
        if (!lines.isEmpty() && lines.get(lines.size() - 1).equals(normalized)) {
            return;
        }
        lines.add(normalized);
    }

    private static String normalizeLineLength(String line) {
        if (line.length() <= MAX_LINE_LENGTH) {
            return line;
        }
        return line.substring(0, MAX_LINE_LENGTH - 12) + "... [trimmed]";
    }

    private static String truncate(String value, int maxChars) {
        if (value.length() <= maxChars) {
            return value;
        }
        return value.substring(0, maxChars - 14) + "\n[log trimmed]";
    }

    private record RedactionRule(Pattern pattern, String replacement) {
    }
}
