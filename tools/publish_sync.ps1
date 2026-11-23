<#
.SYNOPSIS
  Mirror D:\OPEN\DSP (development) to D:\OPEN\DSP-GITHUB (publish mirror) and push.

.DESCRIPTION
  Rules:
    1. Only the publish mirror talks to GitHub.
    2. Dev artifacts (build/, .gradle/, .cxx/, .codebuddy/, *.apk, *.jks ...) are excluded.
    3. Line endings are normalized by .gitattributes when staging, so CRLF noise is not
       committed as content changes.

.PARAMETER Version
  Optional version label used in the sync commit message (e.g. v0.4.2).

.PARAMETER DryRun
  List files that would be copied without touching the mirror.

.EXAMPLE
  .\tools\publish_sync.ps1 -Version v0.4.2
#>
param(
    [string]$Version = "",
    [switch]$DryRun
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

# --- mirror ---
$excludeDirs = @("build", ".gradle", ".kotlin", ".codebuddy", ".git", ".cxx", "node_modules", ".idea", "keystore", "dist")
$excludeFiles = @("*.apk", "*.jks", "*.keystore", "keystore.properties", "local.properties", "*.log")

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

# --- stage + commit + push ---
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

git push -u origin HEAD
$pushCode = $LASTEXITCODE
if ($pushCode -ne 0) {
    Write-Error "git push failed with exit code $pushCode"
}
Pop-Location
exit $pushCode
