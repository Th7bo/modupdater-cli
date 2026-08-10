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
for ($i = 0; $i -lt $instances.Count; $i++) {
    $inst = $instances[$i]
    Write-Host ("  {0,2}) [{1}] {2}  (MC {3})" -f ($i + 1), $inst.Launcher, $inst.Name, $inst.Version)
    # Two instances can share a display name; the folder is what tells them apart.
    $folder = Split-Path -Leaf (Split-Path -Parent $inst.GameDir)
    if ($folder -ne $inst.Name) {
        Write-Host ("      folder: {0}" -f $folder)
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

Write-Host ''
$baseUrl = Read-Host 'Server address (e.g. https://mods.example.com)'
if (-not $baseUrl) { Stop-With 'A server address is required.' }
$baseUrl = $baseUrl.TrimEnd('/')

$secure = Read-Host 'Access token (paste it - it will not be shown)' -AsSecureString
$token = [Runtime.InteropServices.Marshal]::PtrToStringAuto(
    [Runtime.InteropServices.Marshal]::SecureStringToBSTR($secure))
if (-not $token) { Stop-With 'A token is required.' }

# ── Install the shared files ────────────────────────────────────────────────

New-Item -ItemType Directory -Force -Path $installDir | Out-Null
Copy-Item $jar (Join-Path $installDir 'modupdater-cli.jar') -Force
Copy-Item (Join-Path $here 'modupdater.bat') (Join-Path $installDir 'modupdater.bat') -Force
Write-Ok "Installed the updater into $installDir"

$hook = Join-Path $installDir 'modupdater.bat'

# ── Per-instance setup ──────────────────────────────────────────────────────

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
    Set-Content -LiteralPath $file -Value $out -Encoding UTF8
}

$manualNeeded = $false

foreach ($idx in $chosen) {
    $inst = $instances[$idx]
    Write-Host ''
    Write-Host ("Setting up: {0}" -f $inst.Name) -ForegroundColor White

    $version = $inst.Version
    if ($version -eq 'unknown') {
        # Modrinth App keeps the game version in a database we don't read.
        $version = Read-Host '  Which Minecraft version is this instance? (e.g. 1.21.4)'
        if (-not $version) { Write-Warn '  Skipped - no version given.'; continue }
    }

    $modsDir = Join-Path $inst.GameDir 'mods'
    $stateDir = Join-Path $modsDir '.modupdater'
    New-Item -ItemType Directory -Force -Path $stateDir | Out-Null

    Set-Content -LiteralPath (Join-Path $inst.GameDir 'modupdater.properties') `
        -Value @("base.url=$baseUrl", "mc.version=$version") -Encoding UTF8

    $tokenFile = Join-Path $stateDir 'token'
    [IO.File]::WriteAllText($tokenFile, $token)

    # Windows has no chmod; restrict the token to the current user instead.
    $acl = Get-Acl $tokenFile
    $acl.SetAccessRuleProtection($true, $false)
    $acl.SetAccessRule((New-Object Security.AccessControl.FileSystemAccessRule(
        $env:USERNAME, 'FullControl', 'Allow')))
    Set-Acl -Path $tokenFile -AclObject $acl

    Write-Ok '  Wrote settings and token'

    if ($inst.Cfg -and (Test-Path $inst.Cfg)) {
        $reply = Read-Host '  Set up the launcher hooks automatically? [Y/n]'
        if ($reply -match '^[Nn]') {
            $manualNeeded = $true
            Write-Host '  Skipped. Add these yourself under Settings > Custom commands:'
            Write-Host "    Pre-launch: `"$hook`" check"
            Write-Host "    Post-exit:  `"$hook`" apply"
        } else {
            Copy-Item $inst.Cfg "$($inst.Cfg).modupdater-backup" -Force
            Set-CfgKey $inst.Cfg 'OverrideCommands' 'true'
            Set-CfgKey $inst.Cfg 'PreLaunchCommand' "`"$hook`" check"
            Set-CfgKey $inst.Cfg 'PostExitCommand' "`"$hook`" apply"
            Write-Ok '  Launcher hooks configured (backup alongside instance.cfg)'
            Write-Warn '  Close and reopen Prism so it picks up the change.'
        }
    } else {
        $manualNeeded = $true
        Write-Host "  Modrinth App can't be configured automatically."
        Write-Host '  Open the instance''s Options > Hooks and paste:'
        Write-Host "    Pre-launch: `"$hook`" check"
        Write-Host "    Post-exit:  `"$hook`" apply"
    }

    Write-Host '  Checking the connection...'
    & java.exe -Djava.awt.headless=true -jar (Join-Path $installDir 'modupdater-cli.jar') `
        check --mods-dir $modsDir 2>&1 | ForEach-Object { Write-Host "    $_" }
}

Write-Host ''
Write-Host 'Done.' -ForegroundColor White
if ($manualNeeded) {
    Write-Host 'Some instances still need the hooks pasted in by hand - see above.'
}
Write-Host "Next time you launch, you'll be asked about any available updates."
Write-Host ''
Read-Host 'Press Enter to close'
