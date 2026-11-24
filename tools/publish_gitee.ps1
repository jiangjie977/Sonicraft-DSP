<#
.SYNOPSIS
  Publish README + LICENSE only to the Gitee mirror (D:\OPEN\DSP-GITEE).

.DESCRIPTION
  Gitee is used as a SHOWCASE page, not a code mirror: only README.md, LICENSE
  and RELEASE_NOTES.md are published there. Full source lives on GitHub.

  Why such a narrow mirror:
  - Gitee review is slow for system-level audio code, and the repo is not meant
    to be a second source of truth;
  - GPL-3.0 only requires that recipients can obtain the source - one canonical
    repository (GitHub) is enough, as long as the README points to it.

  Transformations applied to the published README:
  - relative links whose target is NOT published on Gitee (PRIVACY_POLICY.md /
    THIRD-PARTY-NOTICES.md) are rewritten to absolute GitHub URLs;
    RELEASE_NOTES.md IS published, so its relative link is kept working;
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

# 1) rewrite ONLY the links whose targets are absent on Gitee.
#    RELEASE_NOTES.md is published here, so its relative link is kept.
$readme = $readme -replace '\]\(PRIVACY_POLICY\.md\)', "]($gh/blob/main/PRIVACY_POLICY.md)"
$readme = $readme -replace '\]\(THIRD-PARTY-NOTICES\.md\)', "]($gh/blob/main/THIRD-PARTY-NOTICES.md)"

# 2) banner under the first heading (explains what this repo contains)
$banner = "> 本仓库为 **Gitee 展示页**，仅包含 README、LICENSE 与发行说明；" +
    "完整源码、隐私政策与问题跟踪见 [GitHub 仓库]($gh)。`r`n`r`n"
$readme = [regex]::Replace($readme, '^(#\s+Sonicraft\s*\r?\n)', "`$1`r`n$banner", 1)

if ($DryRun) {
    Write-Host "Dry run finished. Files would be written to $dst :"
    Write-Host "  - README.md (2 links rewritten, 1 banner inserted)"
    Write-Host "  - LICENSE"
    Write-Host "  - RELEASE_NOTES.md"
    exit 0
}

[IO.File]::WriteAllText((Join-Path $dst "README.md"), $readme, $utf8NoBom)
Copy-Item (Join-Path $src "LICENSE") (Join-Path $dst "LICENSE") -Force
Copy-Item (Join-Path $src "RELEASE_NOTES.md") (Join-Path $dst "RELEASE_NOTES.md") -Force
Write-Host "Wrote README.md + LICENSE + RELEASE_NOTES.md to $dst"

# --- commit + push -------------------------------------------------------
Push-Location $dst
if (-not (Test-Path (Join-Path $dst ".git"))) {
    git init -q
    # Gitee 新建仓库的默认分支是 master（不是 main）——用 master 可避免首页空白
    git branch -M master
    Write-Host "Initialized empty Gitee mirror at $dst (branch: master)"
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
