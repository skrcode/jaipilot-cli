#!/usr/bin/env sh
set -eu

SCRIPT_DIR=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
REPO_ROOT=$(CDPATH= cd -- "$SCRIPT_DIR/.." && pwd)
POM_FILE="$REPO_ROOT/pom.xml"
VERSION_PROVIDER_FILE="$REPO_ROOT/src/main/java/com/jaipilot/cli/JaiPilotVersionProvider.java"
VERSION=""
PUSH_CHANGES=0

usage() {
  cat <<'EOF'
Usage: scripts/release-build.sh --version <version> [--push]

Prepares a new JAIPilot release by:
  1. Updating the project version.
  2. Running the full Maven verify build.
  3. Smoke-testing the install script for that version.
  4. Creating a release commit and annotated git tag.

Options:
  --version <version>  Release version such as 0.3.2.
  --push               Push main and the release tag to origin after tagging.
  -h, --help           Show this help text.
EOF
}

die() {
  echo "$1" >&2
  exit 1
}

require_command() {
  command -v "$1" >/dev/null 2>&1 || die "Required command not found: $1"
}

ensure_clean_worktree() {
  git diff --quiet --ignore-submodules HEAD -- || die "Git worktree is not clean."
  git diff --cached --quiet --ignore-submodules -- || die "Git index has staged changes."
}

validate_version() {
  printf '%s' "$1" | grep -Eq '^[0-9]+\.[0-9]+\.[0-9]+$' || die "Version must look like 0.3.2"
}

current_version() {
  perl -ne 'print "$1\n" if /<revision>([^<]+)<\/revision>/' "$POM_FILE" | head -n 1
}

current_branch() {
  git branch --show-current
}

update_versions() {
  NEW_VERSION=$1 perl -0pi -e 's#<revision>[^<]+</revision>#<revision>$ENV{NEW_VERSION}</revision>#' "$POM_FILE"
  NEW_VERSION=$1 perl -0pi -e 's/version = "[^"]+";/version = "$ENV{NEW_VERSION}";/' "$VERSION_PROVIDER_FILE"
}

ensure_version_applied() {
  expected=$1
  [ "$(current_version)" = "$expected" ] || die "Failed to update pom.xml to version $expected"
  grep -Fq "version = \"$expected\";" "$VERSION_PROVIDER_FILE" || die "Failed to update JaiPilotVersionProvider to version $expected"
}

ensure_tag_absent() {
  tag_name=$1
  git rev-parse -q --verify "refs/tags/$tag_name" >/dev/null 2>&1 && die "Tag already exists locally: $tag_name"
  if [ "$PUSH_CHANGES" -eq 1 ]; then
    git ls-remote --exit-code --tags origin "refs/tags/$tag_name" >/dev/null 2>&1 && die "Tag already exists on origin: $tag_name"
  fi
}

commit_and_tag() {
  version=$1
  git add "$POM_FILE" "$VERSION_PROVIDER_FILE"
  git commit -m "Release $version"
  git tag -a "v$version" -m "Release $version"
}

while [ "$#" -gt 0 ]; do
  case "$1" in
    --version)
      [ "$#" -ge 2 ] || die "Missing value for --version"
      VERSION=${2#v}
      shift 2
      ;;
    --push)
      PUSH_CHANGES=1
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

[ -n "$VERSION" ] || die "--version is required"

require_command git
require_command perl
require_command grep

validate_version "$VERSION"

cd "$REPO_ROOT"
ensure_clean_worktree
[ "$(current_branch)" = "main" ] || die "Release script must be run from the main branch."

CURRENT_VERSION=$(current_version)
[ -n "$CURRENT_VERSION" ] || die "Could not determine the current project version."
[ "$CURRENT_VERSION" != "$VERSION" ] || die "Project is already at version $VERSION"

ensure_tag_absent "v$VERSION"

update_versions "$VERSION"
ensure_version_applied "$VERSION"

./mvnw -B verify
./scripts/smoke-test-install.sh --version "$VERSION"

commit_and_tag "$VERSION"

if [ "$PUSH_CHANGES" -eq 1 ]; then
  git push origin main "v$VERSION"
  echo "Released JAIPilot $VERSION"
  echo "  Commit: $(git rev-parse --short HEAD)"
  echo "  Tag: v$VERSION"
  echo "  Pushed: origin/main and origin/v$VERSION"
else
  echo "Prepared JAIPilot $VERSION"
  echo "  Commit: $(git rev-parse --short HEAD)"
  echo "  Tag: v$VERSION"
  echo "Next:"
  echo "  git push origin main v$VERSION"
fi
