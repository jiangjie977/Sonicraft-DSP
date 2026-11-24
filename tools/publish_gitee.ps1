<#
.SYNOPSIS
  Publish README + LICENSE only to the Gitee mirror (D:\OPEN\DSP-GITEE).

.DESCRIPTION
  Gitee is used as a SHOWCASE page, not a code mirror: only README.md and LICENSE
  are published there. Full source lives on GitHub.

  Why such a narrow mirror:
  - Gitee review is slow for system-level audio code, and the repo is not meant
    to be a second source of truth;
  - GPL-3.0 only requires that recipients can obtain the source - one canonical
    repository (GitHub) is enough, as long as the README points to it.

  Transformations applied to the published README:
  - relative links (RELEASE_NOTES.md / PRIVACY_POLICY.md / THIRD-PARTY-NOTICES.md)
    are rewritten to absolute GitHub URLs, because those files do NOT exist on Gitee;
  - a one-line banner is inserted under the title telling readers this is a
    showcase page and where the real source is.

.PARAMETER Version
  Optional label appended to the sync commit message (e.g. v0.4.2).

.PARAMETER DryRun
  Show what would be written without touching the Gitee mirror.

.PARAMETER NoPush
  Commit to the Gitee mirror but skip git push.

.EXAMPLE
  .\tools\publish_gitee.ps1 -Version v0.4.2
#>
param(
    [string]$Version = "",
    [switch]$DryRun,
    [switch]$NoPush
)

$ErrorActionPreference = "Stop"

$src = "D:\OPEN\DSP"
$dst = "D:\OPEN\DSP-GITEE"
$gh = "https://github.com/jiangjie977/Sonicraft-DSP"
$utf8NoBom = New-Object System.Text.UTF8Encoding($false)

if (-not (Test-Path $dst)) {
    if ($DryRun) { Write-Host "Dry run: would create $dst" } else { New-Item -ItemType Directory -Path $dst -Force | Out-Null }
}

# --- build README for Gitee ---------------------------------------------
$readme = [IO.File]::ReadAllText((Join-Path $src "README.md"), [Text.Encoding]::UTF8)

# 1) relative links -> absolute GitHub URLs (those files are not published on Gitee)
$readme = $readme -replace '\]\(RELEASE_NOTES\.md\)', "]($gh/blob/main/RELEASE_NOTES.md)"
$readme = $readme -replace '\]\(PRIVACY_POLICY\.md\)', "]($gh/blob/main/PRIVACY_POLICY.md)"
$readme = $readme -replace '\]\(THIRD-PARTY-NOTICES\.md\)', "]($gh/blob/main/THIRD-PARTY-NOTICES.md)"

# 2) banner under the first heading (explains why this repo only has 2 files)
$banner = "> 本仓库为 **Gitee 展示页**，仅包含 README 与 LICENSE；完整源码、发行说明与问题跟踪见 " +
    "[GitHub 仓库]($gh)。`r`n`r`n"
$readme = [regex]::Replace($readme, '^(#\s+Sonicraft\s*\r?\n)', "`$1`r`n$banner", 1)

if ($DryRun) {
    Write-Host "Dry run finished. README would be written to $dst with:"
    Write-Host "  - 3 relative links rewritten to $gh"
    Write-Host "  - 1 banner inserted under the title"
    exit 0
}

[IO.File]::WriteAllText((Join-Path $dst "README.md"), $readme, $utf8NoBom)
Copy-Item (Join-Path $src "LICENSE") (Join-Path $dst "LICENSE") -Force
Write-Host "Wrote README.md + LICENSE to $dst"

# --- commit + push -------------------------------------------------------
Push-Location $dst
if (-not (Test-Path (Join-Path $dst ".git"))) {
    git init -q
    git branch -M main
    Write-Host "Initialized empty Gitee mirror at $dst (branch: main)"
}

git add -A
$staged = git status --porcelain
if (-not $staged) {
    Write-Host "Nothing to publish - Gitee mirror already up to date."
    Pop-Location
    exit 0
}

$srcCommit = (git -C $src log -1 --format="%h")
$label = if ($Version) { " ($Version)" } else { "" }
git commit -m "docs: sync README/LICENSE from GitHub$label [src $srcCommit]"
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
$code = $LASTEXITCODE
if ($code -ne 0) {
    Write-Error "git push failed with exit code $code (use -NoPush if the Gitee remote is not configured yet)"
}
Pop-Location
exit $code
