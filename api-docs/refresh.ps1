#Requires -Version 7
<#
.SYNOPSIS
  Refresh the local SpaceTraders API doc cache from GitHub and the live server.
.DESCRIPTION
  Shallow-clones SpaceTradersAPI/api-docs and its wiki into a temp folder,
  copies the reference material into this folder, downloads the bundled
  OpenAPI spec the live server publishes, and rewrites the provenance table
  in README.md. Run from anywhere; paths are relative to this script.
#>
$ErrorActionPreference = 'Stop'
$root = $PSScriptRoot
$tmp  = Join-Path ([System.IO.Path]::GetTempPath()) ("st-api-docs-" + [guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory -Path $tmp | Out-Null

try {
    Write-Host "Cloning api-docs..."
    git clone --quiet --depth 1 https://github.com/SpaceTradersAPI/api-docs.git (Join-Path $tmp 'api-docs')
    Write-Host "Cloning wiki..."
    git clone --quiet --depth 1 https://github.com/SpaceTradersAPI/api-docs.wiki.git (Join-Path $tmp 'wiki')

    $specSha  = (git -C (Join-Path $tmp 'api-docs') log -1 --format='%H')
    $specDate = (git -C (Join-Path $tmp 'api-docs') log -1 --format='%cs')
    $specMsg  = (git -C (Join-Path $tmp 'api-docs') log -1 --format='%s')
    $wikiSha  = (git -C (Join-Path $tmp 'wiki') log -1 --format='%H')
    $wikiDate = (git -C (Join-Path $tmp 'wiki') log -1 --format='%cs')

    # Replace spec/ and wiki/ wholesale so removed upstream files disappear too.
    foreach ($d in 'spec', 'wiki', 'live') {
        $p = Join-Path $root $d
        if (Test-Path $p) { Remove-Item -Recurse -Force $p }
        New-Item -ItemType Directory -Path $p | Out-Null
    }
    New-Item -ItemType Directory -Path (Join-Path $root 'spec\docs') | Out-Null
    Copy-Item (Join-Path $tmp 'api-docs\README.md')          (Join-Path $root 'spec\README.md')
    Copy-Item (Join-Path $tmp 'api-docs\docs\overview.md')   (Join-Path $root 'spec\docs\overview.md')
    Copy-Item (Join-Path $tmp 'api-docs\models')             (Join-Path $root 'spec\models') -Recurse
    Copy-Item (Join-Path $tmp 'api-docs\reference')          (Join-Path $root 'spec\reference') -Recurse
    Copy-Item (Join-Path $tmp 'wiki\*.md')                   (Join-Path $root 'wiki')

    Write-Host "Downloading live bundled spec..."
    $liveRaw = Invoke-RestMethod -Uri 'https://api.spacetraders.io/v2/documentation/json' -TimeoutSec 60
    $liveRaw | ConvertTo-Json -Depth 100 | Set-Content -Path (Join-Path $root 'live\openapi-bundled.json') -Encoding utf8

    $status = Invoke-RestMethod -Uri 'https://api.spacetraders.io/v2/' -TimeoutSec 30
    $today  = Get-Date -Format 'yyyy-MM-dd'

    # Rewrite the provenance table rows in README.md.
    $readme = Join-Path $root 'README.md'
    $text = Get-Content $readme -Raw
    $text = $text -replace '(?m)^\| Snapshot taken \|.*$',    "| Snapshot taken | $today |"
    $text = $text -replace '(?m)^\| api-docs commit \|.*$',   "| api-docs commit | ``$specSha`` ($specDate, `"$specMsg`") |"
    $text = $text -replace '(?m)^\| wiki commit \|.*$',       "| wiki commit | ``$wikiSha`` ($wikiDate) |"
    $text = $text -replace '(?m)^\| Live server version \|.*$', "| Live server version | $($status.version) (reset date $($status.resetDate), $($status.serverResets.frequency) resets) |"
    Set-Content -Path $readme -Value $text -Encoding utf8 -NoNewline

    Write-Host "Done. Review 'git status' and commit."
    Write-Host "Note: the 'live-only operations' list in README.md is maintained by hand; re-check it if the spec changed."
}
finally {
    if (Test-Path $tmp) { Remove-Item -Recurse -Force $tmp }
}
