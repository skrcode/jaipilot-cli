<div align="center">
  <h1>JAIPilot CLI</h1>
  <p><strong>Stop guessing. Start verifying AI-generated Java changes.</strong></p>
  <p>
    <a href="https://github.com/skrcode/jaipilot-cli/actions/workflows/ci.yml">
      <img src="https://github.com/skrcode/jaipilot-cli/actions/workflows/ci.yml/badge.svg?branch=main" alt="CI">
    </a>
    <a href="https://github.com/skrcode/jaipilot-cli/releases">
      <img src="https://img.shields.io/github/v/release/skrcode/jaipilot-cli" alt="Release">
    </a>
    <a href="LICENSE">
      <img src="https://img.shields.io/github/license/skrcode/jaipilot-cli" alt="License">
    </a>
  </p>
</div>

JAIPilot is a zero-config test safety harness for Java Maven projects. It helps keep code written by agents safe by enforcing strong test coverage and mutation resistance before code ships.

With high-coverage, robust unit tests, you can let coding agents such as Claude Code, Codex, and Cursor work on your codebase with a much tighter feedback loop. Existing tests validate new changes quickly, and weak spots become visible immediately.

If your repository already has high line, branch, and mutation coverage, agents can use that test suite as a guardrail. New code can then be held to the same standard.

Run `jaipilot verify` to execute JaCoCo coverage checks and PIT mutation testing. It prints an actionable `PASS` or `FAIL` report that helps developers and coding agents tighten tests before merging changes.

It is designed to work alongside Claude Code, Cursor, Codex, and similar tools. Agents can change code, run `jaipilot verify`, inspect weak coverage and surviving mutations, add or improve tests, and repeat until the project is well protected.

## Why JAIPilot

- Zero-config JaCoCo and PIT verification for Java Maven projects
- A test safety harness for AI-assisted coding and refactoring
- Actionable output with exact coverage gaps and mutation failures
- No changes required in the target project's `pom.xml`
- Uses a temporary mirrored workspace instead of editing your repo

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

## Recommended Agent Workflow

1. Run `jaipilot verify` once on the existing codebase to establish the current safety baseline.
2. Have your coding agent make changes and rerun `jaipilot verify`.
3. Let the agent inspect coverage gaps and surviving mutations, then add or improve tests.
4. Repeat until the project passes at your target threshold.

For many teams, that means starting above 80% for the existing codebase and pushing newly written code toward 100% line, branch, and mutation coverage.

## Use With Coding Agents

JAIPilot works best as the verification loop around an agent. Ask Claude Code, Cursor, Codex, or another coding agent to keep running `jaipilot verify`, inspect failures, improve tests, and repeat until the project passes with coverage as high as possible.

Example prompt:

```text
Keep running `jaipilot verify` and update tests until you reach 80% (or 100%).
```

## What `jaipilot verify` Gives You

- Progress updates while the command runs
- A final `PASS` or `FAIL`
- Exact coverage gaps from JaCoCo
- Exact surviving mutations from PIT
- Concrete next actions

## How It Works

JAIPilot prepares a temporary mirrored Maven workspace, injects the required JaCoCo and PIT plugins there, runs the verification workflow, parses the generated reports, and prints a simplified summary for humans and agents.

The target project does not need JaCoCo or PIT configured in its own `pom.xml`.

## Requirements

- Java 17+
- A Maven project
- JUnit 4 or JUnit 5 tests
- Maven available via `./mvnw` or `mvn`

## Current Scope

- Maven only
- JUnit 4 and JUnit 5 only
- The target repo is not modified
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
