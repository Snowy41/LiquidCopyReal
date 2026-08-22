#!/usr/bin/env sh
set -eu
ROOT=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
exec java -jar "$ROOT/LiquidCopy-Launcher.jar"
