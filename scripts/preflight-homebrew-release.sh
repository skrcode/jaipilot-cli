#!/usr/bin/env sh
set -eu

SCRIPT_DIR=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
REPO_ROOT=$(CDPATH= cd -- "$SCRIPT_DIR/.." && pwd)
MVNW="$REPO_ROOT/mvnw"
VERSION=""

usage() {
  cat <<'EOF'
Usage: scripts/preflight-homebrew-release.sh <version>

Builds the release archives for a version and smoke-tests the packaged
Homebrew artifacts locally before pushing a release tag.
EOF
}

die() {
  echo "$1" >&2
  exit 1
}

while [ "$#" -gt 0 ]; do
  case "$1" in
    -h|--help)
      usage
      exit 0
      ;;
    *)
      [ -z "$VERSION" ] || die "Unexpected argument: $1"
      VERSION=$1
      shift
      ;;
  esac
done

[ -n "$VERSION" ] || die "A release version is required. Example: scripts/preflight-homebrew-release.sh 0.1.0"

VERSION=${VERSION#v}

command -v java >/dev/null 2>&1 || die "Java is required but was not found on PATH."
[ -x "$MVNW" ] || die "Missing Maven Wrapper at $MVNW"

"$MVNW" -B -ntp -Drevision="$VERSION" verify
"$REPO_ROOT/scripts/smoke-test-distributions.sh" --version "$VERSION"

echo
echo "Homebrew release preflight passed for v$VERSION"
echo "Next steps:"
echo "  git tag v$VERSION"
echo "  git push origin v$VERSION"
