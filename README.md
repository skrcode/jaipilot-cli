# JAIPilot CLI

JAIPilot CLI is a test safety harness for Java Maven projects used by coding agents such as Claude Code, Cursor, and Codex.

High unit test coverage and strong mutation coverage make it safer for an agent to generate, refactor, and run code with a tighter feedback loop. JAIPilot CLI wraps JaCoCo unit test coverage and PIT mutation coverage into a single `PASS` or `FAIL` report with actionable reasons that a coding agent can act on directly.

The target project does not need to pre-configure JaCoCo or PIT in its `pom.xml`.

Reference: [AI Is Forcing Us to Write Good Code](https://bits.logic.inc/p/ai-is-forcing-us-to-write-good-code)

## Requirements

- Java 17+
- A Maven project
- JUnit 4 or JUnit 5 tests

## Install

From this repo:

```sh
./scripts/install-global.sh
```

Then make sure `~/.local/bin` is on your `PATH`.

## Using With Coding Agents

This tool is meant for coding agents, including Claude Code, Cursor, and Codex.

The simplest workflow is to give the agent this repo link:

`https://github.com/skrcode/jaipilot-cli`

Then ask it to install JAIPilot CLI and keep running `jaipilot verify` until the coverage report is sorted and the thresholds pass.

Example instruction for an agent:

```text
Use https://github.com/skrcode/jaipilot-cli as the test safety harness for this Maven project.
Install it, run `jaipilot verify`, and keep fixing tests until coverage and mutation thresholds pass.
```

## Usage

Run inside any Maven project:

```sh
jaipilot verify
```

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
