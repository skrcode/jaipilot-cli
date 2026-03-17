# Contributing

Thanks for contributing to JAIPilot.

## Development Setup

- Java 17+
- Git
- A POSIX shell environment for the helper scripts

Clone the repo and run:

```sh
./mvnw -B verify
```

This runs the unit tests, integration tests, packaging, and the distribution checks used in CI.

## Common Commands

Build and test:

```sh
./mvnw -B verify
```

Smoke-test the packaged archives:

```sh
./scripts/smoke-test-distributions.sh
```

Install the CLI locally:

```sh
./scripts/install-global.sh
```

## Pull Request Guidelines

- keep changes focused and scoped to a clear problem
- include tests for behavioral changes when practical
- update documentation when CLI behavior or installation steps change
- prefer small, reviewable pull requests over broad refactors
- do not commit generated `target/` output

Before opening a pull request, run:

```sh
./mvnw -B verify
```

## Reporting Bugs

When filing a bug, include:

- JAIPilot version
- Java version
- Maven version
- operating system
- the command you ran
- relevant logs or failing output
- a minimal sample project, if available

## Security

For security issues, follow [SECURITY.md](SECURITY.md) instead of opening a public issue.
