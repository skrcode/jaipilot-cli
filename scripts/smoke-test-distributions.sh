#!/usr/bin/env sh
set -eu

SCRIPT_DIR=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
REPO_ROOT=$(CDPATH= cd -- "$SCRIPT_DIR/.." && pwd)
DIST_DIR="$REPO_ROOT/target/distributions"
SMOKE_DIR="$REPO_ROOT/target/smoke"
VERSION=""

usage() {
  cat <<'EOF'
Usage: scripts/smoke-test-distributions.sh [--version <version>]

Smoke-tests the packaged JAIPilot zip and tar.gz archives by unpacking them
and running `jaipilot --version` from each archive.
EOF
}

die() {
  echo "$1" >&2
  exit 1
}

while [ "$#" -gt 0 ]; do
  case "$1" in
    --version)
      [ "$#" -ge 2 ] || die "Missing value for --version"
      VERSION=$2
      shift 2
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

[ -d "$DIST_DIR" ] || die "Missing distribution directory: $DIST_DIR"

if [ -n "$VERSION" ]; then
  VERSION=${VERSION#v}
  ZIP="$DIST_DIR/jaipilot-$VERSION.zip"
  TGZ="$DIST_DIR/jaipilot-$VERSION.tar.gz"
else
  ZIP=$(find "$DIST_DIR" -maxdepth 1 -name 'jaipilot-*.zip' | sort | tail -n 1)
  TGZ=$(find "$DIST_DIR" -maxdepth 1 -name 'jaipilot-*.tar.gz' | sort | tail -n 1)
fi

[ -n "${ZIP:-}" ] || die "Could not find a JAIPilot zip distribution under $DIST_DIR"
[ -n "${TGZ:-}" ] || die "Could not find a JAIPilot tar.gz distribution under $DIST_DIR"
[ -f "$ZIP" ] || die "Missing distribution archive: $ZIP"
[ -f "$TGZ" ] || die "Missing distribution archive: $TGZ"

rm -rf "$SMOKE_DIR"
mkdir -p "$SMOKE_DIR/zip" "$SMOKE_DIR/tar"

unzip -q "$ZIP" -d "$SMOKE_DIR/zip"
LC_ALL=C tar -xzf "$TGZ" -C "$SMOKE_DIR/tar"

ZIP_DIR=$(find "$SMOKE_DIR/zip" -mindepth 1 -maxdepth 1 -type d | head -n 1)
TAR_DIR=$(find "$SMOKE_DIR/tar" -mindepth 1 -maxdepth 1 -type d | head -n 1)

[ -n "${ZIP_DIR:-}" ] || die "Unable to locate the extracted zip directory under $SMOKE_DIR/zip"
[ -n "${TAR_DIR:-}" ] || die "Unable to locate the extracted tar directory under $SMOKE_DIR/tar"
[ -x "$ZIP_DIR/bin/jaipilot" ] || die "Missing executable in zip archive: $ZIP_DIR/bin/jaipilot"
[ -x "$TAR_DIR/bin/jaipilot" ] || die "Missing executable in tar archive: $TAR_DIR/bin/jaipilot"

"$ZIP_DIR/bin/jaipilot" --version
"$TAR_DIR/bin/jaipilot" --version

echo "Smoke-tested distributions"
echo "  Zip: $ZIP"
echo "  Tar: $TGZ"
