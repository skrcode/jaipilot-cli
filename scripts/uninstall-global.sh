#!/usr/bin/env sh
set -eu

PREFIX="${HOME}/.local"
BIN_DIR=""
LIB_DIR=""

usage() {
  cat <<'EOF'
Usage: scripts/uninstall-global.sh [options]

Removes a JAIPilot global installation created by install-global.sh.

Options:
  --prefix <dir>    Installation prefix. Default: ~/.local
  --bin-dir <dir>   Explicit bin directory. Overrides --prefix/bin.
  --lib-dir <dir>   Explicit library directory. Overrides --prefix/share/jaipilot.
  -h, --help        Show this help text.
EOF
}

while [ "$#" -gt 0 ]; do
  case "$1" in
    --prefix)
      [ "$#" -ge 2 ] || { echo "Missing value for --prefix" >&2; exit 1; }
      PREFIX=$2
      shift 2
      ;;
    --bin-dir)
      [ "$#" -ge 2 ] || { echo "Missing value for --bin-dir" >&2; exit 1; }
      BIN_DIR=$2
      shift 2
      ;;
    --lib-dir)
      [ "$#" -ge 2 ] || { echo "Missing value for --lib-dir" >&2; exit 1; }
      LIB_DIR=$2
      shift 2
      ;;
    -h|--help)
      usage
      exit 0
      ;;
    *)
      echo "Unknown option: $1" >&2
      exit 1
      ;;
  esac
done

[ -n "${BIN_DIR}" ] || BIN_DIR="${PREFIX}/bin"
[ -n "${LIB_DIR}" ] || LIB_DIR="${PREFIX}/share/jaipilot"

TARGET_WRAPPER="$BIN_DIR/jaipilot"
TARGET_JAR="$LIB_DIR/jaipilot.jar"

if [ -f "$TARGET_WRAPPER" ]; then
  rm -f "$TARGET_WRAPPER"
  echo "Removed $TARGET_WRAPPER"
fi

if [ -f "$TARGET_JAR" ]; then
  rm -f "$TARGET_JAR"
  echo "Removed $TARGET_JAR"
fi

rmdir "$LIB_DIR" 2>/dev/null || true

echo "JAIPilot global installation removed."
