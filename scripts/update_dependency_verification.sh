#!/usr/bin/env bash
# Updates Gradle dependency verification: gradle/verification-metadata.xml
# (the trusted PGP keys, plus SHA-256 checksums for unsigned artifacts) and
# gradle/verification-keyring.keys (the public keys themselves, which builds
# read instead of asking a key server). Run it after changing a dependency,
# review the diff of both files, and commit them with the change.
#
#   scripts/update_dependency_verification.sh            add what's new
#   scripts/update_dependency_verification.sh --fresh    start from empty,
#                                                        dropping unused entries
#   scripts/update_dependency_verification.sh --no-check skip the final check
#   scripts/update_dependency_verification.sh --check-only
#
# It resolves everything from an empty, temporary Gradle home, so nothing is
# taken on trust because your own cache already had it. That means
# downloading every dependency: allow a few minutes.
#
# Steps:
#   1. Two passes of --write-verification-metadata pgp,sha256 --export-keys
#      over every task CI and release.yml run. From an empty home the first
#      pass leaves out some parent and BOM poms (bc-jdk18on-bom,
#      guava-parent...): it takes the signature it found on Maven Central,
#      but a normal build looks for one in the Google repository the pom is
#      credited to, finds none, and fails for want of a checksum. The second
#      pass, with everything downloaded, writes those checksums.
#   2. A check: the same tasks from another empty home, with the key servers
#      off, so it fails if the metadata or the keyring has a gap.
#
# Some artifacts are per platform (aapt2). Without --fresh, entries already in
# the file stay, so running it on a Mac keeps the Linux ones CI needs. With
# --fresh only this platform's are written: use it on Linux, or run the
# Dependency verification workflow, which does Linux and then macOS.
#
# TASKS overrides the task list.
set -euo pipefail

cd "$(git rev-parse --show-toplevel)"

fresh=false
check=true
write=true
force=false
for arg in "$@"; do
  case $arg in
    --fresh) fresh=true ;;
    --no-check) check=false ;;
    --check-only) write=false ;;
    --force) force=true ;;
    -h|--help) sed -n '2,/^set -euo/p' "$0" | sed '$d; s/^# \{0,1\}//'; exit 0 ;;
    *) echo "unknown option: $arg (see --help)" >&2; exit 2 ;;
  esac
done

TASKS=${TASKS:-testProdDebugUnitTest assembleProdDebug lintProdDebug assembleDevRelease bundleInternalRelease bundleProdRelease assembleProdRelease}
read -r -a tasks <<<"$TASKS"

metadata=gradle/verification-metadata.xml
keyring=gradle/verification-keyring.keys

if $fresh && [ "$(uname -s)" != Linux ] && ! $force; then
  echo "--fresh on $(uname -s) drops the Linux-only entries CI needs (aapt2)." >&2
  echo "Run it without --fresh, or use the Dependency verification workflow (or --force)." >&2
  exit 1
fi

work=$(mktemp -d)
trap 'rm -rf "$work"' EXIT

gradle() {
  GRADLE_USER_HOME=$1 ./gradlew --no-daemon --no-build-cache "${@:2}"
}

if $write; then
  if $fresh; then
    rm -f "$keyring" gradle/verification-keyring.gpg
    cat > "$metadata" <<'EOF'
<?xml version="1.0" encoding="UTF-8"?>
<verification-metadata xmlns="https://schema.gradle.org/dependency-verification" xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance" xsi:schemaLocation="https://schema.gradle.org/dependency-verification https://schema.gradle.org/dependency-verification/dependency-verification-1.4.xsd">
   <configuration>
      <verify-metadata>true</verify-metadata>
      <verify-signatures>true</verify-signatures>
      <keyring-format>armored</keyring-format>
      <key-servers>
         <key-server uri="hkps://keyserver.ubuntu.com"/>
         <key-server uri="hkps://keys.openpgp.org"/>
      </key-servers>
   </configuration>
   <components/>
</verification-metadata>
EOF
  fi

  if ! grep -q '<verify-signatures>true</verify-signatures>' "$metadata"; then
    echo "$metadata doesn't check signatures yet (it's checksums only)." >&2
    echo "Run with --fresh (on Linux), or use the Dependency verification workflow, once to switch it over." >&2
    exit 1
  fi
  # List the key servers, whatever the file had (none in an older file, or
  # enabled="false" from before they were used).
  perl -0pi -e '
    s{[ \t]*<key-servers\b[^>]*/>[ \t]*\n}{}g;
    s{[ \t]*<key-servers\b.*?</key-servers>[ \t]*\n}{}gs;
    s{(\n([ \t]*)<keyring-format>[^<]*</keyring-format>)}{$1\n$2<key-servers>\n$2   <key-server uri="hkps://keyserver.ubuntu.com"/>\n$2   <key-server uri="hkps://keys.openpgp.org"/>\n$2</key-servers>} or die "no keyring-format element in the metadata\n";
  ' "$metadata"

  echo "== Writing verification metadata (first pass)"
  gradle "$work/home" --write-verification-metadata pgp,sha256 --export-keys "${tasks[@]}"
  echo "== Writing verification metadata (second pass)"
  gradle "$work/home" --write-verification-metadata pgp,sha256 --export-keys "${tasks[@]}"
  # Gradle writes a binary copy of the keyring too; only the armored one is
  # committed.
  rm -f gradle/verification-keyring.gpg
fi

if $check; then
  echo "== Checking a build verifies, key servers off"
  cp "$metadata" "$work/metadata.xml"
  perl -0pi -e '
    s{<key-servers>.*?</key-servers>}{<key-servers enabled="false"/>}s;
    s{(\s*)</configuration>}{$1   <key-servers enabled="false"/>$1</configuration>} unless /<key-servers enabled="false"\/>/;
  ' "$metadata"
  status=0
  gradle "$work/check-home" "${tasks[@]}" || status=$?
  cp "$work/metadata.xml" "$metadata"
  if [ "$status" -ne 0 ]; then
    echo "The check failed: see the dependency verification error above." >&2
    exit "$status"
  fi
fi

echo
git status --short -- "$metadata" "$keyring"
echo "Trusted keys: $(grep -c '<trusted-key ' "$metadata" || true)"
echo "Checksum-only components: $(grep -c '<component ' "$metadata" || true)"
if $write; then
  echo "Review the diff (a new trusted key or checksum is a new thing being trusted), then commit both files."
fi
