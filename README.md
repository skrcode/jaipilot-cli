# JAIPilot CLI

[![CI](https://github.com/skrcode/jaipilot-cli/actions/workflows/ci.yml/badge.svg?branch=main)](https://github.com/skrcode/jaipilot-cli/actions/workflows/ci.yml)
[![Release](https://img.shields.io/github/v/release/skrcode/jaipilot-cli)](https://github.com/skrcode/jaipilot-cli/releases)
[![License](https://img.shields.io/github/license/skrcode/jaipilot-cli)](LICENSE)

JAIPilot is a zero-config test safety harness for Java Maven applications. `jaipilot verify` runs JaCoCo coverage checks and PIT mutation testing, then prints an actionable PASS or FAIL report that helps developers and coding agents tighten tests before shipping changes.

It is designed to work alongside Claude Code, Cursor, Codex, and similar tools. Agents can change code, run `jaipilot verify`, inspect weak coverage and surviving mutations, add or improve tests, and repeat until the project is well protected.

## Why JAIPilot

- zero-config JaCoCo and PIT verification for Java Maven projects
- a test safety harness for AI-assisted coding and refactoring
- actionable output with exact coverage gaps and mutation failures
- no changes required in the target project's `pom.xml`
- uses a temporary mirrored workspace instead of editing your repo

This follows the idea described in [AI is forcing us to write good code](https://bits.logic.inc/p/ai-is-forcing-us-to-write-good-code): as AI makes code generation cheaper, strong tests become the safety system that keeps quality high.

## Install

Homebrew:

```sh
brew install skrcode/tap/jaipilot
```

Fallback from source:

```sh
./scripts/install-global.sh
```

Then make sure `~/.local/bin` is on your `PATH`.

## Quick Start

Run inside any Maven project:

```sh
jaipilot verify
```

Run with explicit thresholds:

```sh
jaipilot verify \
  --line-coverage-threshold 85 \
  --branch-coverage-threshold 75 \
  --instruction-coverage-threshold 85 \
  --mutation-threshold 80
```

Use a specific Maven executable:

```sh
jaipilot verify --maven-executable /path/to/mvn
```

## Use With Coding Agents

JAIPilot works best as the verification loop around an agent. Ask Claude Code, Cursor, Codex, or another coding agent to keep running `jaipilot verify`, inspect failures, improve tests, and repeat until the project passes with coverage as high as possible.

Example prompt:

```text
Keep running `jaipilot verify` and update tests until you reach 80% (or 100%).
```

## What `jaipilot verify` Gives You

- progress updates while the command runs
- a final `PASS` or `FAIL`
- exact coverage gaps from JaCoCo
- exact surviving mutations from PIT
- concrete next actions instead of raw plugin output

## How It Works

JAIPilot prepares a temporary mirrored Maven workspace, injects the required JaCoCo and PIT plugins there, runs the verification workflow, parses the generated reports, and prints a simplified summary for humans and agents.

The target project does not need JaCoCo or PIT configured in its own `pom.xml`.

## Requirements

- Java 17+
- a Maven project
- JUnit 4 or JUnit 5 tests
- Maven available via `./mvnw` or `mvn`

## Current Scope

- Maven only
- JUnit 4 and JUnit 5 only
- the target repo is not modified
- PIT runs in parallel and reuses history from `~/.jaipilot/pit-history` to speed up repeat runs

## Development

Build and test locally:

```sh
./mvnw -B verify
```

Smoke-test the packaged distributions:

```sh
./scripts/smoke-test-distributions.sh
```

## Contributing

See [CONTRIBUTING.md](CONTRIBUTING.md) for development setup and pull request expectations.

## Security

See [SECURITY.md](SECURITY.md) for vulnerability reporting guidance.

## License

[MIT](LICENSE)
