#!/usr/bin/env bash
# Pre-launch / post-exit hook for Prism Launcher and Modrinth App.
#
# Take no arguments. Prism does not escape $INST_MC_DIR and it cannot be quoted
# in the hook field, so any instance path containing a space breaks if the value
# is passed on the hook's command line. Reading it here, inside the script, is
# the only place quoting works.
#
# Prism      → Settings ▸ Custom commands
#   Pre-launch:  /path/to/modupdater.sh check
#   Post-exit:   /path/to/modupdater.sh apply
#
# Modrinth App → Instance settings ▸ Hooks
#   Pre-launch:  /path/to/modupdater.sh check
#   Post-exit:   /path/to/modupdater.sh apply
#
# Never exits non-zero except when the updater deliberately cancels the launch:
# a failing pre-launch hook blocks the game from starting.

set -u

# Everything is forwarded, so the same wrapper serves the launcher hooks and a
# person at a terminal running "modupdater.sh profile use dungeons".
if [ "$#" -eq 0 ]; then
    set -- check
fi

# Where this script and the JAR live.
HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
JAR="${MODUPDATER_JAR:-$HERE/modupdater-cli.jar}"

# Prism exports this; Modrinth App does not, and neither does a shell. Left
# unset the updater falls back to ./mods on its own — and it has to be unset
# rather than filled in with "$PWD/mods", because that is how "modupdater
# profile enable" run from anywhere knows to offer the instance menu instead of
# acting on a mods folder that does not exist.
if [ -n "${INST_MC_DIR:-}" ]; then
    MODS_DIR="$INST_MC_DIR/mods"
elif [ -n "${MODUPDATER_MODS_DIR:-}" ]; then
    MODS_DIR="$MODUPDATER_MODS_DIR"
else
    MODS_DIR=""
fi

JAVA="${INST_JAVA:-java}"

if [ ! -f "$JAR" ]; then
    echo "[modupdater] JAR not found at $JAR — skipping" >&2
    exit 0
fi

if [ -n "$MODS_DIR" ]; then
    "$JAVA" -jar "$JAR" "$@" --mods-dir "$MODS_DIR"
else
    "$JAVA" -jar "$JAR" "$@"
fi
STATUS=$?

# 1 means the user chose to cancel the launch; anything else is an updater
# problem and must not stop the game.
if [ "$STATUS" -eq 1 ]; then
    exit 1
fi

exit 0
