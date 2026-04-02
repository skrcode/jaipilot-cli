<div align="center">
  <h1>JAIPilot CLI</h1>
  <p><strong>Generate and validate Java tests with an agent-friendly loop.</strong></p>
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
    ·
    <a href="#development"><strong>Development</strong></a>
  </p>
</div>

<p align="center">
  JAIPilot is a Java CLI for backend-assisted JUnit generation. It writes tests, validates them with your local build tool, and can run iterative fixing passes when the first generated test fails to compile or pass.
</p>

<hr />

JAIPilot is designed for fast iteration with coding agents such as Claude Code, Codex, and Cursor. Agents can generate tests, inspect build feedback, apply fixes, and repeat until tests are stable.

## Why JAIPilot

- Backend-assisted JUnit test generation
- Local Maven/Gradle validation after generation
- Automatic dependency source download for missing imported context classes
- Iterative fixing loop when generated tests fail build/test validation
- Actionable logs for agent workflows

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

## Update

Update to the latest release with:

```sh
jaipilot update
```

Check without installing:

```sh
jaipilot update --check
```

Enable automatic startup installs when a new release is available:

```sh
jaipilot update --enable-auto-updates
```

Disable them again with:

```sh
jaipilot update --disable-auto-updates
```

JAIPilot checks for updates on startup for installer-managed installs. Set `JAIPILOT_DISABLE_UPDATE_CHECK=true` to skip startup checks entirely.

## Quick Start

Authenticate once:

```sh
jaipilot login
```

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

## Agent Workflow

1. Ask your agent to generate tests with `jaipilot generate`.
2. Let JAIPilot run local compile/test validation and stream logs.
3. If validation fails, JAIPilot sends build feedback back to the backend as a `fix` pass and retries.
4. Repeat generation for other classes.

Example prompt:

```text
Use `jaipilot generate` for the touched classes and keep iterating until local tests pass.
```

## Commands

Authentication commands:

- `jaipilot login` starts the browser flow and stores credentials in `~/.config/jaipilot/credentials.json`.
- `jaipilot status` shows the current signed-in user and refreshes the access token if needed.
- `jaipilot logout` clears the stored session.

Generation commands:

- `jaipilot generate <path-to-class>` generates or updates a corresponding test file.

Common options:

- `JAIPILOT_JWT_TOKEN` can be used instead of a stored login session.
- `--output` overrides the inferred test file path.
- `--build-executable`, `--build-arg`, and `--timeout-seconds` control the local validation phase.

## How It Works

`jaipilot generate` reads local source files, calls the backend generation API, polls for completion, writes the returned test file, and validates it with your build tool (`test-compile`/targeted `test` for Maven, `testClasses`/targeted `test --tests` for Gradle).

If validation fails, JAIPilot automatically performs iterative fixing passes using build failure logs. When required context classes are missing from local sources, JAIPilot can trigger dependency source download and retry.

For Maven wrapper usage, JAIPilot only uses wrapper scripts when `.mvn/wrapper/maven-wrapper.properties` exists; otherwise it falls back to system `mvn`/`mvn.cmd`.

## Requirements

- Java 17+
- A Maven or Gradle project
- JUnit 4 or JUnit 5 tests
- Maven available via `./mvnw` or `mvn`, or Gradle available via `./gradlew` or `gradle`
- A JAIPilot login session or a valid `JAIPILOT_JWT_TOKEN` for backend-assisted generation

## Development

Build and test locally:

```sh
./mvnw -B test
```

Smoke-test the install path:

```sh
./scripts/smoke-test-install.sh
```

## Contributing

See [CONTRIBUTING.md](CONTRIBUTING.md) for development setup and pull request expectations.

## Security

See [SECURITY.md](SECURITY.md) for vulnerability reporting guidance.

## Proxy

JAIPilot uses the runtime default trust settings and supports standard proxy environment variables:

- `HTTPS_PROXY=http://proxy.example.com:8080`
- `HTTP_PROXY=http://proxy.example.com:8080`
- `NO_PROXY=127.0.0.1,localhost,.internal.example.com`

## License

[MIT](LICENSE)
