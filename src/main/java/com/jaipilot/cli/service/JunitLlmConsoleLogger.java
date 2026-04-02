package com.jaipilot.cli.service;

import com.jaipilot.cli.model.JunitLlmOperation;
import java.io.PrintWriter;
import java.nio.file.Path;
import java.time.Duration;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

public final class JunitLlmConsoleLogger {

    private static final DateTimeFormatter TIMESTAMP_FORMAT = DateTimeFormatter.ofPattern("HH:mm:ss");
    private static final String ANSI_GREEN = "\u001B[32m";
    private static final String ANSI_RED = "\u001B[31m";
    private static final String ANSI_RESET = "\u001B[0m";

    private final PrintWriter writer;

    public JunitLlmConsoleLogger(PrintWriter writer) {
        this.writer = writer;
    }

    public void announceStatus(JunitLlmOperation operation) {
        info("Maximizing coverage...");
    }

    public void announceCacheRead(Path filePath, List<String> cachedContextPaths) {
        info("Reading cached context for " + filePath);
    }

    public void announceRequiredContextPath(String contextPath) {
        info("Context file: " + contextPath);
    }

    public void announceTestFileDiff(String previousContent, String currentContent) {
        info("Source diff:");
        List<DiffLine> diffLines = buildDiffLines(previousContent, currentContent);
        if (diffLines.isEmpty()) {
            info("No file changes.");
            return;
        }
        for (DiffLine diffLine : diffLines) {
            if (diffLine.type() == DiffType.ADD) {
                raw(colorize(ANSI_GREEN, "+ " + diffLine.value()));
            } else if (diffLine.type() == DiffType.REMOVE) {
                raw(colorize(ANSI_RED, "- " + diffLine.value()));
            }
        }
    }

    public void announceTestFile(Path outputPath) {
        info("Test file: " + outputPath);
    }

    public void announceTotalTime(Duration duration) {
        info("Total time: " + formatDuration(duration));
    }

    public void error(String message) {
        info("ERROR: " + message);
    }

    public void info(String message) {
        raw(message);
    }

    private void raw(String message) {
        writer.println("[" + LocalTime.now().format(TIMESTAMP_FORMAT) + "] " + message);
        writer.flush();
    }

    private String formatDuration(Duration duration) {
        long totalMillis = duration.toMillis();
        if (totalMillis < 1_000) {
            return totalMillis + "ms";
        }
        return String.format("%.1fs", totalMillis / 1_000.0);
    }

    private String colorize(String color, String message) {
        return color + message + ANSI_RESET;
    }

    private List<DiffLine> buildDiffLines(String previousContent, String currentContent) {
        List<String> previousLines = toLines(previousContent);
        List<String> currentLines = toLines(currentContent);
        int[][] lcsLengths = buildLcsLengths(previousLines, currentLines);
        List<DiffLine> diffLines = new ArrayList<>();

        int previousIndex = 0;
        int currentIndex = 0;
        while (previousIndex < previousLines.size() && currentIndex < currentLines.size()) {
            String previousLine = previousLines.get(previousIndex);
            String currentLine = currentLines.get(currentIndex);
            if (previousLine.equals(currentLine)) {
                previousIndex++;
                currentIndex++;
                continue;
            }
            if (lcsLengths[previousIndex + 1][currentIndex] >= lcsLengths[previousIndex][currentIndex + 1]) {
                diffLines.add(new DiffLine(DiffType.REMOVE, previousLine));
                previousIndex++;
            } else {
                diffLines.add(new DiffLine(DiffType.ADD, currentLine));
                currentIndex++;
            }
        }

        while (previousIndex < previousLines.size()) {
            diffLines.add(new DiffLine(DiffType.REMOVE, previousLines.get(previousIndex++)));
        }
        while (currentIndex < currentLines.size()) {
            diffLines.add(new DiffLine(DiffType.ADD, currentLines.get(currentIndex++)));
        }
        return diffLines;
    }

    private int[][] buildLcsLengths(List<String> previousLines, List<String> currentLines) {
        int[][] lcsLengths = new int[previousLines.size() + 1][currentLines.size() + 1];
        for (int previousIndex = previousLines.size() - 1; previousIndex >= 0; previousIndex--) {
            for (int currentIndex = currentLines.size() - 1; currentIndex >= 0; currentIndex--) {
                if (previousLines.get(previousIndex).equals(currentLines.get(currentIndex))) {
                    lcsLengths[previousIndex][currentIndex] = lcsLengths[previousIndex + 1][currentIndex + 1] + 1;
                } else {
                    lcsLengths[previousIndex][currentIndex] = Math.max(
                            lcsLengths[previousIndex + 1][currentIndex],
                            lcsLengths[previousIndex][currentIndex + 1]
                    );
                }
            }
        }
        return lcsLengths;
    }

    private List<String> toLines(String content) {
        if (content == null || content.isEmpty()) {
            return List.of();
        }
        String normalizedContent = content.replace("\r\n", "\n").replace('\r', '\n');
        List<String> lines = new ArrayList<>(List.of(normalizedContent.split("\n", -1)));
        if (!lines.isEmpty() && lines.get(lines.size() - 1).isEmpty()) {
            lines.remove(lines.size() - 1);
        }
        return lines;
    }

    private enum DiffType {
        ADD,
        REMOVE
    }

    private record DiffLine(DiffType type, String value) {
    }
}
