# modupdater-cli

Checks the mods in a Minecraft instance against a [ModUpdater](https://github.com/Th7bo/ModUpdater) server before the game launches, shows you what changed, and installs the builds you accept.

Standalone Java — no Minecraft dependency, no mod to install. It runs from your launcher's hook, when nothing has the JAR files open yet.

## Requirements

- Java 21 or newer
- A ModUpdater server with `CLIENT_API_TOKEN` configured
- **Prism Launcher** or **Modrinth App**

> The official Minecraft launcher has no pre-launch hook, so it cannot be supported.

## Install

One command. It downloads the latest version and walks you through the rest.

**Linux / macOS**

```bash
curl -fsSL https://mod.th7bo.dev/install | bash
```

**Windows** — in PowerShell

```powershell
irm https://mod.th7bo.dev/install.ps1 | iex
```

<details>
<summary>Or install from the zip by hand</summary>

Download `modupdater-installer.zip` from [the latest release](https://github.com/Th7bo/modupdater-cli/releases/latest), extract it **somewhere you'll keep it**, and run `install.sh` (Linux/macOS) or double-click `install.bat` (Windows).
</details>

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
6. If [mod profiles](#mod-profiles) are switched on for the instance, applies the chosen one

`apply` (post-exit)

1. If the session after an update lasted under two minutes, treats it as a failed launch and restores the previous JARs
2. Otherwise confirms the update
3. Runs `relaunch.command` if one is set

### Rollback

The platform builds from upstream development commits, so a JAR that crashes on init is an ordinary outcome. Replaced JARs are kept in `mods/.modupdater/backup/` until a launch proves the new ones work. Exactly one generation is kept.

## Mod profiles

**Off by default.** If you have never asked for this, nothing about it applies to you: no extra prompt, no mods moved, no files created, no `profiles.json` needed. Skip this section.

It exists for the case where one instance holds every SkyBlock mod you own and a weaker machine cannot run all of them at once. Turn it on and you pick a set at launch — dungeon mods for dungeons, mining mods for mining, a small one when you want frames.

It is per instance, so the same setup can have it on for a laptop and off for a desktop.

### Turning it on

```bash
modupdater profile enable
```

That sets `profiles.enabled=true` in the instance's `modupdater.properties` — leaving your comments and everything else in it alone — and writes a starter `mods/.modupdater/profiles.json` with every mod you have in one group. Nothing moves until you split that group up, so enabling it cannot change which mods load.

`modupdater profile disable` reverses it, and brings any stored mods back into `mods/` first so nothing is left behind. Your `profiles.json` is kept either way.

The rest of the settings live in `modupdater.properties`:

```properties
profiles.enabled=true    # what `profile enable` writes
profile.default=general  # used when nothing is remembered
profile.prompt=true      # ask at launch, or just apply the default
profile.remember=true    # start from what you picked last time
```

Then edit `mods/.modupdater/profiles.json` into something like:

```json
{
  "groups": {
    "base":        ["fabric-api", "skyhanni", "modupdater"],
    "performance": ["sodium", "lithium", "ferritecore"],
    "qol":         ["firmament", "dulkirmod"],
    "dungeons":    ["bettermap", "dungeonrooms"],
    "mining":      ["coleweight", "skyblockcollectiontracker"]
  },
  "profiles": {
    "general":  { "description": "Normal SkyBlock",  "include": ["base", "performance", "qol"] },
    "dungeons": { "description": "Dungeon mods on",  "include": ["base", "performance", "qol", "dungeons"] },
    "mining":   { "description": "Mining mods on",   "include": ["base", "performance", "mining"] },
    "lite":     { "description": "Maximum FPS",      "include": ["base", "performance"], "ungrouped": "disable" },
    "everything": { "description": "All of it",      "includeAll": true }
  }
}
```

A profile is a **composition of groups**, not a list of exceptions. `mining` is `base + performance + mining`. Install a new general-purpose mod, add it to `base` once, and every profile built on `base` picks it up — you never edit five profiles for one mod.

For the one-offs that do not deserve a group:

| Key | Meaning |
|---|---|
| `include` | group names to combine |
| `add` | extra mod ids, on top of the groups |
| `remove` | mod ids to drop, whatever the groups say |
| `includeAll` | every installed mod, ignoring groups |
| `ungrouped` | `keep` (default) or `disable` — see below |

Mods are named by their **mod id** — the `id` in the JAR's `fabric.mod.json`, the same identifier the updater matches against the server. Not the filename, so a build that renames `BetterMap-1.6.2.jar` to `BetterMap-1.7.0.jar` changes nothing.

A mod that appears in no group and in no profile's `add`/`remove` stays **active in every profile**, and is listed in the log. Installing a mod and forgetting to file it should not make it vanish from the game. Set `"ungrouped": "disable"` on a profile — usually the lean one — when you want the opposite.

### How it is stored

```
mods/
├── skyhanni-1.2.5.jar          ← active: what the game loads
├── sodium-0.6.0.jar
└── .modupdater/
    ├── profiles.json           ← your groups and profiles
    ├── profile.json            ← what is applied now, and what to offer next
    ├── inactive/               ← installed, but not in the current profile
    │   └── coleweight-2.0.jar
    └── backup/                 ← the previous build, until a launch confirms
```

One JAR per mod. Switching profiles moves files between `mods/` and `mods/.modupdater/inactive/` — nothing is copied per profile, and nothing is ever deleted because a profile leaves it out.

**A mod in `inactive/` is still installed, and still gets updates.** That is the point: switch to Dungeons after a month away and you get the current BetterMap, not the one from before. Its new build lands in `inactive/`, so updating a mod never switches it on.

### Commands

```bash
modupdater profile enable      # turn it on for this instance, with a starter config
modupdater profile list        # groups, profiles, and what is applied now
modupdater profile current
modupdater profile use mining  # switch without launching
modupdater profile disable     # turn it off, putting every stored mod back
```

Same executable, same launcher hooks — `check` before launch, `apply` after exit, exactly as before. There is no second tool and no extra hook to add.

### Worth knowing

- **Dependencies are not resolved.** If a mod needs a library, keep the library in a group every profile includes — `base` is what it is for. Nothing warns you about a missing dependency, because the manifest does not describe them.
- A profile name that no longer exists, an unknown mod id, an unreadable `profiles.json` — each is logged and launches with everything active. Never a blocked launch.
- A mod whose JAR turns up in both `mods/` and `inactive/` is left alone by profiles and reported, since which copy is real is not ours to guess. Delete the one you do not want.
- Turn it off with `modupdater profile disable` rather than by editing the property: that puts every stored mod back into `mods/` first. Setting `profiles.enabled=false` by hand leaves them where they are, and the game will not load them.

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
| `--profiles-enabled` | `profiles.enabled` | `MODUPDATER_PROFILES_ENABLED` | Mod profiles for this instance (default off) |
| `--profile` | `profile.default` | `MODUPDATER_PROFILE` | Profile to use when nothing is remembered |
| `--profile-prompt` | `profile.prompt` | `MODUPDATER_PROFILE_PROMPT` | Ask at launch (default yes) |
| `--profile-remember` | `profile.remember` | `MODUPDATER_PROFILE_REMEMBER` | Offer last launch's choice first (default yes) |

Logs go to `mods/.modupdater/log.txt`. The token is redacted from every line.

## Build

```bash
./gradlew build      # runs the tests, and produces both of:
                     #   build/libs/modupdater-cli-<version>.jar
                     #   build/dist/modupdater-installer.zip
```

## Release

```bash
./gradlew publishInstaller
```

Builds the zip and attaches it to the `v<version>` GitHub release, creating it if needed and replacing the asset if it already exists. The bootstrap scripts fetch `/releases/latest/download/modupdater-installer.zip`, so publishing is what makes a fix reach people — bump `version` in `build.gradle.kts` first if the release already exists and you want a new tag.

## Not supported

- Installing mods you don't already have — this updates, it doesn't install
- Mods the platform doesn't build (Modrinth/CurseForge sources)
- Dependency resolution between mods, profiles included
- Launchers without pre-launch/post-exit hooks
