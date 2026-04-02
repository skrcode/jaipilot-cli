package com.jaipilot.cli;

import com.jaipilot.cli.commands.GenerateCommand;
import com.jaipilot.cli.commands.LoginCommand;
import com.jaipilot.cli.commands.LogoutCommand;
import com.jaipilot.cli.commands.StatusCommand;
import com.jaipilot.cli.commands.UpdateCommand;
import com.jaipilot.cli.update.UpdateBootstrap;
import java.io.PrintWriter;
import java.util.concurrent.Callable;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Spec;
import picocli.CommandLine.Model.CommandSpec;

@Command(
        name = "jaipilot",
        mixinStandardHelpOptions = true,
        versionProvider = JaiPilotVersionProvider.class,
        description = "Runs backend-assisted JUnit generation workflows.",
        subcommands = {
            LoginCommand.class,
            LogoutCommand.class,
            StatusCommand.class,
            UpdateCommand.class,
            GenerateCommand.class
        }
)
public final class JaiPilotCli implements Callable<Integer> {

    @Spec
    private CommandSpec spec;

    public static void main(String[] args) {
        UpdateBootstrap.maybeRun(args, new PrintWriter(System.err, true));
        int exitCode = new CommandLine(new JaiPilotCli()).execute(args);
        System.exit(exitCode);
    }

    @Override
    public Integer call() {
        spec.commandLine().usage(spec.commandLine().getOut());
        return CommandLine.ExitCode.OK;
    }
}
