#!/usr/bin/env bash
# Builds one branch holding every open Dependabot pull request against $BASE,
# for .github/workflows/combine-dependabot.yml. Run from a clone whose
# "origin" is the GitHub repository, with gh signed in. It only changes the
# local clone: it never pushes.
#
#   BASE=dev scripts/combine_dependabot.sh
#
# Each pull request's branch is merged in turn, so GitHub sees its commits
# land when the combined pull request is merged. Two Dependabot pull requests
# often change neighbouring lines (gradle/libs.versions.toml), which git
# can't merge; then the pull request's own change is applied instead, with no
# context lines, as a commit of its own. Version bumps only replace lines, so
# that is safe, and git apply still refuses if the lines it replaces differ.
# A pull request that neither works for is left out and listed.
#
# Writes, in $OUT_DIR (default: a temporary directory, printed at the end):
#   body.md     the combined pull request's description
#   included    the numbers of the pull requests merged, one per line
#   gradle      "true" if anything Gradle reads changed, else "false"
set -euo pipefail

BASE=${BASE:-dev}
OUT_DIR=${OUT_DIR:-$(mktemp -d)}
mkdir -p "$OUT_DIR"

git fetch --quiet origin "+refs/heads/$BASE:refs/remotes/origin/$BASE"
git switch --quiet --force-create combine-dependabot-work "origin/$BASE"

prs=$(gh pr list --base "$BASE" --state open --limit 100 \
  --json number,title,headRefName,url,author \
  --jq '[.[] | select(.author.login == "app/dependabot" and (.headRefName | startswith("dependabot/")))] | sort_by(.number)')

included=()
skipped=()
included_lines=()
skipped_lines=()

while IFS=$'\t' read -r number head title url; do
  [ -n "$number" ] || continue
  echo "::group::#$number $title"
  git fetch --quiet origin "+refs/heads/$head:refs/remotes/origin/$head"
  how=""
  if git merge --quiet --no-ff --no-edit -m "Merge #$number: $title" "origin/$head"; then
    how="merged"
  else
    git merge --abort
    base_commit=$(git merge-base HEAD "origin/$head")
    if git diff --unified=0 "$base_commit" "origin/$head" | git apply --unidiff-zero --index; then
      git commit --quiet -m "$title (#$number)" -m "Applied from $url, whose branch didn't merge cleanly with the pull requests before it."
      how="applied"
    else
      git reset --quiet --hard HEAD
    fi
  fi
  if [ -n "$how" ]; then
    included+=("$number")
    if [ "$how" = applied ]; then
      included_lines+=("- #$number $title (applied as a patch: it conflicted with an earlier one)")
    else
      included_lines+=("- #$number $title")
    fi
  else
    skipped+=("$number")
    skipped_lines+=("- #$number $title")
  fi
  echo "#$number: ${how:-skipped}"
  echo "::endgroup::"
done < <(jq -r '.[] | [.number, .headRefName, .title, .url] | @tsv' <<<"$prs")

printf '%s\n' "${included[@]+"${included[@]}"}" | sed '/^$/d' > "$OUT_DIR/included"

if git diff --name-only "origin/$BASE" HEAD |
    grep -Eq '^(gradle/|.*\.gradle\.kts$|gradle\.properties$)'; then
  echo true > "$OUT_DIR/gradle"
else
  echo false > "$OUT_DIR/gradle"
fi

{
  echo "Every open Dependabot pull request against \`$BASE\`, in one branch, so CI runs once."
  echo "Made by the Combine Dependabot workflow; it rebuilds this branch from \`$BASE\` each run, so don't push to it."
  echo
  if [ ${#included_lines[@]} -gt 0 ]; then
    echo "**Included**"
    echo
    printf '%s\n' "${included_lines[@]}"
    echo
  fi
  if [ ${#skipped_lines[@]} -gt 0 ]; then
    echo "**Left out** (conflicts with the ones above; they'll be picked up once Dependabot rebases them)"
    echo
    printf '%s\n' "${skipped_lines[@]}"
    echo
  fi
  if [ "$(cat "$OUT_DIR/gradle")" = true ]; then
    echo "\`gradle/verification-metadata.xml\` and \`gradle/verification-keyring.keys\` are regenerated in the last commit (Dependency verification workflow). Review that diff: a new trusted key or checksum is a new thing being trusted."
    echo
  fi
  echo "Once this is merged, Dependabot closes the pull requests above on its next run (the merged ones are marked merged straight away if this is merged with a merge commit)."
} > "$OUT_DIR/body.md"

echo "Included: ${included[*]:-none}. Left out: ${skipped[*]:-none}."
echo "Output in $OUT_DIR"
