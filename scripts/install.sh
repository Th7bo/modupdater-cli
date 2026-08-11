#!/usr/bin/env bash
#
# Interactive installer for modupdater-cli.
#
# Finds your Prism / Modrinth instances, asks which one to set up, and writes
# everything into place. For Prism it can also fill in the launcher hooks for
# you.
#
# Just run it:   ./install.sh
#
# Written for bash 3.2 so it works on stock macOS as well as Linux.

set -u

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
JAR="${MODUPDATER_JAR:-$HERE/modupdater-cli.jar}"
INSTALL_DIR="${MODUPDATER_HOME:-$HOME/.local/share/modupdater}"

bold()  { printf '\033[1m%s\033[0m\n' "$1"; }
warn()  { printf '\033[33m%s\033[0m\n' "$1"; }
err()   { printf '\033[31m%s\033[0m\n' "$1" >&2; }
ok()    { printf '\033[32m%s\033[0m\n' "$1"; }

die() { err "$1"; exit 1; }

# ── Prerequisites ───────────────────────────────────────────────────────────

JAVA_BIN="$(command -v java || true)"
[ -n "$JAVA_BIN" ] || die "Java is not installed. Install Java 21 or newer, then run this again."

[ -f "$JAR" ] || die "Could not find modupdater-cli.jar next to this script (looked in $HERE)."

echo
bold "ModUpdater installer"
echo

# ── Find instances ──────────────────────────────────────────────────────────

# -Duser.home is passed explicitly: on Linux the JVM takes user.home from the
# passwd entry and ignores $HOME, so without this a relocated HOME is silently
# ignored and the search hits the real one.
TSV="$("$JAVA_BIN" -Duser.home="$HOME" -jar "$JAR" list-instances)"

if [ -z "$TSV" ]; then
    err "No Minecraft instances found."
    echo
    echo "This looks for Prism Launcher, MultiMC and Modrinth App in their usual"
    echo "locations. If you keep instances somewhere else, you can still set it up"
    echo "by hand — see the README."
    exit 1
fi

launchers=(); names=(); versions=(); gamedirs=(); cfgs=()
while IFS=$'\t' read -r launcher name version gamedir cfg; do
    [ -n "$launcher" ] || continue
    launchers+=("$launcher"); names+=("$name"); versions+=("$version")
    gamedirs+=("$gamedir"); cfgs+=("$cfg")
done <<< "$TSV"

count=${#names[@]}

echo "Found $count instance(s):"
echo
i=0
while [ "$i" -lt "$count" ]; do
    n=$((i + 1))
    printf '  %2d) [%s] %s  (MC %s)\n' "$n" "${launchers[$i]}" "${names[$i]}" "${versions[$i]}"
    # Two instances can share a display name, and then the folder is the only
    # thing that tells them apart — so show it, but only when it adds something.
    folder="$(basename "$(dirname "${gamedirs[$i]}")")"
    if [ "$folder" != "${names[$i]}" ]; then
        printf '      folder: %s\n' "$folder"
    fi
    i=$((i + 1))
done
echo

printf 'Which one? (number, or several like 1,3, or "all"): '
read -r selection || true
if [ -z "$selection" ]; then
    if [ -t 0 ]; then
        die "Nothing selected."
    fi
    # No answer and no terminal: whatever launched this handed us a stream
    # rather than a keyboard, so no prompt can ever be answered.
    die "Nothing selected — this installer could not read your answer.
Run it from a terminal, or download and run install.sh directly:
  https://github.com/Th7bo/modupdater-cli/releases/latest"
fi

chosen=()
if [ "$selection" = "all" ]; then
    i=0
    while [ "$i" -lt "$count" ]; do chosen+=("$i"); i=$((i + 1)); done
else
    old_ifs="$IFS"; IFS=','
    for part in $selection; do
        part="$(echo "$part" | tr -d '[:space:]')"
        case "$part" in
            ''|*[!0-9]*) IFS="$old_ifs"; die "'$part' is not a number." ;;
        esac
        if [ "$part" -lt 1 ] || [ "$part" -gt "$count" ]; then
            IFS="$old_ifs"; die "$part is not in the list."
        fi
        chosen+=("$((part - 1))")
    done
    IFS="$old_ifs"
fi

# ── Server details ──────────────────────────────────────────────────────────

SETTINGS_FILE="$INSTALL_DIR/settings.properties"
SAVED_TOKEN_FILE="$INSTALL_DIR/token"

read_property() {
    [ -f "$2" ] || return 0
    sed -n "s/^$1=//p" "$2" | head -1
}

saved_url=""
saved_token=""

[ -f "$SETTINGS_FILE" ] && saved_url="$(read_property 'base\.url' "$SETTINGS_FILE")"
[ -f "$SAVED_TOKEN_FILE" ] && saved_token="$(cat "$SAVED_TOKEN_FILE")"

# Fall back to an instance configured earlier — by a previous version of this
# installer, or by hand — so nobody is asked to dig out their token twice.
for idx in "${chosen[@]}"; do
    gd="${gamedirs[$idx]}"
    [ -n "$saved_url" ] || saved_url="$(read_property 'base\.url' "$gd/modupdater.properties")"
    [ -n "$saved_token" ] || { [ -f "$gd/mods/.modupdater/token" ] && saved_token="$(cat "$gd/mods/.modupdater/token")"; }
done

base_url=""
token=""

if [ -n "$saved_url" ] && [ -n "$saved_token" ]; then
    echo
    echo "Found existing settings:"
    echo "  Server: $saved_url"
    echo "  Token:  saved"
    printf 'Use these? [Y/n]: '
    read -r reply
    case "$reply" in
        [Nn]*) ;;
        *) base_url="$saved_url"; token="$saved_token" ;;
    esac
fi

if [ -z "$base_url" ]; then
    echo
    printf 'Server address (e.g. https://mods.example.com): '
    read -r base_url
    [ -n "$base_url" ] || die "A server address is required."
    base_url="${base_url%/}"

    printf 'Access token (paste it — it will not be shown): '
    read -rs token
    echo
    [ -n "$token" ] || die "A token is required."
fi

# ── Install the shared files ────────────────────────────────────────────────

mkdir -p "$INSTALL_DIR"
cp "$JAR" "$INSTALL_DIR/modupdater-cli.jar"
cp "$HERE/modupdater.sh" "$INSTALL_DIR/modupdater.sh"
chmod +x "$INSTALL_DIR/modupdater.sh"

# Prism stores instance.cfg through Qt's INI handling, which strips quote
# characters and drops unquoted whitespace outside them — so a value like
#     "/path/modupdater.sh" check
# is silently rewritten to /path/modupdater.shcheck the next time Prism saves,
# and the launch then fails with "process failed to start".
#
# These one-line scripts take no arguments, so the hook value is a single bare
# path with nothing in it for Qt to mangle.
for hook_mode in check apply; do
    hook_script="$INSTALL_DIR/$hook_mode.sh"
    printf '#!/usr/bin/env bash\nexec "%s" %s\n' "$INSTALL_DIR/modupdater.sh" "$hook_mode" > "$hook_script"
    chmod +x "$hook_script"
done

# Remembered centrally so setting up another instance later asks nothing. The
# token is already on disk per instance; this copy carries the same 0600.
printf 'base.url=%s\n' "$base_url" > "$SETTINGS_FILE"
printf '%s' "$token" > "$SAVED_TOKEN_FILE"
chmod 600 "$SAVED_TOKEN_FILE"

ok "Installed the updater into $INSTALL_DIR"

PRE_HOOK="$INSTALL_DIR/check.sh"
POST_HOOK="$INSTALL_DIR/apply.sh"

# ── Per-instance setup ──────────────────────────────────────────────────────

# Qt keeps an unquoted value verbatim, but eats whitespace that sits outside
# quotes. A path with no spaces therefore needs no quoting at all; one with
# spaces must be quoted as a whole, never partially.
quote_for_cfg() {
    case "$1" in
        *[[:space:]]*) printf '"%s"' "$1" ;;
        *) printf '%s' "$1" ;;
    esac
}

set_cfg_key() {
    cfg_file="$1"; cfg_key="$2"; cfg_value="$3"
    tmp="$(mktemp)"
    if grep -q "^${cfg_key}=" "$cfg_file"; then
        awk -v k="$cfg_key" -v v="$cfg_value" \
            '$0 ~ "^" k "=" { print k "=" v; next } { print }' "$cfg_file" > "$tmp"
    else
        awk -v k="$cfg_key" -v v="$cfg_value" \
            '{ print } /^\[General\]$/ && !inserted { print k "=" v; inserted = 1 }' "$cfg_file" > "$tmp"
    fi
    mv "$tmp" "$cfg_file"
}

manual_needed=0

for idx in "${chosen[@]}"; do
    launcher="${launchers[$idx]}"; name="${names[$idx]}"
    version="${versions[$idx]}"; gamedir="${gamedirs[$idx]}"; cfg="${cfgs[$idx]}"

    echo
    bold "Setting up: $name"

    if [ "$version" = "unknown" ]; then
        # Modrinth App keeps the game version in a database we don't read. If
        # this instance was set up before, reuse that answer rather than asking
        # for it again.
        version="$(read_property 'mc\.version' "$gamedir/modupdater.properties")"
        if [ -n "$version" ]; then
            echo "  Using the Minecraft version from last time: $version"
        else
            printf '  Which Minecraft version is this instance? (e.g. 1.21.4): '
            read -r version
            [ -n "$version" ] || { warn "  Skipped — no version given."; continue; }
        fi
    fi

    mods_dir="$gamedir/mods"
    mkdir -p "$mods_dir/.modupdater"

    printf 'base.url=%s\nmc.version=%s\n' "$base_url" "$version" > "$gamedir/modupdater.properties"

    printf '%s' "$token" > "$mods_dir/.modupdater/token"
    chmod 600 "$mods_dir/.modupdater/token"

    ok "  Wrote settings and token"

    if [ -n "$cfg" ] && [ -f "$cfg" ]; then
        printf '  Set up the launcher hooks automatically? [Y/n]: '
        read -r reply
        case "$reply" in
            [Nn]*)
                manual_needed=1
                echo "  Skipped. Add these yourself under Settings > Custom commands:"
                echo "    Pre-launch: $PRE_HOOK"
                echo "    Post-exit:  $POST_HOOK"
                ;;
            *)
                cp "$cfg" "$cfg.modupdater-backup"
                set_cfg_key "$cfg" "OverrideCommands" "true"
                set_cfg_key "$cfg" "PreLaunchCommand" "$(quote_for_cfg "$PRE_HOOK")"
                set_cfg_key "$cfg" "PostExitCommand" "$(quote_for_cfg "$POST_HOOK")"
                ok "  Launcher hooks configured (backup at $(basename "$cfg").modupdater-backup)"
                warn "  Close and reopen Prism so it picks up the change."
                ;;
        esac
    else
        # Modrinth App sets none of Prism's INST_* variables, so the shared
        # wrappers would fall back to the current directory and read the mods of
        # whatever the launcher happened to start in. These bake this instance's
        # path in, and still take no arguments for the launcher to mangle.
        manual_needed=1

        for mode in check apply; do
            target="$mods_dir/.modupdater/$mode.sh"
            {
                echo '#!/usr/bin/env bash'
                echo "export MODUPDATER_MODS_DIR=\"$mods_dir\""
                echo "exec \"$INSTALL_DIR/modupdater.sh\" $mode"
            } > "$target"
            chmod +x "$target"
        done

        echo "  Modrinth App can't be configured automatically."
        echo "  Open the instance's Options > Hooks and paste:"
        echo "    Pre-launch: $mods_dir/.modupdater/check.sh"
        echo "    Post-exit:  $mods_dir/.modupdater/apply.sh"
        echo "  If a field will not stick: click outside it, then fully quit"
        echo "  and reopen Modrinth App before checking again."
    fi

    printf '  Also install the in-game notifier, so updates show up while you play? [Y/n]: '
    read -r want_mod
    case "$want_mod" in
        [Nn]*)
            echo "  Skipped. Updates will still be offered before each launch."
            ;;
        *)
            # Pulled from the server like any other mod, so it updates itself
            # afterwards rather than needing a manual download every release.
            "$JAVA_BIN" -jar "$INSTALL_DIR/modupdater-cli.jar" \
                install-mod --mods-dir "$mods_dir" 2>&1 | sed 's/^/    /'
            ;;
    esac

    echo "  Checking the connection..."
    "$JAVA_BIN" -Djava.awt.headless=true -jar "$INSTALL_DIR/modupdater-cli.jar" \
        check --mods-dir "$mods_dir" 2>&1 | sed 's/^/    /'
done

echo
bold "Done."
if [ "$manual_needed" -eq 1 ]; then
    echo "Some instances still need the hooks pasted in by hand — see above."
fi
echo "Next time you launch, you'll be asked about any available updates."
