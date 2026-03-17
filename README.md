# JAIPilot CLI

`jaipilot verify` runs JaCoCo and PIT for a Maven project and prints a simple `PASS` or `FAIL` report with actionable reasons.

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

## Releasing

JAIPilot ships to Homebrew through the public tap repo `skrcode/homebrew-tap`.

One-time setup:

- create the public GitHub repo `skrcode/homebrew-tap` with default branch `main`
- run `./scripts/bootstrap-homebrew-tap.sh --tap skrcode/homebrew-tap --remote-url git@github.com:skrcode/homebrew-tap.git --push`
- add the `GH_PAT` GitHub Actions secret to `skrcode/jaipilot-cli` with write access to this repo and `skrcode/homebrew-tap`

Release flow:

- run `./scripts/preflight-homebrew-release.sh 0.1.0`
- push a tag like `v0.1.0`
- watch the release workflow publish the GitHub Release and update the Homebrew tap

The release workflow publishes:

- `jaipilot-<version>.zip`
- `jaipilot-<version>.tar.gz`
- The Homebrew formula is generated from the `.zip` artifact.

See [docs/homebrew-release.md](docs/homebrew-release.md) for the full walkthrough.
