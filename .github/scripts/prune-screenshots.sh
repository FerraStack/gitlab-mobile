#!/usr/bin/env bash
# Remove a closed PR's screenshots from the `ci-screenshots` branch. The branch is
# rewritten as a single commit so pruned images stop taking space in the repository.
#
# Env: GH_TOKEN, REPO, PR
set -euo pipefail

work=$(mktemp -d)
for attempt in 1 2 3 4 5; do
  rm -rf "$work/pub"
  git init -q "$work/pub"
  cd "$work/pub"
  git remote add origin "https://x-access-token:${GH_TOKEN}@github.com/${REPO}.git"
  if ! git fetch -q --depth 1 origin ci-screenshots 2>/dev/null; then
    echo "No ci-screenshots branch; nothing to prune."
    exit 0
  fi
  head=$(git rev-parse FETCH_HEAD)
  git checkout -q --detach "$head"
  if [[ ! -d "pr-$PR" ]]; then
    echo "No screenshots for PR #$PR."
    exit 0
  fi
  git rm -rq -- "pr-$PR"
  tree=$(git write-tree)
  commit=$(git -c user.name="github-actions[bot]" \
    -c user.email="41898282+github-actions[bot]@users.noreply.github.com" \
    commit-tree "$tree" -m "Screenshots of open pull requests (pruned PR #$PR)")
  # Fails instead of overwriting if another job pushed in the meantime.
  if git push -q --force-with-lease="ci-screenshots:$head" origin "$commit:refs/heads/ci-screenshots"; then
    echo "Pruned screenshots of PR #$PR."
    exit 0
  fi
  sleep $((attempt * 5))
done
echo "::error::Could not prune ci-screenshots"
exit 1
