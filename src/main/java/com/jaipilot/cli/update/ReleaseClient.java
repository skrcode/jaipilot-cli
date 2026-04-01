package com.jaipilot.cli.update;

import java.nio.file.Path;
import java.time.Duration;

public interface ReleaseClient {

    String fetchLatestVersion(Duration timeout);

    java.net.URI archiveUri(String version, String platform);

    java.net.URI checksumUri(String version, String platform);

    void download(java.net.URI source, Path destination, Duration timeout);
}
