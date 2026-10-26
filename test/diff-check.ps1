# ============================================================================
# diff-check.ps1 - compare this Working tree's src/ java files against the
# Githubrepos mirror after every build; append a "found diffed" note to both
# changelogs (English changes.log + Traditional Chinese changes_zh-HK.log).
# Read-only: nothing is copied or committed. Skips when the mirror is missing.
# ----------------------------------------------------------------------------
# Usage (called from build.xml):
#   powershell -NoProfile -ExecutionPolicy Bypass -Command
#     "& '<jr.root>/test/diff-check.ps1' -WorkingSrc '<jr.root>/src'
#        -MirrorSrc '<jr.git.repo>/src' -Changelog '<jr.changelog>'
#        -ChangelogZh '<jr.changelog.zh>'"
# ============================================================================
param(
    [string]$WorkingSrc,
    [string]$MirrorSrc,
    [string]$Changelog,
    [string]$ChangelogZh
)

$ErrorActionPreference = 'Stop'

if (-not (Test-Path -LiteralPath $WorkingSrc)) {
    Write-Output "diff-check: Working src missing: $WorkingSrc"
    exit 0
}
if (-not (Test-Path -LiteralPath $MirrorSrc)) {
    Write-Output "diff-check: mirror src missing: $MirrorSrc (skipped)"
    exit 0
}

$workingRoot = (Resolve-Path -LiteralPath $WorkingSrc).Path.TrimEnd('\', '/')
$diffed = New-Object System.Collections.Generic.List[string]

Get-ChildItem -LiteralPath $WorkingSrc -Filter *.java -Recurse -File | ForEach-Object {
    $rel = $_.FullName.Substring($workingRoot.Length).TrimStart('\', '/')
    $mirror = Join-Path $MirrorSrc $rel
    if (-not (Test-Path -LiteralPath $mirror)) {
        $diffed.Add($rel)
        return
    }
    $h1 = (Get-FileHash -LiteralPath $_.FullName -Algorithm SHA256).Hash
    $h2 = (Get-FileHash -LiteralPath $mirror -Algorithm SHA256).Hash
    if ($h1 -ne $h2) {
        $diffed.Add($rel)
    }
}

if ($diffed.Count -eq 0) {
    Write-Output "diff-check: 0 java files differ."
    exit 0
}

$ts = Get-Date -Format 'yyyy-MM-dd HH:mm:ss'
$noteEn = "# Human-readable diff note (auto-detected)`n" +
          "# $ts - $($diffed.Count) java file(s) differ from the Githubrepos mirror (not yet synced):`n"
$noteZh = "# 人話差異說明（自動偵測）`n" +
          "# $ts - $($diffed.Count) 個 Java 檔案與 Githubrepos 鏡像不同（未同步）：`n"
foreach ($rel in $diffed) {
    $noteEn += "#   - $rel`n"
    $noteZh += "#   - $rel`n"
}

if (Test-Path -LiteralPath $Changelog) {
    Add-Content -LiteralPath $Changelog -Value $noteEn -Encoding utf8BOM
} else {
    [IO.File]::WriteAllText($Changelog, $noteEn, (New-Object Text.UTF8Encoding $true))
}
if (Test-Path -LiteralPath $ChangelogZh) {
    Add-Content -LiteralPath $ChangelogZh -Value $noteZh -Encoding utf8BOM
} else {
    [IO.File]::WriteAllText($ChangelogZh, $noteZh, (New-Object Text.UTF8Encoding $true))
}

Write-Output "diff-check: found diffed: $($diffed.Count) java file(s) -> changelog note appended."
