package com.jaipilot.cli;

import picocli.CommandLine.IVersionProvider;

public final class JaiPilotVersionProvider implements IVersionProvider {

    @Override
    public String[] getVersion() {
        return new String[] {resolveVersion()};
    }

    public static String resolveVersion() {
        Package commandPackage = JaiPilotCli.class.getPackage();
        String version = commandPackage == null ? null : commandPackage.getImplementationVersion();
        if (version == null || version.isBlank()) {
            version = "0.3.17";
        }
        return version;
    }
}
