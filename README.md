# JAIPilot CLI

**JAIPilot CLI is a code safety harness for AI-assisted Java delivery.**

As more production code is written with tools like Cursor and Claude Code, the cost of shipping unverified changes rises fast. JAIPilot helps Java teams protect existing behavior, generate missing JUnit coverage for changed classes, and move toward release decisions with more confidence.

JAIPilot is built for teams that want more than ad hoc test generation. It is designed to fit into a Java testing workflow where coverage matters, existing behavior must be preserved, and new code needs a reliable verification layer before it reaches production.

## Why Enterprise Teams Use JAIPilot

- **Coverage maximization, not one-off test generation.** JAIPilot is built for workflows where teams need to push classes toward coverage requirements instead of just asking an LLM to "write some tests."
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

Today, the CLI focuses on authenticated test generation and test repair for targeted Java classes. That makes it a practical building block for coverage-oriented workflows in Java codebases.

## Coverage Workflow

JAIPilot is designed to fit naturally into a JaCoCo-driven coverage loop:

1. A developer or coding agent changes a Java class.
2. The team identifies the class or package that needs stronger unit coverage.
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

That workflow is especially important in teams where AI is increasing code throughput faster than manual test writing can keep up.

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

## Common Usage

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
