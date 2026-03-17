#!/usr/bin/env sh
set -eu

TAP_REPO="skrcode/homebrew-tap"
BRANCH="main"
REMOTE_URL=""
PUSH=0
FORCE_REMOTE=0

usage() {
  cat <<'EOF'
Usage: scripts/bootstrap-homebrew-tap.sh [options]

Creates or reuses a Homebrew tap skeleton for JAIPilot.

Options:
  --tap <owner/repo>         Tap repository. Default: skrcode/homebrew-tap
  --branch <name>            Default branch to create or push. Default: main
  --remote-url <url>         Configure the tap's origin remote.
  --push                     Push the tap branch after configuring origin.
  --force-remote             Replace an existing origin remote URL.
  -h, --help                 Show this help text.
EOF
}

die() {
  echo "$1" >&2
  exit 1
}

tap_install_name() {
  owner=${1%%/*}
  repo=${1#*/}
  case "$repo" in
    homebrew-*) repo=${repo#homebrew-} ;;
  esac
  printf '%s/%s\n' "$owner" "$repo"
}

while [ "$#" -gt 0 ]; do
  case "$1" in
    --tap)
      [ "$#" -ge 2 ] || die "Missing value for --tap"
      TAP_REPO=$2
      shift 2
      ;;
    --branch)
      [ "$#" -ge 2 ] || die "Missing value for --branch"
      BRANCH=$2
      shift 2
      ;;
    --remote-url)
      [ "$#" -ge 2 ] || die "Missing value for --remote-url"
      REMOTE_URL=$2
      shift 2
      ;;
    --push)
      PUSH=1
      shift
      ;;
    --force-remote)
      FORCE_REMOTE=1
      shift
      ;;
    -h|--help)
      usage
      exit 0
      ;;
    *)
      die "Unknown option: $1"
      ;;
  esac
done

case "$TAP_REPO" in
  */*) ;;
  *) die "--tap must be in owner/repo form" ;;
esac

command -v brew >/dev/null 2>&1 || die "Homebrew is required but was not found on PATH."
command -v git >/dev/null 2>&1 || die "git is required but was not found on PATH."

TAP_PATH=$(brew --repository "$TAP_REPO")

if [ -d "$TAP_PATH" ]; then
  echo "Reusing existing tap at $TAP_PATH"
else
  brew tap-new "$TAP_REPO" --branch="$BRANCH"
fi

if [ -n "$REMOTE_URL" ]; then
  if git -C "$TAP_PATH" remote get-url origin >/dev/null 2>&1; then
    CURRENT_REMOTE=$(git -C "$TAP_PATH" remote get-url origin)
    if [ "$CURRENT_REMOTE" != "$REMOTE_URL" ]; then
      [ "$FORCE_REMOTE" -eq 1 ] || die "Origin already points to $CURRENT_REMOTE. Re-run with --force-remote to replace it."
      git -C "$TAP_PATH" remote set-url origin "$REMOTE_URL"
    fi
  else
    git -C "$TAP_PATH" remote add origin "$REMOTE_URL"
  fi
fi

if [ "$PUSH" -eq 1 ]; then
  git -C "$TAP_PATH" remote get-url origin >/dev/null 2>&1 || die "--push requires an origin remote. Pass --remote-url first."
  git -C "$TAP_PATH" push -u origin "$BRANCH"
fi

echo "Homebrew tap bootstrap complete"
echo "  Repo: $TAP_REPO"
echo "  Install name: $(tap_install_name "$TAP_REPO")"
echo "  Path: $TAP_PATH"

if git -C "$TAP_PATH" remote get-url origin >/dev/null 2>&1; then
  echo "  Remote: $(git -C "$TAP_PATH" remote get-url origin)"
else
  echo "  Remote: not configured"
  echo
  echo "Next steps:"
  echo "  1. Create the public GitHub repo $TAP_REPO with default branch $BRANCH."
  echo "  2. Re-run this script with --remote-url <git-url> --push"
fi

echo
echo "After releases, users can install JAIPilot with:"
echo "  brew install $(tap_install_name "$TAP_REPO")/jaipilot"
