# JAIPilot CLI

JAIPilot is a test safety harness for Java Maven applications. `jaipilot verify` runs JaCoCo and PIT so coding agents like Claude Code, Cursor, and Codex can write and refactor code safely while pushing toward high coverage, ideally 100% where practical.

The goal is to give agents strong safety rails: coverage and mutation testing act as the harness that lets them make changes, run checks, inspect gaps, and keep iterating. This follows the idea described in [AI is forcing us to write good code](https://bits.logic.inc/p/ai-is-forcing-us-to-write-good-code).

The target project does not need JaCoCo or PIT configured in its own `pom.xml`.

## Requirements

- Java 17+
- A Maven project
- JUnit 4 or JUnit 5 tests

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

## Usage

Run inside any Maven project:

```sh
jaipilot verify
```

Ask the agent to keep running `jaipilot verify`, inspect the failures, add or improve tests, and repeat until coverage and mutation results are maximized.

Set thresholds explicitly:

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

## What You Get

- progress updates while the command runs
- a final `PASS` or `FAIL`
- exact coverage gaps
- exact mutation failures
- concrete next actions

## Notes

- Maven only
- JUnit 4 and JUnit 5 are supported
- the command uses a temporary mirrored workspace and does not edit the target repo
- PIT runs in parallel and reuses history from `~/.jaipilot/pit-history` to speed up repeat runs
