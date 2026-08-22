#!/usr/bin/env sh
set -eu
ROOT=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
LAUNCHER="$ROOT/LiquidCopy-Launcher.jar"

if [ ! -f "$LAUNCHER" ]; then
  echo "ERROR: Missing $LAUNCHER" >&2
  exit 2
fi

if [ -n "${JAVA_HOME:-}" ] && [ -x "$JAVA_HOME/bin/java" ]; then
  JAVA_BIN="$JAVA_HOME/bin/java"
elif command -v java >/dev/null 2>&1; then
  JAVA_BIN=$(command -v java)
else
  echo "ERROR: LiquidCopy requires Java 21; java was not found." >&2
  exit 2
fi

JAVA_SETTINGS=$($JAVA_BIN -XshowSettings:properties -version 2>&1) || {
  echo "ERROR: Failed to run $JAVA_BIN." >&2
  exit 2
}
if ! printf '%s\n' "$JAVA_SETTINGS" | grep -Eq 'java\.specification\.version[[:space:]]*=[[:space:]]*21'; then
  echo "ERROR: LiquidCopy requires Java 21; $JAVA_BIN is a different version." >&2
  printf '%s\n' "$JAVA_SETTINGS" | head -n 1 >&2
  exit 2
fi

exec "$JAVA_BIN" -jar "$LAUNCHER"
