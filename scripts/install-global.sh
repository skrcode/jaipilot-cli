#!/usr/bin/env sh
set -eu

SCRIPT_DIR=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
REPO_ROOT=$(CDPATH= cd -- "$SCRIPT_DIR/.." && pwd)
MVNW="$REPO_ROOT/mvnw"

PREFIX="${HOME}/.local"
BIN_DIR=""
LIB_DIR=""
SKIP_BUILD=0
FORCE=0

usage() {
  cat <<'EOF'
Usage: scripts/install-global.sh [options]

Builds JAIPilot and installs a global `jaipilot` launcher.

Options:
  --prefix <dir>    Installation prefix. Default: ~/.local
  --bin-dir <dir>   Explicit bin directory. Overrides --prefix/bin.
  --lib-dir <dir>   Explicit library directory. Overrides --prefix/share/jaipilot.
  --skip-build      Reuse an existing built jar from ./target.
  --force           Overwrite an existing non-JAIPilot wrapper.
  -h, --help        Show this help text.
EOF
}

die() {
  echo "$1" >&2
  exit 1
}

find_jar() {
  find "$REPO_ROOT/target" -maxdepth 1 -name 'jaipilot-cli-*-all.jar' | sort | tail -n 1
}

contains_path_entry() {
  case ":$PATH:" in
    *":$1:"*) return 0 ;;
    *) return 1 ;;
  esac
}

while [ "$#" -gt 0 ]; do
  case "$1" in
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
    --lib-dir)
      [ "$#" -ge 2 ] || die "Missing value for --lib-dir"
      LIB_DIR=$2
      shift 2
      ;;
    --skip-build)
      SKIP_BUILD=1
      shift
      ;;
    --force)
      FORCE=1
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

[ -n "${BIN_DIR}" ] || BIN_DIR="${PREFIX}/bin"
[ -n "${LIB_DIR}" ] || LIB_DIR="${PREFIX}/share/jaipilot"

command -v java >/dev/null 2>&1 || die "Java is required to run JAIPilot but was not found on PATH."
[ -x "$MVNW" ] || die "Missing Maven Wrapper at $MVNW"

if [ "$SKIP_BUILD" -eq 0 ]; then
  "$MVNW" -q -DskipTests package
fi

SOURCE_JAR=$(find_jar)
[ -n "$SOURCE_JAR" ] || die "Could not locate the shaded JAIPilot jar under $REPO_ROOT/target. Run ./mvnw package first."

mkdir -p "$BIN_DIR" "$LIB_DIR"

TARGET_JAR="$LIB_DIR/jaipilot.jar"
TARGET_WRAPPER="$BIN_DIR/jaipilot"

if [ -e "$TARGET_WRAPPER" ] && [ "$FORCE" -ne 1 ]; then
  if ! grep -q "Installed by JAIPilot install-global.sh" "$TARGET_WRAPPER" 2>/dev/null; then
    die "Refusing to overwrite existing $TARGET_WRAPPER. Re-run with --force if you want to replace it."
  fi
fi

cp "$SOURCE_JAR" "$TARGET_JAR"

cat > "$TARGET_WRAPPER" <<EOF
#!/usr/bin/env sh
set -eu
# Installed by JAIPilot install-global.sh
exec java -jar "$TARGET_JAR" "\$@"
EOF

chmod +x "$TARGET_WRAPPER"

echo "Installed JAIPilot"
echo "  Jar: $TARGET_JAR"
echo "  Launcher: $TARGET_WRAPPER"

if contains_path_entry "$BIN_DIR"; then
  echo "  PATH: $BIN_DIR is already on PATH"
else
  echo "  PATH: add $BIN_DIR to your PATH"
  echo "        echo 'export PATH=\"$BIN_DIR:\$PATH\"' >> ~/.zshrc"
fi

echo
echo "You can now run:"
echo "  jaipilot verify --help"
