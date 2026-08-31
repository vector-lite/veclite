# Start the veclite Spring Boot service with credentials loaded from .env.
# Usage (from repo root):
#   .\start-local.ps1
#
# On first run: copy `.env.example` to `.env` and fill in the real values.
# This script only reads KEY=VALUE lines; quoted values are stripped of
# surrounding quotes; lines starting with `#` and blank lines are skipped.

$ErrorActionPreference = 'Stop'

$scriptDir = Split-Path -Parent $MyInvocation.MyCommand.Path
$envFile = Join-Path $scriptDir '.env'

if (-not (Test-Path -Path $envFile -PathType Leaf)) {
    Write-Host ".env not found at $envFile" -ForegroundColor Red
    Write-Host "Copy .env.example to .env and fill in your real values:" -ForegroundColor Yellow
    Write-Host "    Copy-Item .env.example .env" -ForegroundColor Yellow
    Write-Host "Then edit .env and re-run this script." -ForegroundColor Yellow
    exit 1
}

Get-Content -LiteralPath $envFile -Encoding UTF8 | ForEach-Object {
    $line = $_.Trim()
    if (-not $line) { return }
    if ($line.StartsWith('#')) { return }

    $idx = $line.IndexOf('=')
    if ($idx -le 0) { return }

    $key = $line.Substring(0, $idx).Trim()
    $val = $line.Substring($idx + 1).Trim()

    # Strip a single matching pair of surrounding quotes
    if ($val.Length -ge 2) {
        $first = $val[0]
        $last = $val[$val.Length - 1]
        if (($first -eq '"' -and $last -eq '"') -or ($first -eq "'" -and $last -eq "'")) {
            $val = $val.Substring(1, $val.Length - 2)
        }
    }

    Set-Item -Path "Env:$key" -Value $val
}

Set-Location $scriptDir
& .\gradlew.bat bootRun @args
