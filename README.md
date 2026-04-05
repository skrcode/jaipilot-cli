<div align="center">
  <h1>JAIPilot - Autogenerate High Coverage Java Unit Tests</h1>
  <p><strong>JAIPilot automatically writes high quality unit tests for your PR to achieve high coverage for Java codebases.</strong></p>
  <p>
    <a href="https://github.com/JAIPilot/jaipilot-cli/actions/workflows/ci.yml">
      <img src="https://github.com/JAIPilot/jaipilot-cli/actions/workflows/ci.yml/badge.svg?branch=main" alt="CI">
    </a>
    <a href="https://github.com/JAIPilot/jaipilot-cli/releases">
      <img src="https://img.shields.io/github/v/release/JAIPilot/jaipilot-cli" alt="Release">
    </a>
    <a href="LICENSE">
      <img src="https://img.shields.io/github/license/JAIPilot/jaipilot-cli" alt="License">
    </a>
  </p>
  <p>
    <a href="#install"><strong>Install</strong></a>
    ·
    <a href="#quick-start"><strong>Quick Start</strong></a>
    ·
    <a href="#how-it-works"><strong>How It Works</strong></a>
  </p>
</div>

<p align="center">
  JAIPilot automatically writes high quality unit tests for your PR to achieve high coverage for Java codebases.
</p>

<hr />

JAIPilot automatically generates high quality high coverage unit tests for PRs for your Java codebase.

## Why JAIPilot

- Automatically generates high quality high coverage unit tests for PRs for your Java codebase
- All generated tests are fully compilable, executable, and maximize line coverage
- Analyzes changed Java code and context for every PR to generate high quality meaningful tests
- Builds, executes, and maximizes line coverage

## GitHub Action (PR Automation)

To run JAIPilot automatically on pull requests with this action, you must provide your JAIPilot license key to the workflow.
Get the key by logging in at `https://jaipilot.com` (free credits are available).

1. Go to your repository `Settings` -> `Secrets and variables` -> `Actions`.
2. Create a repository secret (for example `JAIPILOT_LICENSE_KEY`) and paste your JAIPilot license key as the value.
3. Reference that secret in your workflow input/env for the JAIPilot action.

Without a valid license key configured in the action, JAIPilot will not auto-execute on PRs.

## Install

Install with:

```sh
curl -fsSL https://jaipilot.com/install.sh | bash
```

That installs `jaipilot` into `~/.local/bin` by default, downloads the platform-specific release archive for your machine, and verifies the release archive SHA-256 checksum before unpacking it.

Bundled-runtime releases target:

- `linux-x64`
- `linux-aarch64`
- `macos-x64`
- `macos-aarch64`

Make sure `~/.local/bin` is on your `PATH`.

## Usage Options

You can use JAIPilot in either of these ways:

- JAIPilot CLI (run locally with `jaipilot` commands)
- GitHub Action (run automatically on pull requests in GitHub)

## Quick Start

Authenticate once:

```sh
jaipilot login
```

Get your license key by logging in at `https://jaipilot.com` (free credits are available).

Generate a JUnit test for a class:

```sh
jaipilot generate src/main/java/org/example/CrashController.java
```

Use a specific build executable:

```sh
jaipilot generate src/main/java/org/example/CrashController.java --build-executable /path/to/mvn
```

Pass extra build arguments when needed:

```sh
jaipilot generate src/main/java/org/example/CrashController.java --build-arg -DskipITs
```

## Commands

Authentication commands:

- `jaipilot login` starts the browser flow and stores credentials in `~/.config/jaipilot/credentials.json`.
- `jaipilot status` shows the current signed-in user and refreshes the access token if needed.
- `jaipilot logout` clears the stored session.

Generation commands:

- `jaipilot generate <path-to-class>` generates or updates a corresponding test file.

## How It Works

`jaipilot generate` reads local source files, calls the backend generation API, polls for completion, writes the returned test file, and validates it with your build tool in three stages: compile, codebase rules, and targeted test execution (`test-compile`/`verify`/targeted `test` for Maven, `testClasses`/`check`/targeted `test --tests` for Gradle). Rule validation is run with full-suite test execution skipped because JAIPilot already runs targeted test validation separately.

If validation fails, JAIPilot automatically performs iterative fixing passes using build failure logs. When required context classes are missing from local sources, JAIPilot can trigger dependency source download and retry.

For Maven wrapper usage, JAIPilot only uses wrapper scripts when `.mvn/wrapper/maven-wrapper.properties` exists; otherwise it falls back to system `mvn`/`mvn.cmd`.

## License

[MIT](LICENSE)
