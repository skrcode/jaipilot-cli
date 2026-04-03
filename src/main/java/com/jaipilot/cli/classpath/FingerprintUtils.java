package com.jaipilot.cli.classpath;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;

final class FingerprintUtils {

    private FingerprintUtils() {
    }

    static String sha256Hex(List<String> lines) {
        MessageDigest digest = newDigest();
        for (String line : lines) {
            String normalized = line == null ? "" : line;
            digest.update(normalized.getBytes(StandardCharsets.UTF_8));
            digest.update((byte) '\n');
        }
        return toHex(digest.digest());
    }

    static String fileHash(Path file) {
        if (file == null || !Files.isRegularFile(file)) {
            return "missing";
        }
        MessageDigest digest = newDigest();
        try {
            digest.update(Files.readAllBytes(file));
            return toHex(digest.digest());
        } catch (IOException exception) {
            return "unreadable:" + exception.getClass().getSimpleName();
        }
    }

    static List<Path> sortedFiles(Path directory) {
        if (directory == null || !Files.isDirectory(directory)) {
            return List.of();
        }
        try (Stream<Path> paths = Files.walk(directory)) {
            return paths
                    .filter(Files::isRegularFile)
                    .map(Path::toAbsolutePath)
                    .map(Path::normalize)
                    .sorted(Comparator.comparing(path -> path.toString().toLowerCase(Locale.ROOT)))
                    .toList();
        } catch (IOException ignored) {
            return List.of();
        }
    }

    static String normalize(Path path) {
        if (path == null) {
            return "";
        }
        return path.toAbsolutePath().normalize().toString();
    }

    private static MessageDigest newDigest() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private static String toHex(byte[] bytes) {
        StringBuilder builder = new StringBuilder(bytes.length * 2);
        for (byte current : bytes) {
            builder.append(Character.forDigit((current >> 4) & 0xF, 16));
            builder.append(Character.forDigit(current & 0xF, 16));
        }
        return builder.toString();
    }
}
