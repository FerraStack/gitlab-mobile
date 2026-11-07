#!/usr/bin/env bash
# Publish a PR's screenshots to the orphan `ci-screenshots` branch and upsert one
# sticky PR comment (found by its hidden marker) that shows them.
#
# Usage: post-screenshots.sh <dir with PNGs and changed.txt>
# Env: GH_TOKEN, REPO, PR, RUN_ID, RUN_ATTEMPT, SHA
set -euo pipefail

shots=$(realpath "$1")
marker='<!-- ferra-screenshots -->'
dir="pr-$PR/$RUN_ID-$RUN_ATTEMPT"
work=$(mktemp -d)

if ! find "$shots" -name '*.png' | grep -q .; then
  echo "No screenshots in $shots; nothing to post."
  exit 0
fi

# Push with retries: another PR's job may push to the branch at the same time.
for attempt in 1 2 3 4 5; do
  rm -rf "$work/pub"
  git init -q "$work/pub"
  cd "$work/pub"
  git remote add origin "https://x-access-token:${GH_TOKEN}@github.com/${REPO}.git"
  if git fetch -q --depth 1 origin ci-screenshots 2>/dev/null; then
    git checkout -q -b ci-screenshots FETCH_HEAD
  else
    git checkout -q --orphan ci-screenshots
  fi
  mkdir -p "$dir"
  find "$shots" -name '*.png' -exec cp {} "$dir"/ \;
  git add -A
  git -c user.name="github-actions[bot]" \
    -c user.email="41898282+github-actions[bot]@users.noreply.github.com" \
    commit -qm "Screenshots for PR #$PR (run $RUN_ID, attempt $RUN_ATTEMPT)"
  if git push -q origin ci-screenshots; then
    break
  fi
  if [[ $attempt == 5 ]]; then
    echo "::error::Could not push to ci-screenshots"
    exit 1
  fi
  sleep $((attempt * 5))
done

base="https://raw.githubusercontent.com/${REPO}/ci-screenshots/${dir}"
changed_file="$shots/changed.txt"
touch "$changed_file"
is_changed() { grep -qxF "$1" "$changed_file"; }
img() { echo "<img src=\"$base/$1\" width=\"240\" alt=\"$2\">"; }

# One row per screen: name_light.png / name_dark.png; other PNGs get a row of their own.
rows() { # $1 = "changed" | "unchanged"
  local name light dark state lc dc
  for name in $(find "$dir" -maxdepth 1 -name '*.png' -printf '%f\n' \
    | sed -E 's/_(light|dark)\.png$//; s/\.png$//' | sort -u); do
    light="${name}_light.png"
    dark="${name}_dark.png"
    if [[ ! -f "$dir/$light" && ! -f "$dir/$dark" ]]; then
      light="$name.png"
      dark=""
    fi
    state=unchanged
    if is_changed "$light" || { [[ -n $dark ]] && is_changed "$dark"; }; then state=changed; fi
    [[ $state == "$1" ]] || continue
    lc="—"; dc="—"
    [[ -f "$dir/$light" ]] && lc=$(img "$light" "$name, light")
    [[ -n $dark && -f "$dir/$dark" ]] && dc=$(img "$dark" "$name, dark")
    echo "| \`$name\` | $lc | $dc |"
  done
}

stale_file="$shots/stale.txt"
touch "$stale_file"
stale=$(find "$dir" -maxdepth 1 -name '*.png' -printf '%f\n' | grep -xFf "$stale_file" | sort || true)

changed_rows=$(rows changed)
unchanged_rows=$(rows unchanged)
{
  echo "$marker"
  echo "### Screenshots"
  echo "Commit ${SHA:0:7} · [run $RUN_ID](https://github.com/${REPO}/actions/runs/${RUN_ID}) · Roborazzi on Robolectric with invented data"
  echo
  if [[ -n $changed_rows ]]; then
    echo "**Changed by this pull request** (different from the base branch):"
    echo
    echo "| Screen | Light | Dark |"
    echo "|---|---|---|"
    echo "$changed_rows"
  else
    echo "No screenshot differs from the base branch."
  fi
  if [[ -n $stale ]]; then
    echo
    echo "> [!NOTE]"
    echo "> Not yet committed in \`app/src/test/screenshots\` (record with \`./gradlew :app:recordRoborazziDebug\`, or copy them from this run's \`screenshots-…\` artifact): $(echo "$stale" | sed 's/.*/`&`/' | paste -sd, - | sed 's/,/, /g')"
  fi
  if [[ -n $unchanged_rows ]]; then
    echo
    echo "<details><summary>Unchanged screens</summary>"
    echo
    echo "| Screen | Light | Dark |"
    echo "|---|---|---|"
    echo "$unchanged_rows"
    echo
    echo "</details>"
  fi
} > "$work/body.md"

id=$(gh api "repos/${REPO}/issues/${PR}/comments" --paginate \
  --jq ".[] | select(.body | contains(\"$marker\")) | .id" | head -n1)
if [[ -n $id ]]; then
  gh api -X PATCH "repos/${REPO}/issues/comments/${id}" -F body=@"$work/body.md" > /dev/null
else
  gh api "repos/${REPO}/issues/${PR}/comments" -F body=@"$work/body.md" > /dev/null
fi
echo "Posted $(find "$dir" -name '*.png' | wc -l) screenshots to PR #$PR."
