# modupdater-cli

Checks the mods in a Minecraft instance against a [ModUpdater](https://github.com/Th7bo/ModUpdater) server before the game launches, shows you what changed, and installs the builds you accept.

Standalone Java — no Minecraft dependency, no mod to install. It runs from your launcher's hook, when nothing has the JAR files open yet.

## Requirements

- Java 21 or newer
- A ModUpdater server with `CLIENT_API_TOKEN` configured
- **Prism Launcher** or **Modrinth App**

> The official Minecraft launcher has no pre-launch hook, so it cannot be supported.

## Install

Get `modupdater-installer.zip`, extract it **somewhere you'll keep it**, and run the installer:

- **Windows** — double-click `install.bat`
- **Linux / macOS** — `./install.sh` in a terminal

It finds your instances, shows them as a list, and asks which to set up:

```
Found 3 instance(s):

   1) [Prism] Skyblock New  (MC 1.21.11)
   2) [Prism] Th7bo  (MC 1.21.6)
   3) [Modrinth] Fabric 1.21.4  (MC unknown)

Which one? (number, or several like 1,3, or "all"):
```

Then it asks for the server address and your access token, writes everything into place, and — for Prism — fills in the launcher hooks for you. Finally it checks the connection so you know it works before you launch.

Ask whoever runs the server for the **server address** and the **access token**.

> Close Prism before running the installer, and reopen it afterwards. Prism rewrites `instance.cfg` when it exits and would overwrite the change.

Nothing else to do — next time you press Play, you'll be asked about any updates.

### If your launcher isn't found

The installer looks in the usual locations for Prism, MultiMC and Modrinth App. If you keep instances elsewhere, set it up by hand:

1. Put `modupdater-cli.jar` and the wrapper script somewhere permanent.
2. Write your token, readable only by you:

   ```bash
   mkdir -p /path/to/instance/minecraft/mods/.modupdater
   printf 'your-token' > /path/to/instance/minecraft/mods/.modupdater/token
   chmod 600 /path/to/instance/minecraft/mods/.modupdater/token
   ```

   The token is never accepted as a command-line flag — process arguments are readable by every process on the machine.

3. Create `modupdater.properties` in the **instance** directory (one level above `mods/`):

   ```properties
   base.url=https://mods.example.com
   mc.version=1.21.4
   # Optional. Prism only — Modrinth App has no CLI to launch an instance.
   relaunch.command=prismlauncher --launch Fabric 1.21.4
   ```

   `mc.version` must be the exact Minecraft version of the instance. It is matched literally against what the server publishes, so `1.21.4` will not match a build published for `1.21.5`.

4. Add the hooks yourself, as below.

## Wire it into your launcher

The installer does this for Prism. Do it by hand for Modrinth App, or if you skipped that step.

### Prism Launcher

Instance ▸ Edit ▸ Settings ▸ **Custom commands**

| Field | Value |
|---|---|
| Pre-launch command | `/path/to/modupdater.sh check` |
| Post-exit command | `/path/to/modupdater.sh apply` |

Prism exports `$INST_MC_DIR`, so the script finds `mods/` on its own.

### Modrinth App

Instance ▸ Options ▸ **Hooks**

| Field | Value |
|---|---|
| Pre-launch | `/path/to/modupdater.sh check` |
| Post-exit | `/path/to/modupdater.sh apply` |

Modrinth App does not export instance paths, so either set `MODUPDATER_MODS_DIR`, or rely on the launcher's working directory being the instance folder.

Modrinth App has [no command to launch a profile](https://github.com/modrinth/code/issues/2985), so leave `relaunch.command` unset there and press Play again after an update.

## What it does

`check` (pre-launch)

1. Rolls back the previous update if the session after it failed
2. Fetches the manifest and scans `mods/`
3. Offers only mods that are installed, built for this exact Minecraft version, and whose SHA-256 differs from what you have
4. Shows the list with the commit message behind each build
5. Downloads, **verifies the checksum before touching `mods/`**, then swaps the files

`apply` (post-exit)

1. If the session after an update lasted under two minutes, treats it as a failed launch and restores the previous JARs
2. Otherwise confirms the update
3. Runs `relaunch.command` if one is set

### Rollback

The platform builds from upstream development commits, so a JAR that crashes on init is an ordinary outcome. Replaced JARs are kept in `mods/.modupdater/backup/` until a launch proves the new ones work. Exactly one generation is kept.

## Exit codes

`0` for everything except one case: `1` means **you** chose "Cancel launch" in the dialog.

Server down, token rejected, endpoint unconfigured, unreadable manifest, no display, nothing to update, or you clicked "Launch without updating" — all exit `0`. A non-zero exit from a pre-launch hook stops the game from starting, which looks like a broken launcher rather than a broken updater.

## Configuration reference

Resolution order: command-line flag, then `modupdater.properties`, then environment.

| Flag | Property | Environment | Meaning |
|---|---|---|---|
| `--base-url` | `base.url` | `MODUPDATER_BASE_URL` | ModUpdater server root |
| `--mods-dir` | — | `MODUPDATER_MODS_DIR` | Path to `mods/` (default `./mods`) |
| `--mc` | `mc.version` | `MODUPDATER_MC_VERSION` | Instance's Minecraft version |
| `--token-file` | `token.file` | `MODUPDATER_TOKEN_FILE` | Token file (default `<mods>/.modupdater/token`) |
| `--relaunch-command` | `relaunch.command` | `MODUPDATER_RELAUNCH_COMMAND` | Run after a successful session |
| — | — | `MODUPDATER_TOKEN` | Token directly, instead of a file |

Logs go to `mods/.modupdater/log.txt`. The token is redacted from every line.

## Build

```bash
./gradlew build      # runs the tests, and produces both of:
                     #   build/libs/modupdater-cli-<version>.jar
                     #   build/dist/modupdater-installer.zip   <- hand this out
```

## Not supported

- Installing mods you don't already have — this updates, it doesn't install
- Mods the platform doesn't build (Modrinth/CurseForge sources)
- Dependency resolution between mods
- Launchers without pre-launch/post-exit hooks
