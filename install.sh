#!/usr/bin/env sh
set -eu

REPO="JAIPilot/jaipilot-cli"
PREFIX="${HOME}/.local"
BIN_DIR=""
APP_DIR=""
VERSION=""
RESOLVED_VERSION=""
RESOLVED_PLATFORM=""
ARCHIVE_URL=""
CHECKSUM_URL=""
PLATFORM=""

usage() {
  cat <<'EOF'
Usage: install.sh [options]

Installs the latest JAIPilot release with a bundled Java runtime and an archive
checksum verification step.

Options:
  --version <version>      Install a specific release version.
  --platform <platform>    Override platform detection. Example: macos-aarch64.
  --archive-url <url>      Override the release archive URL. Intended for testing.
  --checksum-url <url>     Override the archive checksum URL. Intended for testing.
  --prefix <dir>           Installation prefix. Default: ~/.local
  --bin-dir <dir>          Explicit bin directory. Overrides --prefix/bin.
  --app-dir <dir>          Explicit app directory. Overrides --prefix/share/jaipilot.
  --lib-dir <dir>          Deprecated alias for --app-dir.
  -h, --help               Show this help text.
EOF
}

die() {
  echo "$1" >&2
  exit 1
}

strip_v() {
  case "$1" in
    v*) printf '%s\n' "${1#v}" ;;
    *) printf '%s\n' "$1" ;;
  esac
}

contains_path_entry() {
  case ":$PATH:" in
    *":$1:"*) return 0 ;;
    *) return 1 ;;
  esac
}

require_command() {
  command -v "$1" >/dev/null 2>&1 || die "Required command not found: $1"
}

checksum_command() {
  if command -v sha256sum >/dev/null 2>&1; then
    printf 'sha256sum\n'
    return
  fi
  if command -v shasum >/dev/null 2>&1; then
    printf 'shasum\n'
    return
  fi
  if command -v openssl >/dev/null 2>&1; then
    printf 'openssl\n'
    return
  fi
  die "Required checksum tool not found: sha256sum, shasum, or openssl"
}

compute_sha256() {
  tool=$(checksum_command)
  case "$tool" in
    sha256sum)
      sha256sum "$1" | awk '{print tolower($1)}'
      ;;
    shasum)
      shasum -a 256 "$1" | awk '{print tolower($1)}'
      ;;
    openssl)
      openssl dgst -sha256 "$1" | awk '{print tolower($NF)}'
      ;;
  esac
}

resolve_latest_version() {
  json=$(curl -fsSL "https://api.github.com/repos/$REPO/releases/latest")
  version=$(printf '%s' "$json" | tr -d '\n' | sed -n 's/.*"tag_name"[[:space:]]*:[[:space:]]*"\(v\{0,1\}[^"]*\)".*/\1/p')
  [ -n "$version" ] || die "Failed to determine the latest JAIPilot version."
  strip_v "$version"
}

resolve_version_from_archive_url() {
  [ -n "$ARCHIVE_URL" ] || return 1
  archive_name=$(printf '%s\n' "$ARCHIVE_URL" | sed 's#^.*\/##; s/[?#].*$//')
  version=$(printf '%s\n' "$archive_name" | sed -n 's/^jaipilot-\([0-9][0-9.]*\)-.*\.tar\.gz$/\1/p')
  [ -n "$version" ] || return 1
  printf '%s\n' "$version"
}

resolve_version() {
  if [ -n "$VERSION" ]; then
    printf '%s\n' "$VERSION"
    return
  fi
  if version=$(resolve_version_from_archive_url); then
    printf '%s\n' "$version"
    return
  fi
  resolve_latest_version
}

resolve_os() {
  case "$(uname -s)" in
    Linux) printf 'linux\n' ;;
    Darwin) printf 'macos\n' ;;
    *) die "Unsupported operating system: $(uname -s)" ;;
  esac
}

resolve_arch() {
  case "$(uname -m)" in
    x86_64|amd64) printf 'x64\n' ;;
    arm64|aarch64) printf 'aarch64\n' ;;
    *) die "Unsupported architecture: $(uname -m)" ;;
  esac
}

resolve_platform() {
  if [ -n "$PLATFORM" ]; then
    printf '%s\n' "$PLATFORM"
    return
  fi
  printf '%s-%s\n' "$(resolve_os)" "$(resolve_arch)"
}

resolve_archive_url() {
  if [ -n "$ARCHIVE_URL" ]; then
    printf '%s\n' "$ARCHIVE_URL"
    return
  fi

  printf 'https://github.com/%s/releases/download/v%s/jaipilot-%s-%s.tar.gz\n' "$REPO" "$RESOLVED_VERSION" "$RESOLVED_VERSION" "$RESOLVED_PLATFORM"
}

resolve_checksum_url() {
  if [ -n "$CHECKSUM_URL" ]; then
    printf '%s\n' "$CHECKSUM_URL"
    return
  fi
  printf '%s.sha256\n' "$1"
}

read_expected_sha256() {
  expected=$(awk 'NF {print $1; exit}' "$1" | tr '[:upper:]' '[:lower:]')
  case "$expected" in
    [0-9a-f][0-9a-f][0-9a-f][0-9a-f][0-9a-f][0-9a-f][0-9a-f][0-9a-f]*)
      ;;
    *)
      die "Checksum file did not contain a SHA-256 digest: $1"
      ;;
  esac
  [ "${#expected}" -eq 64 ] || die "Checksum file did not contain a SHA-256 digest: $1"
  printf '%s\n' "$expected"
}

while [ "$#" -gt 0 ]; do
  case "$1" in
    --version)
      [ "$#" -ge 2 ] || die "Missing value for --version"
      VERSION=$(strip_v "$2")
      shift 2
      ;;
    --archive-url)
      [ "$#" -ge 2 ] || die "Missing value for --archive-url"
      ARCHIVE_URL=$2
      shift 2
      ;;
    --platform)
      [ "$#" -ge 2 ] || die "Missing value for --platform"
      PLATFORM=$2
      shift 2
      ;;
    --checksum-url)
      [ "$#" -ge 2 ] || die "Missing value for --checksum-url"
      CHECKSUM_URL=$2
      shift 2
      ;;
    --prefix)
      [ "$#" -ge 2 ] || die "Missing value for --prefix"
      PREFIX=$2
      shift 2
      ;;
    --bin-dir)
      [ "$#" -ge 2 ] || die "Missing value for --bin-dir"
      BIN_DIR=$2
      shift 2
      ;;
    --app-dir)
      [ "$#" -ge 2 ] || die "Missing value for --app-dir"
      APP_DIR=$2
      shift 2
      ;;
    --lib-dir)
      [ "$#" -ge 2 ] || die "Missing value for --lib-dir"
      APP_DIR=$2
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

[ -n "$BIN_DIR" ] || BIN_DIR="${PREFIX}/bin"
[ -n "$APP_DIR" ] || APP_DIR="${PREFIX}/share/jaipilot"

require_command curl
require_command tar
require_command mktemp

RESOLVED_VERSION=$(resolve_version)
RESOLVED_PLATFORM=$(resolve_platform)
ARCHIVE_URL=$(resolve_archive_url)
CHECKSUM_URL=$(resolve_checksum_url "$ARCHIVE_URL")

TMP_DIR=$(mktemp -d)
trap 'rm -rf "$TMP_DIR"' EXIT INT TERM

ARCHIVE_PATH="$TMP_DIR/jaipilot.tar.gz"
CHECKSUM_PATH="$TMP_DIR/jaipilot.tar.gz.sha256"
curl -fsSL "$ARCHIVE_URL" -o "$ARCHIVE_PATH"
curl -fsSL "$CHECKSUM_URL" -o "$CHECKSUM_PATH"

EXPECTED_SHA256=$(read_expected_sha256 "$CHECKSUM_PATH")
ACTUAL_SHA256=$(compute_sha256 "$ARCHIVE_PATH")
[ "$EXPECTED_SHA256" = "$ACTUAL_SHA256" ] || die "SHA-256 mismatch for downloaded archive."

tar -xzf "$ARCHIVE_PATH" -C "$TMP_DIR"

EXTRACTED_DIR=$(find "$TMP_DIR" -mindepth 1 -maxdepth 1 -type d | head -n 1)
[ -n "${EXTRACTED_DIR:-}" ] || die "Failed to unpack the JAIPilot archive."
[ -f "$EXTRACTED_DIR/lib/jaipilot.jar" ] || die "Downloaded archive is missing lib/jaipilot.jar."
[ -x "$EXTRACTED_DIR/bin/jaipilot" ] || die "Downloaded archive is missing bin/jaipilot."
[ -x "$EXTRACTED_DIR/runtime/bin/java" ] || die "Downloaded archive is missing the bundled Java runtime."

mkdir -p "$BIN_DIR" "$(dirname "$APP_DIR")"
mkdir -p "$APP_DIR/bin" "$APP_DIR/versions"

VERSION_DIR="$APP_DIR/versions/$RESOLVED_VERSION"
rm -rf "$VERSION_DIR"
mv "$EXTRACTED_DIR" "$VERSION_DIR"

rm -f "$APP_DIR/current"
ln -s "versions/$RESOLVED_VERSION" "$APP_DIR/current"

cat > "$APP_DIR/bin/jaipilot" <<EOF
#!/usr/bin/env sh
set -eu
BASE_DIR=\$(CDPATH= cd -- "\$(dirname -- "\$0")/.." && pwd)
exec "\$BASE_DIR/current/bin/jaipilot" "\$@"
EOF

chmod +x "$APP_DIR/bin/jaipilot"

cat > "$BIN_DIR/jaipilot" <<EOF
#!/usr/bin/env sh
set -eu
exec "$APP_DIR/bin/jaipilot" "\$@"
EOF

chmod +x "$BIN_DIR/jaipilot"

echo "Installed JAIPilot"
echo "  Version: $RESOLVED_VERSION"
echo "  Archive: $ARCHIVE_URL"
echo "  SHA-256: $ACTUAL_SHA256"
echo "  App: $APP_DIR"
echo "  Current: $APP_DIR/current"
echo "  Payload: $VERSION_DIR"
echo "  Runtime: $APP_DIR/current/runtime/bin/java"
echo "  Launcher: $BIN_DIR/jaipilot"

if contains_path_entry "$BIN_DIR"; then
  echo "  PATH: $BIN_DIR is already on PATH"
else
  echo "  PATH: add $BIN_DIR to your PATH"
fi

echo
echo "You can now run:"
echo "  jaipilot --help"
