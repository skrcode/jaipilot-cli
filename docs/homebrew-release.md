# Homebrew Release Guide

JAIPilot publishes to Homebrew through the third-party tap `skrcode/homebrew-tap`.
The main repo creates release archives, JReleaser publishes the GitHub Release,
and JReleaser commits `Formula/jaipilot.rb` into the tap repo.

## One-time setup

1. Create a public GitHub repository named `skrcode/homebrew-tap` with default branch `main`.
2. Bootstrap the local tap skeleton and push it:

   ```sh
   ./scripts/bootstrap-homebrew-tap.sh \
     --tap skrcode/homebrew-tap \
     --remote-url git@github.com:skrcode/homebrew-tap.git \
     --push
   ```

3. Add a GitHub Actions secret named `GH_PAT` to `skrcode/jaipilot-cli`.
   The token must be able to push releases to `skrcode/jaipilot-cli` and commits to
   `skrcode/homebrew-tap`.

## What is already wired

- [release.yml](../.github/workflows/release.yml) triggers on tags matching `v*`.
- The workflow strips the leading `v` and passes the rest to Maven as `revision`.
- [jreleaser.yml](../jreleaser.yml) publishes the GitHub Release and updates `skrcode/homebrew-tap`.
- Homebrew uses the `.zip` release archive. The `.tar.gz` asset is still published to GitHub Releases but is excluded from brew generation.

## Release steps

1. Run a local preflight for the version you want to ship:

   ```sh
   ./scripts/preflight-homebrew-release.sh 0.1.0
   ```

2. Tag and push the release:

   ```sh
   git tag v0.1.0
   git push origin v0.1.0
   ```

3. Watch the GitHub Actions release job. It validates `GH_PAT`, confirms the tap repo is public on `main`, builds the archives, smoke-tests them, then runs JReleaser.
4. Verify the outputs:

   ```sh
   brew install skrcode/tap/jaipilot
   jaipilot --version
   ```

5. On later releases, validate upgrades:

   ```sh
   brew update
   brew upgrade skrcode/tap/jaipilot
   ```

## Troubleshooting

- If the release workflow fails before JReleaser runs, check that `GH_PAT` exists and can access `skrcode/homebrew-tap`.
- If JReleaser fails to update Homebrew, confirm that `skrcode/homebrew-tap` exists, is public, and uses `main`.
- If `brew install skrcode/tap/jaipilot` does not find the formula, verify that `Formula/jaipilot.rb` was committed to the tap repo for the tagged release.
