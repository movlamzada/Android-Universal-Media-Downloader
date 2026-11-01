package org.gradle.wrapper;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Properties;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Small self-bootstrapping Gradle launcher for this source bundle.
 *
 * The official Gradle distribution is downloaded from distributionUrl and checked
 * against distributionSha256Sum before extraction. This is intentionally minimal:
 * it does not implement all optional features of Gradle's official wrapper.
 */
public final class GradleWrapperMain {
    private GradleWrapperMain() { }

    public static void main(String[] args) {
        try {
            Path projectDir = findProjectDir();
            Path propertiesFile = projectDir.resolve("gradle/wrapper/gradle-wrapper.properties");
            if (!Files.isRegularFile(propertiesFile)) {
                throw new IOException("Cannot locate " + propertiesFile);
            }

            Properties properties = new Properties();
            try (InputStream input = Files.newInputStream(propertiesFile)) {
                properties.load(input);
            }

            String distributionUrl = required(properties, "distributionUrl");
            String expectedSha = required(properties, "distributionSha256Sum").trim().toLowerCase();
            if (!expectedSha.matches("[0-9a-f]{64}")) {
                throw new IOException("Invalid distributionSha256Sum in gradle-wrapper.properties");
            }

            Path gradleUserHome = gradleUserHome();
            String distributionName = distributionFolderName(distributionUrl);
            Path distributionRoot = gradleUserHome.resolve("wrapper/dists/universal-media-downloader")
                    .resolve(distributionName);
            String gradleVersionFolder = distributionName.endsWith("-bin")
                    ? distributionName.substring(0, distributionName.length() - 4)
                    : distributionName;
            Path gradleHome = distributionRoot.resolve(gradleVersionFolder);
            Path gradleExecutable = gradleHome.resolve(isWindows() ? "bin/gradle.bat" : "bin/gradle");
            Path verifiedMarker = distributionRoot.resolve("distribution.sha256");

            if (!Files.isRegularFile(gradleExecutable)
                    || !Files.isRegularFile(verifiedMarker)
                    || !Files.readString(verifiedMarker).trim().equals(expectedSha)) {
                Files.createDirectories(distributionRoot);
                deleteRecursively(gradleHome);
                Files.deleteIfExists(verifiedMarker);
                installDistribution(distributionUrl, expectedSha, distributionRoot, gradleHome);
                Files.writeString(verifiedMarker, expectedSha + System.lineSeparator());
            }

            if (!isWindows()) {
                gradleExecutable.toFile().setExecutable(true, true);
            }

            List<String> command = new ArrayList<>();
            if (isWindows()) {
                command.add("cmd.exe");
                command.add("/d");
                command.add("/c");
                command.add("call");
            }
            command.add(gradleExecutable.toAbsolutePath().toString());
            command.addAll(List.of(args));

            Process process = new ProcessBuilder(command)
                    .directory(projectDir.toFile())
                    .inheritIO()
                    .start();
            System.exit(process.waitFor());
        } catch (Exception error) {
            System.err.println("Gradle bootstrap failed: " + error.getMessage());
            System.err.println("Check internet access, JDK installation and gradle/wrapper/gradle-wrapper.properties.");
            System.exit(1);
        }
    }

    private static Path findProjectDir() throws IOException {
        String configured = System.getProperty("org.gradle.wrapper.projectDir");
        if (configured != null && !configured.isBlank()) {
            Path path = Path.of(configured).toAbsolutePath().normalize();
            if (Files.isRegularFile(path.resolve("gradle/wrapper/gradle-wrapper.properties"))) return path;
        }
        Path current = Path.of(System.getProperty("user.dir")).toAbsolutePath().normalize();
        for (Path path = current; path != null; path = path.getParent()) {
            if (Files.isRegularFile(path.resolve("gradle/wrapper/gradle-wrapper.properties"))) return path;
        }
        throw new IOException("Run this launcher from the project folder or a subfolder.");
    }

    private static Path gradleUserHome() {
        String configured = System.getenv("GRADLE_USER_HOME");
        if (configured == null || configured.isBlank()) configured = System.getProperty("user.home") + "/.gradle";
        return Path.of(configured).toAbsolutePath().normalize();
    }

    private static String required(Properties properties, String key) throws IOException {
        String value = properties.getProperty(key);
        if (value == null || value.isBlank()) throw new IOException("Missing " + key);
        return value.trim();
    }

    private static String distributionFolderName(String distributionUrl) throws Exception {
        String path = URI.create(distributionUrl.replace("\\:", ":")).getPath();
        String fileName = path.substring(path.lastIndexOf('/') + 1);
        if (!fileName.endsWith(".zip")) throw new IOException("distributionUrl must reference a ZIP archive");
        return fileName.substring(0, fileName.length() - 4);
    }

    private static void installDistribution(String distributionUrl, String expectedSha,
                                            Path installRoot, Path gradleHome) throws Exception {
        Path archive = installRoot.resolve("gradle-distribution.zip.part");
        Path extractRoot = installRoot.resolve("extract-tmp");
        deleteRecursively(extractRoot);
        Files.createDirectories(installRoot);
        System.out.println("Downloading the pinned Gradle distribution (first build only)...");

        HttpURLConnection connection = (HttpURLConnection) new URL(distributionUrl.replace("\\:", ":")).openConnection();
        connection.setInstanceFollowRedirects(true);
        connection.setConnectTimeout(30_000);
        connection.setReadTimeout(90_000);
        connection.setRequestProperty("User-Agent", "UniversalMediaDownloader-GradleBootstrap/1.0");
        connection.connect();
        int code = connection.getResponseCode();
        if (code < 200 || code >= 300) throw new IOException("Gradle download returned HTTP " + code);
        try (InputStream input = new BufferedInputStream(connection.getInputStream())) {
            Files.copy(input, archive, StandardCopyOption.REPLACE_EXISTING);
        } finally {
            connection.disconnect();
        }

        String actualSha = sha256(archive);
        if (!actualSha.equals(expectedSha)) {
            Files.deleteIfExists(archive);
            throw new IOException("Gradle distribution checksum mismatch. Expected " + expectedSha + " but received " + actualSha);
        }

        Files.createDirectories(extractRoot);
        unzipSafely(archive, extractRoot);
        Files.deleteIfExists(archive);
        Path extractedHome = extractRoot.resolve(gradleHome.getFileName());
        if (!Files.isDirectory(extractedHome)) {
            throw new IOException("The Gradle ZIP did not contain " + gradleHome.getFileName());
        }
        try {
            Files.move(extractedHome, gradleHome, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException ignored) {
            Files.move(extractedHome, gradleHome, StandardCopyOption.REPLACE_EXISTING);
        }
        deleteRecursively(extractRoot);
        System.out.println("Gradle " + gradleHome.getFileName().toString().replace("gradle-", "") + " is ready.");
    }

    private static String sha256(Path file) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (InputStream input = new BufferedInputStream(Files.newInputStream(file))) {
            byte[] buffer = new byte[1024 * 1024];
            int count;
            while ((count = input.read(buffer)) != -1) digest.update(buffer, 0, count);
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    private static void unzipSafely(Path archive, Path destination) throws IOException {
        Path root = destination.toAbsolutePath().normalize();
        try (ZipInputStream zip = new ZipInputStream(new BufferedInputStream(Files.newInputStream(archive)))) {
            ZipEntry entry;
            byte[] buffer = new byte[64 * 1024];
            while ((entry = zip.getNextEntry()) != null) {
                Path target = root.resolve(entry.getName()).normalize();
                if (!target.startsWith(root)) throw new IOException("Unsafe path in Gradle archive: " + entry.getName());
                if (entry.isDirectory()) {
                    Files.createDirectories(target);
                } else {
                    Path parent = target.getParent();
                    if (parent != null) Files.createDirectories(parent);
                    try (BufferedOutputStream output = new BufferedOutputStream(Files.newOutputStream(target))) {
                        int count;
                        while ((count = zip.read(buffer)) != -1) output.write(buffer, 0, count);
                    }
                }
                zip.closeEntry();
            }
        }
    }

    private static void deleteRecursively(Path root) throws IOException {
        if (!Files.exists(root)) return;
        try (var paths = Files.walk(root)) {
            paths.sorted((left, right) -> right.getNameCount() - left.getNameCount())
                    .forEach(path -> {
                        try { Files.deleteIfExists(path); }
                        catch (IOException error) { throw new RuntimeException(error); }
                    });
        } catch (RuntimeException error) {
            if (error.getCause() instanceof IOException io) throw io;
            throw error;
        }
    }

    private static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase().contains("win");
    }
}
