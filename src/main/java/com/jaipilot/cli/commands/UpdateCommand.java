package com.jaipilot.cli.commands;

import com.jaipilot.cli.update.UpdateCheckResult;
import com.jaipilot.cli.update.UpdateInstallResult;
import com.jaipilot.cli.update.UpdateService;
import java.io.PrintWriter;
import java.time.Duration;
import java.util.concurrent.Callable;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Option;
import picocli.CommandLine.Spec;

@Command(
        name = "update",
        mixinStandardHelpOptions = true,
        description = "Check for and install JAIPilot CLI updates."
)
public final class UpdateCommand implements Callable<Integer> {

    @Option(
            names = "--check",
            description = "Check for the latest release without installing it."
    )
    private boolean checkOnly;

    @Option(
            names = "--version",
            paramLabel = "<version>",
            description = "Install a specific version instead of the latest release."
    )
    private String requestedVersion;

    @Option(
            names = "--enable-auto-updates",
            description = "Enable automatic startup installs when a new release is available."
    )
    private boolean enableAutoUpdates;

    @Option(
            names = "--disable-auto-updates",
            description = "Disable automatic startup installs."
    )
    private boolean disableAutoUpdates;

    @Option(
            names = "--status",
            description = "Show the current update configuration without checking the network."
    )
    private boolean statusOnly;

    @Option(
            names = "--timeout-seconds",
            defaultValue = "10",
            paramLabel = "<seconds>",
            description = "Maximum time to wait for network operations. Default: ${DEFAULT-VALUE}."
    )
    private long timeoutSeconds;

    @Spec
    private CommandSpec spec;

    private final UpdateService updateService;

    public UpdateCommand() {
        this(new UpdateService());
    }

    public UpdateCommand(UpdateService updateService) {
        this.updateService = updateService;
    }

    @Override
    public Integer call() {
        if (timeoutSeconds < 1) {
            throw new CommandLine.ParameterException(spec.commandLine(), "--timeout-seconds must be greater than zero.");
        }
        if (enableAutoUpdates && disableAutoUpdates) {
            throw new CommandLine.ParameterException(
                    spec.commandLine(),
                    "--enable-auto-updates and --disable-auto-updates cannot be used together."
            );
        }

        PrintWriter out = spec.commandLine().getOut();
        boolean settingsChanged = false;
        if (enableAutoUpdates) {
            updateService.setAutoUpdates(true);
            settingsChanged = true;
            out.println("Automatic startup updates: enabled");
        } else if (disableAutoUpdates) {
            updateService.setAutoUpdates(false);
            settingsChanged = true;
            out.println("Automatic startup updates: disabled");
        }

        if (statusOnly) {
            printStatus(out);
            return CommandLine.ExitCode.OK;
        }

        if (checkOnly) {
            UpdateCheckResult result = updateService.checkForUpdates(Duration.ofSeconds(timeoutSeconds));
            printCheckResult(out, result);
            return result.updateAvailable() ? 1 : CommandLine.ExitCode.OK;
        }

        if (settingsChanged && requestedVersion == null) {
            printStatus(out);
            return CommandLine.ExitCode.OK;
        }

        try {
            UpdateInstallResult result = requestedVersion == null
                    ? updateService.installLatest(Duration.ofSeconds(timeoutSeconds))
                    : updateService.installVersion(requestedVersion, Duration.ofSeconds(timeoutSeconds));
            if (result.changed()) {
                out.println("Updated JAIPilot from " + result.previousVersion() + " to " + result.installedVersion() + ".");
                out.println("Install root: " + result.installRoot());
                out.println("Future invocations will use the new version.");
            } else {
                out.println("JAIPilot is already up to date at " + result.installedVersion() + ".");
                out.println("Install root: " + result.installRoot());
            }
            out.flush();
            return CommandLine.ExitCode.OK;
        } catch (IllegalArgumentException | IllegalStateException exception) {
            out.println(exception.getMessage());
            out.flush();
            return CommandLine.ExitCode.SOFTWARE;
        }
    }

    private void printStatus(PrintWriter out) {
        out.println("Current version: " + com.jaipilot.cli.JaiPilotVersionProvider.resolveVersion());
        out.println("Managed install: " + (updateService.hasManagedInstallation() ? "yes" : "no"));
        updateService.installRoot().ifPresent(path -> out.println("Install root: " + path));
        out.println("Automatic startup updates: " + (updateService.autoUpdatesEnabled() ? "enabled" : "disabled"));
        out.println("Settings file: " + updateService.settingsPath());
        out.flush();
    }

    private void printCheckResult(PrintWriter out, UpdateCheckResult result) {
        out.println("Current version: " + result.currentVersion());
        out.println("Latest version: " + result.latestVersion());
        out.println("Managed install: " + (result.managedInstall() ? "yes" : "no"));
        if (result.installRoot() != null) {
            out.println("Install root: " + result.installRoot());
        }
        out.println("Automatic startup updates: " + (result.autoUpdatesEnabled() ? "enabled" : "disabled"));
        out.println(result.updateAvailable()
                ? "Status: update available"
                : "Status: up to date");
        out.flush();
    }
}
