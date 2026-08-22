#!/usr/bin/env sh
set -eu

BASELINE_COMMIT="10ab071612562ed5fba91cf3a4f05417240135a8"
BASELINE_TREE="98e50c9e30211070b1759431630540d1df532356"

ROOT=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd -P)
GIT_ROOT=$(CDPATH= cd -- "$(git -C "$ROOT" rev-parse --show-toplevel)" && pwd -P)

if [ "$ROOT" != "$GIT_ROOT" ]; then
  echo "ROLLBACK ERROR: script is not at the repository root" >&2
  exit 2
fi

git -C "$ROOT" reset --hard "$BASELINE_COMMIT"
git -C "$ROOT" clean -fdx \
  -e ROLLBACK.sh \
  -e VERIFICATION.txt \
  -e LiquidCopy-1.21.11.patch

HEAD=$(git -C "$ROOT" rev-parse HEAD)
TREE=$(git -C "$ROOT" rev-parse 'HEAD^{tree}')
TRACKED_STATUS=$(git -C "$ROOT" status --porcelain --untracked-files=no)

if [ "$HEAD" != "$BASELINE_COMMIT" ] || [ "$TREE" != "$BASELINE_TREE" ] || [ -n "$TRACKED_STATUS" ]; then
  echo "ROLLBACK ERROR: baseline verification failed" >&2
  exit 3
fi

echo "ROLLBACK OK"
echo "HEAD=$HEAD"
echo "TREE=$TREE"
echo "TRACKED_STATUS=CLEAN"
