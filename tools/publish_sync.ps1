<#
.SYNOPSIS
  Mirror D:\OPEN\DSP (development) to D:\OPEN\DSP-GITHUB (publish mirror).

.DESCRIPTION
  HARD RULE: the publish mirror must never contain excluded files or folders.
  robocopy /XD only skips the source side, so this script prunes the destination
  after every sync (excluded dirs, excluded files, empty dirs left behind).

.PARAMETER Version
  Optional version label used in the sync commit message (e.g. v0.4.2).

.PARAMETER DryRun
  List files that would be copied without touching the mirror.

.PARAMETER NoPush
  Commit to the mirror but skip git push (used when the remote is not configured yet).

.EXAMPLE
  .\tools\publish_sync.ps1 -Version v0.4.2
#>
param(
    [string]$Version = "",
    [switch]$DryRun,
    [switch]$NoPush
)

$ErrorActionPreference = "Stop"

$src = "D:\OPEN\DSP"
$dst = "D:\OPEN\DSP-GITHUB"

if (-not (Test-Path $dst)) {
    Write-Error "Publish mirror not found: $dst"
    exit 1
}

# --- guard: dev tree must be clean so nothing half-finished leaks out ---
Push-Location $src
$dirty = git status --porcelain
Pop-Location
if ($dirty -and -not $DryRun) {
    Write-Warning "Dev tree has uncommitted changes:"
    $dirty | ForEach-Object { Write-Warning "  $_" }
    $answer = Read-Host "Continue anyway? [y/N]"
    if ($answer -notmatch '^[yY]') { exit 1 }
}

# --- exclude lists -------------------------------------------------------
# Source-side skips (may include .git: we do not copy dev history into the mirror).
# docs/: development notes (design/plan/roadmap) stay local only.
# CHANGELOG.md: internal change log, not published.
$excludeDirs = @("build", ".gradle", ".kotlin", ".codebuddy", ".git", ".cxx", "node_modules", ".idea", "keystore", "dist", "docs")
$excludeFiles = @("*.apk", "*.jks", "*.keystore", "keystore.properties", "local.properties", "*.log", "AGENTS.md", "README.public.md", "CHANGELOG.md", "EQ_USER_MANUAL.md", "M8_MULTIBAND_USER_GUIDE.md")

# Destination pruning (NEVER prune .git: the mirror owns its own repo).
$pruneDirs = @("build", ".gradle", ".kotlin", ".codebuddy", ".cxx", "node_modules", ".idea", "keystore", "dist", "docs")
$pruneFiles = @("*.apk", "*.jks", "*.keystore", "keystore.properties", "local.properties", "*.log", "AGENTS.md", "README.public.md", "CHANGELOG.md", "EQ_USER_MANUAL.md", "M8_MULTIBAND_USER_GUIDE.md")

# Images are pruned ONLY under tools/ (screenshots/forensics captures).
# Never use a global *.png filter: it would delete app launcher icons.
$toolsImagePatterns = @("*.png", "*.jpg", "*.jpeg", "*.gif", "*.webp", "*.bmp")

# --- ensure publish repo exists (first run creates an independent history) ---
if (-not (Test-Path (Join-Path $dst ".git"))) {
    if ($DryRun) {
        Write-Host "Dry run: publish repo not initialized yet ($dst\.git missing)."
    } else {
        Push-Location $dst
        git init -q
        git branch -M main
        Pop-Location
        Write-Host "Initialized empty publish repo at $dst (branch: main)"
    }
}

# --- mirror --------------------------------------------------------------
$robocopyArgs = @($src, $dst, "/MIR", "/XD") + $excludeDirs + @("/XF") + $excludeFiles + @("/NFL", "/NDL", "/NJH", "/NJS", "/NC", "/NS")
if ($DryRun) { $robocopyArgs += @("/L") }

& robocopy @robocopyArgs | Out-Null
$code = $LASTEXITCODE
if ($code -ge 8) {
    Write-Error "robocopy failed with exit code $code"
    exit 1
}

if ($DryRun) {
    Write-Host "Dry run finished (no files changed)."
    exit 0
}

# --- HARD RULE: prune excluded items that robocopy left on the destination ---
foreach ($d in $pruneDirs) {
    $p = Join-Path $dst $d
    if (Test-Path $p) {
        Remove-Item $p -Recurse -Force -ErrorAction SilentlyContinue
    }
}

foreach ($pat in $pruneFiles) {
    Get-ChildItem -Path $dst -Recurse -File -Filter $pat -Force -ErrorAction SilentlyContinue |
        Where-Object { $_.FullName -notlike "*\.git\*" } |
        ForEach-Object { Remove-Item -LiteralPath $_.FullName -Force -ErrorAction SilentlyContinue }
}

# Prune screenshots/images under tools/ only (keeps app launcher icons intact).
# NOTE 1: -Include with a plain dir path matches nothing; always use '<dir>\*'.
# NOTE 2: piping FileInfo straight into Remove-Item silently no-ops here
#         (its -Path does not bind from FullName in this host) -> use -LiteralPath in ForEach-Object.
$toolsPath = Join-Path $dst "tools"
if (Test-Path $toolsPath) {
    foreach ($pat in $toolsImagePatterns) {
        $hit = Get-ChildItem -Path (Join-Path $toolsPath "*") -Recurse -File -Filter $pat -Force -ErrorAction SilentlyContinue
        foreach ($item in $hit) {
            Remove-Item -LiteralPath $item.FullName -Force -ErrorAction SilentlyContinue
        }
    }
}

# Remove empty directories left behind (deepest first), except .git.
Get-ChildItem -Path $dst -Recurse -Directory -Force -ErrorAction SilentlyContinue |
    Where-Object { $_.FullName -notlike "*\.git*" } |
    Sort-Object { $_.FullName.Length } -Descending |
    ForEach-Object {
        if ((Get-ChildItem $_.FullName -Force -ErrorAction SilentlyContinue | Measure-Object).Count -eq 0) {
            Remove-Item $_.FullName -Recurse -Force -ErrorAction SilentlyContinue
        }
    }

# --- stage + commit + push ----------------------------------------------
Push-Location $dst
git add -A
$staged = git status --porcelain
if (-not $staged) {
    Write-Host "Nothing to publish - mirror already up to date."
    Pop-Location
    exit 0
}

$srcCommit = (git -C $src log -1 --format="%h")
$msg = if ($Version) { "sync: $Version (from DSP $srcCommit)" } else { "sync: from DSP $srcCommit" }
git commit -m $msg
if ($LASTEXITCODE -ne 0) {
    Write-Error "git commit failed"
    Pop-Location
    exit 1
}

if ($NoPush) {
    Write-Host "Committed locally (NoPush). Configure remote then: git push -u origin HEAD"
    Pop-Location
    exit 0
}

git push -u origin HEAD
$pushCode = $LASTEXITCODE
if ($pushCode -ne 0) {
    Write-Error "git push failed with exit code $pushCode (use -NoPush if remote is not configured yet)"
}
Pop-Location
exit $pushCode
