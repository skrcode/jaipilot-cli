# JAIPilot CLI

`jaipilot verify` runs JaCoCo and PIT on a Maven project and prints a simple `PASS` or `FAIL` report with actionable reasons.

The target project does not need to pre-configure JaCoCo or PIT in its `pom.xml`.

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

