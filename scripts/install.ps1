# Interactive installer for modupdater-cli (Windows).
#
# Finds your Prism / Modrinth instances, asks which one to set up, writes the
# settings and token, and can fill in Prism's launcher hooks for you.
#
# Normally started by install.bat rather than run directly.

$ErrorActionPreference = 'Stop'

function Write-Ok   ($m) { Write-Host $m -ForegroundColor Green }
function Write-Warn ($m) { Write-Host $m -ForegroundColor Yellow }
function Write-Err  ($m) { Write-Host $m -ForegroundColor Red }

function Stop-With ($m) {
    Write-Err $m
    Write-Host ''
    Read-Host 'Press Enter to close'
    exit 1
}

# install.bat runs this through powershell.exe — Windows PowerShell 5.1 — where
# `Set-Content -Encoding UTF8` prepends a byte-order mark. A BOM makes Java read
# the first key of a .properties file as "﻿base.url", so the server setting
# goes missing while the file visibly contains it, and Qt reads a BOM'd
# "[General]" as an ordinary line, which loses every setting in Prism's
# instance.cfg. Nothing here may be written with one.
$Utf8NoBom = New-Object Text.UTF8Encoding($false)

function Write-Utf8 ($path, $lines) {
    [IO.File]::WriteAllText($path, ((@($lines) -join "`r`n") + "`r`n"), $Utf8NoBom)
}

# Windows has no chmod, so the token is restricted by taking the file out of
# inheritance and granting only the current user.
#
# Warns rather than throws. $ErrorActionPreference is Stop, and an identity that
# will not resolve — a Microsoft account, a renamed profile, a domain machine —
# would otherwise abort the whole setup after the files are already written,
# leaving a half-configured instance over a hardening step.
# Runs the CLI and echoes its output, indented.
#
# Two Windows PowerShell hazards, both hit during the first real install:
#
#  * Arguments are passed as an array. Written inline, `-Djava.awt.headless=true`
#    reached java split in two and it tried to run ".awt.headless=true" as the
#    main class. An array leaves nothing for the parser to re-split.
#
#  * Native stderr is not redirected into the pipeline. With `2>&1` those lines
#    become ErrorRecords, and $ErrorActionPreference is Stop, so anything the CLI
#    writes to stderr ends the installer — which is how a failed connection test
#    took down the whole run after the work was already done.
function Invoke-Cli ($cliArgs, $jvmArgs = @()) {
    $all = @($jvmArgs) + @('-jar', (Join-Path $installDir 'modupdater-cli.jar')) + @($cliArgs)
    try {
        & java.exe @all | ForEach-Object { Write-Host "    $_" }
    } catch {
        Write-Warn "    could not run the updater: $($_.Exception.Message)"
    }
}

# Puts the install directory on the user's PATH, so "modupdater" works from any
# prompt. Only the user-scoped variable is touched - the machine one needs
# administrator rights, and rewriting it is how a PATH gets destroyed.
#
# Read back the User value alone rather than $env:Path: that is the User and
# Machine values already joined together, and writing it back would copy every
# machine entry into the user's own PATH.
function Add-ToUserPath ($dir) {
    $current = [Environment]::GetEnvironmentVariable('Path', 'User')
    $parts = @()
    if ($current) { $parts = @($current -split ';' | Where-Object { $_ -ne '' }) }

    if ($parts -contains $dir) { return $false }

    [Environment]::SetEnvironmentVariable('Path', (($parts + $dir) -join ';'), 'User')
    # So the rest of this session, and anything it starts, can already see it.
    $env:Path = "$env:Path;$dir"
    return $true
}

function Protect-TokenFile ($path) {
    try {
        $acl = Get-Acl -LiteralPath $path
        $acl.SetAccessRuleProtection($true, $false)
        $acl.SetAccessRule((New-Object Security.AccessControl.FileSystemAccessRule(
            [Security.Principal.WindowsIdentity]::GetCurrent().Name, 'FullControl', 'Allow')))
        Set-Acl -LiteralPath $path -AclObject $acl
    } catch {
        Write-Warn "  Could not restrict permissions on $path - it is readable by other accounts on this PC."
    }
}

$here = Split-Path -Parent $MyInvocation.MyCommand.Path
$jar = if ($env:MODUPDATER_JAR) { $env:MODUPDATER_JAR } else { Join-Path $here 'modupdater-cli.jar' }
$installDir = if ($env:MODUPDATER_HOME) { $env:MODUPDATER_HOME } else { Join-Path $env:LOCALAPPDATA 'modupdater' }

Write-Host ''
Write-Host 'ModUpdater installer' -ForegroundColor White
Write-Host ''

# ── Prerequisites ───────────────────────────────────────────────────────────

$java = (Get-Command java -ErrorAction SilentlyContinue)
if (-not $java) {
    Stop-With 'Java is not installed. Install Java 21 or newer, then run this again.'
}
if (-not (Test-Path $jar)) {
    Stop-With "Could not find modupdater-cli.jar next to this script (looked in $here)."
}

# ── Find instances ──────────────────────────────────────────────────────────

$tsv = & java.exe -jar $jar list-instances
if (-not $tsv) {
    Write-Err 'No Minecraft instances found.'
    Write-Host ''
    Write-Host 'This looks for Prism Launcher, MultiMC and Modrinth App in their usual'
    Write-Host 'locations. If you keep instances somewhere else, you can still set it up'
    Write-Host 'by hand - see the README.'
    Read-Host 'Press Enter to close'
    exit 1
}

$instances = @()
foreach ($line in @($tsv)) {
    if (-not $line) { continue }
    $f = $line -split "`t"
    if ($f.Count -lt 4) { continue }
    $instances += [pscustomobject]@{
        Launcher = $f[0]
        Name     = $f[1]
        Version  = $f[2]
        GameDir  = $f[3]
        Cfg      = if ($f.Count -ge 5) { $f[4] } else { '' }
    }
}

Write-Host ("Found {0} instance(s):" -f $instances.Count)
Write-Host ''

# Only worth showing a path when the name alone is ambiguous. Deriving a "folder"
# from the game directory printed "profiles" against every Modrinth instance,
# which distinguishes nothing and reads like part of the name.
$duplicateNames = $instances | Group-Object Name | Where-Object { $_.Count -gt 1 } | ForEach-Object { $_.Name }

for ($i = 0; $i -lt $instances.Count; $i++) {
    $inst = $instances[$i]
    Write-Host ("  {0,2}) [{1}] {2}  (MC {3})" -f ($i + 1), $inst.Launcher, $inst.Name, $inst.Version)
    if ($duplicateNames -contains $inst.Name) {
        Write-Host ("      {0}" -f $inst.GameDir) -ForegroundColor DarkGray
    }
}
Write-Host ''

$selection = Read-Host 'Which one? (number, or several like 1,3, or "all")'
if (-not $selection) { Stop-With 'Nothing selected.' }

$chosen = @()
if ($selection -eq 'all') {
    $chosen = 0..($instances.Count - 1)
} else {
    foreach ($part in ($selection -split ',')) {
        $trimmed = $part.Trim()
        $number = 0
        if (-not [int]::TryParse($trimmed, [ref]$number)) { Stop-With "'$trimmed' is not a number." }
        if ($number -lt 1 -or $number -gt $instances.Count) { Stop-With "$number is not in the list." }
        $chosen += ($number - 1)
    }
}

# ── Server details ──────────────────────────────────────────────────────────

$settingsFile = Join-Path $installDir 'settings.properties'
$savedTokenFile = Join-Path $installDir 'token'

function Read-Property ($file, $key) {
    if (-not (Test-Path -LiteralPath $file)) { return $null }
    foreach ($line in Get-Content -LiteralPath $file) {
        if ($line -match "^$key=(.*)$") { return $Matches[1] }
    }
    return $null
}

$savedUrl = Read-Property $settingsFile 'base\.url'
$savedToken = if (Test-Path -LiteralPath $savedTokenFile) {
    (Get-Content -LiteralPath $savedTokenFile -Raw).Trim()
} else { $null }

# Fall back to an instance configured earlier - by a previous version of this
# installer, or by hand - so nobody is asked to dig out their token twice.
foreach ($idx in $chosen) {
    $gd = $instances[$idx].GameDir
    if (-not $savedUrl) { $savedUrl = Read-Property (Join-Path $gd 'modupdater.properties') 'base\.url' }
    if (-not $savedToken) {
        $t = Join-Path $gd 'mods\.modupdater\token'
        if (Test-Path -LiteralPath $t) { $savedToken = (Get-Content -LiteralPath $t -Raw).Trim() }
    }
}

$baseUrl = $null
$token = $null

if ($savedUrl -and $savedToken) {
    Write-Host ''
    Write-Host 'Found existing settings:'
    Write-Host "  Server: $savedUrl"
    Write-Host '  Token:  saved'
    $reply = Read-Host 'Use these? [Y/n]'
    if ($reply -notmatch '^[Nn]') {
        $baseUrl = $savedUrl
        $token = $savedToken
    }
}

if (-not $baseUrl) {
    Write-Host ''
    $baseUrl = Read-Host 'Server address (e.g. https://mods.example.com)'
    if (-not $baseUrl) { Stop-With 'A server address is required.' }
    $baseUrl = $baseUrl.TrimEnd('/')

    $secure = Read-Host 'Access token (paste it - it will not be shown)' -AsSecureString
    $token = [Runtime.InteropServices.Marshal]::PtrToStringAuto(
        [Runtime.InteropServices.Marshal]::SecureStringToBSTR($secure))
    if (-not $token) { Stop-With 'A token is required.' }
}

# ── Install the shared files ────────────────────────────────────────────────

New-Item -ItemType Directory -Force -Path $installDir | Out-Null
Copy-Item $jar (Join-Path $installDir 'modupdater-cli.jar') -Force
Copy-Item (Join-Path $here 'modupdater.bat') (Join-Path $installDir 'modupdater.bat') -Force

# Prism stores instance.cfg through Qt's INI handling, which strips quote
# characters and drops unquoted whitespace outside them - so a value like
#     "C:\path\modupdater.bat" check
# is silently rewritten to ...modupdater.batcheck the next time Prism saves and
# the launch then fails with "process failed to start".
#
# These take no arguments, so the hook value has nothing in it to mangle.
foreach ($mode in @('check', 'apply')) {
    $wrapper = Join-Path $installDir "$mode.bat"
    # ANSI rather than ASCII: cmd.exe reads .bat in the system codepage, and a
    # user folder like C:\Users\José turns into an unusable path under ASCII.
    Set-Content -LiteralPath $wrapper -Encoding Default -Value @(
        '@echo off',
        ('call "' + (Join-Path $installDir 'modupdater.bat') + '" ' + $mode),
        'exit /b %errorlevel%'
    )
}

# Remembered centrally so setting up another instance later asks nothing.
Write-Utf8 $settingsFile "base.url=$baseUrl"
[IO.File]::WriteAllText($savedTokenFile, $token)
Protect-TokenFile $savedTokenFile

Write-Ok "Installed the updater into $installDir"

# ── Put "modupdater" on PATH ────────────────────────────────────────────────

# modupdater.bat is already in $installDir, and .BAT is in PATHEXT, so putting
# the folder on PATH is all "modupdater" needs to work as a command.
try {
    if (Add-ToUserPath $installDir) {
        Write-Ok "Added $installDir to your PATH"
        Write-Warn '  Open a new terminal before running "modupdater" - this one predates the change.'
    } else {
        Write-Ok 'You can now run: modupdater profile enable'
    }
} catch {
    Write-Warn "  Could not add $installDir to your PATH: $($_.Exception.Message)"
    Write-Host  "  Run it by full path instead: $(Join-Path $installDir 'modupdater.bat') profile enable"
}

$preHook = Join-Path $installDir 'check.bat'
$postHook = Join-Path $installDir 'apply.bat'

# ── Per-instance setup ──────────────────────────────────────────────────────

# Qt keeps an unquoted value verbatim but eats whitespace outside quotes, so a
# path with spaces must be quoted as a whole, never partially.
function Quote-ForCfg ($path) {
    if ($path -match '\s') { return '"' + $path + '"' }
    return $path
}

function Set-CfgKey ($file, $key, $value) {
    $lines = Get-Content -LiteralPath $file
    $replaced = $false
    $out = foreach ($line in $lines) {
        if ($line -match "^$key=") { "$key=$value"; $replaced = $true } else { $line }
    }
    if (-not $replaced) {
        # Insert into [General]; appending would land in a later section.
        $inserted = $false
        $out = foreach ($line in $out) {
            $line
            if (-not $inserted -and $line -eq '[General]') { "$key=$value"; $inserted = $true }
        }
    }
    Write-Utf8 $file $out
}

$manualNeeded = $false

foreach ($idx in $chosen) {
    $inst = $instances[$idx]
    Write-Host ''
    Write-Host ("Setting up: {0}" -f $inst.Name) -ForegroundColor White

    $version = $inst.Version
    if ($version -eq 'unknown') {
        # Modrinth App keeps the game version in a database we don't read. If
        # this instance was set up before, reuse that answer rather than asking.
        $version = Read-Property (Join-Path $inst.GameDir 'modupdater.properties') 'mc\.version'
        if ($version) {
            Write-Host "  Using the Minecraft version from last time: $version"
        } else {
            $version = Read-Host '  Which Minecraft version is this instance? (e.g. 1.21.4)'
            if (-not $version) { Write-Warn '  Skipped - no version given.'; continue }
        }
    }

    $modsDir = Join-Path $inst.GameDir 'mods'
    $stateDir = Join-Path $modsDir '.modupdater'
    New-Item -ItemType Directory -Force -Path $stateDir | Out-Null

    Write-Utf8 (Join-Path $inst.GameDir 'modupdater.properties') @("base.url=$baseUrl", "mc.version=$version")

    $tokenFile = Join-Path $stateDir 'token'
    [IO.File]::WriteAllText($tokenFile, $token)
    Protect-TokenFile $tokenFile

    Write-Ok '  Wrote settings and token'

    if ($inst.Cfg -and (Test-Path $inst.Cfg)) {
        $reply = Read-Host '  Set up the launcher hooks automatically? [Y/n]'
        if ($reply -match '^[Nn]') {
            $manualNeeded = $true
            Write-Host '  Skipped. Add these yourself under Settings > Custom commands:'
            Write-Host "    Pre-launch: $preHook"
            Write-Host "    Post-exit:  $postHook"
        } else {
            Copy-Item $inst.Cfg "$($inst.Cfg).modupdater-backup" -Force
            Set-CfgKey $inst.Cfg 'OverrideCommands' 'true'
            Set-CfgKey $inst.Cfg 'PreLaunchCommand' (Quote-ForCfg $preHook)
            Set-CfgKey $inst.Cfg 'PostExitCommand' (Quote-ForCfg $postHook)
            Write-Ok '  Launcher hooks configured (backup alongside instance.cfg)'
            Write-Warn '  Close and reopen Prism so it picks up the change.'
        }
    } else {
        # Modrinth App's own Hooks screen creates the record and stores nulls, so
        # the fields cannot be filled in by hand. Written straight into its
        # database instead, which is why this needs the app closed.
        Write-Host '  Modrinth App stores its hooks in a database, and its settings'
        Write-Host '  screen does not save them. This can write them directly.'
        Write-Warn '  Close Modrinth App completely first - it rewrites this file when it exits.'
        $reply = Read-Host '  Write the hooks now? [Y/n]'

        if ($reply -match '^[Nn]') {
            $manualNeeded = $true
            Write-Host '  Skipped. Options > Hooks, if it will take them:'
            Write-Host "    Pre-launch: $preHook"
            Write-Host "    Post-exit:  $postHook"
        } else {
            Invoke-Cli @('configure-modrinth', '--mods-dir', $modsDir, '--pre', $preHook, '--post', $postHook)
            Write-Warn '  Start Modrinth App again and check Options > Hooks shows them.'
        }
    }

    $wantMod = Read-Host '  Also install the in-game notifier, so updates show up while you play? [Y/n]'
    if ($wantMod -match '^[Nn]') {
        Write-Host '  Skipped. Updates will still be offered before each launch.'
    } else {
        # Pulled from the server like any other mod, so it updates itself
        # afterwards rather than needing a manual download every release.
        Invoke-Cli @('install-mod', '--mods-dir', $modsDir)
    }

    Write-Host '  Checking the connection...'
    # Headless so a manifest with updates in it cannot pop the update dialog
    # in the middle of setup.
    Invoke-Cli @('check', '--mods-dir', $modsDir) @('-Djava.awt.headless=true')
}

Write-Host ''
Write-Host 'Done.' -ForegroundColor White
if ($manualNeeded) {
    Write-Host 'Some instances still need the hooks pasted in by hand - see above.'
}
Write-Host "Next time you launch, you'll be asked about any available updates."
Write-Host 'To sort your mods into profiles:  modupdater profile enable'
Write-Host ''
Read-Host 'Press Enter to close'
