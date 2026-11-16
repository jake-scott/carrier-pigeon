#!/usr/bin/env bash
# Moves one exact Play bundle from one track to the next, in one Play edit:
# internal -> closed for an rc tag, closed -> production for a release tag.
# release.yml calls it; nothing is uploaded or built here.
#
#   play_promote.sh <from track> <to track> <release name> <version code> <bundle sha256>
#
# The version code and SHA-256 come from the main run that built the tagged
# commit (release.yml, job `bundle`, which checks the bundle's build
# provenance). This script only promotes when Play agrees:
#   - the current completed release on <from track> is exactly that version
#     code, and
#   - the bundle Play holds under that code has that SHA-256.
# Release names are only shown, never trusted: anything that can write to a
# testing track can name a release whatever it likes, but it can't reuse a
# version code or change the bundle behind one.
#
# The checks and the promotion are one edit, so a track that changes in the
# meantime makes the commit fail instead of promoting something else. If
# <to track> already has that version code completed, it does nothing, so a
# failed run can be re-run.
#
# Environment:
#   TOKEN          access token for the Play Developer API
#   PLAY_PACKAGE   the app's package name
set -euo pipefail

if [ $# -ne 5 ]; then
  sed -n '6p' "$0" | sed 's/^# //' >&2
  exit 2
fi
from=$1 to=$2 name=$3 code=$4 want_sha=$5
: "${TOKEN:?set TOKEN}"
: "${PLAY_PACKAGE:?set PLAY_PACKAGE}"
[[ $code =~ ^[0-9]+$ ]] || { echo "version code must be a number" >&2; exit 2; }
[[ $want_sha =~ ^[0-9a-f]{64}$ ]] || { echo "bundle sha256 must be 64 hex digits" >&2; exit 2; }

api="https://androidpublisher.googleapis.com/androidpublisher/v3/applications/$PLAY_PACKAGE"
auth=(--header "Authorization: Bearer $TOKEN")
# On an HTTP error, show Google's error body (it names what's missing)
# instead of only curl's status line.
call() {
  local out
  if ! out=$(curl --silent --show-error --fail-with-body "${auth[@]}" "$@"); then
    echo "::error title=API call failed::${*: -1}: $(jq -c '.error // .' <<<"$out" 2>/dev/null || echo "$out")" >&2
    return 1
  fi
  printf '%s' "$out"
}

edit=$(call --request POST "$api/edits" | jq -r .id)
abandon() {
  call --request DELETE "$api/edits/$edit" >/dev/null || true
}

# A track can hold a completed release beside drafts or a halted one; the
# completed one is what testers or users get now.
current=$(call "$api/edits/$edit/tracks/$from" |
  jq -c '[.releases[]? | select(.status == "completed")][0] // empty')
if [ -z "$current" ]; then
  abandon
  echo "::error title=Nothing on $from::$from has no completed release to promote."
  exit 1
fi
current_name=$(jq -r '.name // "(unnamed)"' <<<"$current")
current_codes=$(jq -c '.versionCodes' <<<"$current")
if [ "$current_codes" != "[\"$code\"]" ]; then
  abandon
  echo "::error title=$from has a different build::$from has $current_name (version codes $current_codes), not version code $code, the one main built from this commit. Promote the build testers have now: tag the commit it came from, or get this commit onto $from first."
  exit 1
fi

play_sha=$(call "$api/edits/$edit/bundles" |
  jq -r --arg c "$code" '[.bundles[]? | select((.versionCode | tostring) == $c)][0].sha256 // empty')
if [ "$play_sha" != "$want_sha" ]; then
  abandon
  echo "::error title=Bundle mismatch::Play's bundle for version code $code has SHA-256 ${play_sha:-none}, but main built $want_sha. Not promoting."
  exit 1
fi

already=$(call "$api/edits/$edit/tracks/$to" |
  jq -r --arg c "$code" '[.releases[]? | select(.status == "completed" and .versionCodes == [$c])] | length')
if [ "$already" != 0 ]; then
  abandon
  echo "::notice title=Already promoted::Version code $code is already the completed release on $to."
  echo "Version code $code was already on \`$to\`." >> "${GITHUB_STEP_SUMMARY:-/dev/null}"
  exit 0
fi

echo "Promoting $current_name (version code $code) from $from to $to as $name"
call --request PUT --header "Content-Type: application/json" \
  --data "$(jq -n --arg n "$name" --arg c "$code" \
    '{releases: [{name: $n, versionCodes: [$c], status: "completed"}]}')" \
  "$api/edits/$edit/tracks/$to" >/dev/null
call --request POST "$api/edits/$edit:commit" >/dev/null
{
  echo "### Google Play"
  echo "$name (version code $code, from $current_name on \`$from\`) promoted to \`$to\`"
} >> "${GITHUB_STEP_SUMMARY:-/dev/null}"
