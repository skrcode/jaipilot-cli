# JAIPilot CLI

**JAIPilot - Test Harness for applications. Deploy with confidence.**

JAIPilot helps Java teams generate or repair JUnit tests when code changes put coverage and release confidence at risk. It is designed for Maven-based Java projects that use JaCoCo to verify coverage and need a reliable way to protect existing behavior as more code is produced by AI tools like Cursor and Claude Code.

## What JAIPilot Does

- Generates JUnit tests for changed Java classes
- Repairs broken or outdated test classes
- Helps restore coverage after code changes
- Protects existing behavior with stronger test coverage
- Fits into terminal, CI, and agent-driven workflows

## Why Enterprise Teams Use JAIPilot

- **Coverage repair, not one-off test generation.** JAIPilot is built for workflows where teams need to recover coverage regressions and meet coverage requirements instead of just asking an LLM to "write some tests."
- **A safety harness for AI-generated code.** When Cursor or Claude Code accelerates implementation, JAIPilot adds a disciplined test layer that helps catch regressions before deployment.
- **Protection for existing code.** New feature work should not silently weaken tested behavior. JAIPilot helps generate or repair tests so existing code paths stay protected as the codebase evolves.
- **Deploy with confidence.** JAIPilot is intended to sit alongside Maven, JUnit, and JaCoCo so teams can use coverage as a concrete release signal instead of relying on intuition.
- **Built for automation.** The CLI format makes JAIPilot usable from terminals, scripts, CI pipelines, and external agents.

## Where JAIPilot Fits

JAIPilot does not replace Cursor or Claude Code. It complements them.

Use your preferred agent to write or modify Java code. Then use JAIPilot to generate or repair JUnit tests for the affected classes, run your Maven and JaCoCo checks, and verify that the change meets your coverage and code-safety expectations.

This is the core positioning:

- Cursor or Claude Code helps produce code quickly
- JAIPilot helps ensure that code is backed by tests
- Maven and JaCoCo provide the build and coverage signal
- Your CI pipeline decides whether the change is safe to ship

## Current Focus

JAIPilot currently works best for:

- Java 17 projects
- Standard Maven-style source layouts such as `src/main/java` and `src/test/java`
- JUnit-based testing workflows
- Teams that use JaCoCo as the coverage signal in local development or CI

Today, the CLI focuses on authenticated test generation and test repair for targeted Java classes.

## Coverage Repair Workflow

JAIPilot is designed to fit naturally into a JaCoCo-driven coverage loop:

1. A developer or coding agent changes a Java class.
2. Coverage drops or the team identifies a class that no longer meets coverage expectations.
3. JAIPilot generates a new JUnit test or repairs an existing one.
4. Maven and JaCoCo are run to validate behavior and measure coverage.
5. The change can be reviewed and deployed with a stronger safety signal.

Example workflow:

```sh
mvn test jacoco:report
./jaipilot generate src/main/java/com/acme/orders/OrderService.java
mvn test jacoco:report
```

If a test already exists but is outdated or broken:

```sh
./jaipilot fix src/test/java/com/acme/orders/OrderServiceTest.java
```

## Working with Cursor and Claude Code

JAIPilot is most useful when treated as a specialized execution primitive inside a broader AI-assisted workflow.

Typical pattern:

1. Cursor or Claude Code makes a code change.
2. JAIPilot is run against the changed class to generate or repair tests.
3. Maven and JaCoCo validate that the change is covered and does not regress existing behavior.

That matters in teams where AI is increasing code throughput faster than manual test writing can keep up.

## Quick Start

### Run from the repository

```sh
./jaipilot --help
```

The repository includes a `jaipilot` launcher script that builds the shaded JAR on demand and then executes the CLI.

### Build the distributable JAR

```sh
./gradlew shadowJar
java -jar build/libs/jaipilot-cli-0.1.0-all.jar --help
```

### Sign in

```sh
./jaipilot login
./jaipilot status
```

## Usage

### Generate a test for a source class

```sh
./jaipilot generate src/main/java/com/example/service/InvoiceService.java
```

By default, `generate` writes to `src/test/java/<package>/InvoiceServiceTest.java`.

### Add supporting context

```sh
./jaipilot generate src/main/java/com/example/service/InvoiceService.java \
  --context src/main/java/com/example/repository/InvoiceRepository.java \
  --context src/main/java/com/example/model/Invoice.java
```

### Repair an existing test class

```sh
./jaipilot fix src/test/java/com/example/service/InvoiceServiceTest.java
```

By default, `fix` updates the provided test file in place.

### Write output to a custom location

```sh
./jaipilot generate src/main/java/com/example/service/InvoiceService.java \
  --output generated-tests/InvoiceServiceTest.java
```

## Command Reference

| Command | Purpose |
| --- | --- |
| `login` | Opens the JAIPilot browser login flow. |
| `status` | Shows the currently signed-in email, if available. |
| `logout` | Removes locally stored credentials. |
| `generate <classPath>` | Generates a JUnit test for a source class. |
| `fix <classPath>` | Repairs an existing test class. |

### Options for `generate` and `fix`

| Option | Description |
| --- | --- |
| `--context <path>` | Additional context files. Repeat the flag or provide a comma-separated list. |
| `--project-root <dir>` | Project root used to resolve input and output paths. Defaults to the current directory. |
| `--output <path>` | Explicit output file path relative to the project root. |
| `--verbose` | Prints backend session details and stack traces on failure. |

### Options for `login`

| Option | Description |
| --- | --- |
| `--timeout <seconds>` | Login timeout in seconds. Defaults to `180`. |

## Authentication and Configuration

`login` starts a temporary callback server on `127.0.0.1` and opens `https://www.jaipilot.com/plugin-login` in the default browser. Credentials are stored at `~/.config/jaipilot/credentials.json` by default. Access tokens are refreshed automatically through `https://www.jaipilot.com/plugin-refresh`.

To override the credential location, use either of the following:

- `JAIPILOT_CONFIG_HOME=/path/to/config`
- `-Djaipilot.config.home=/path/to/config`

## Development

```sh
./gradlew test
./gradlew shadowJar
```

Optional native build with GraalVM Native Image installed:

```sh
./gradlew nativeCompile
```

## License

MIT
