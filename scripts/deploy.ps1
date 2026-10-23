param(
    [ValidateSet('config', 'build', 'up', 'down', 'status', 'logs')]
    [string]$Action = 'status'
)

$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent $PSScriptRoot
Push-Location $projectRoot
try {
    if (-not (Test-Path -LiteralPath '.env')) { throw 'Run ./scripts/dev.ps1 init first, then configure your local .env.' }
    & docker compose --env-file .env -f compose.yaml config --quiet
    if ($LASTEXITCODE -ne 0) { throw 'Invalid deployment configuration.' }
    switch ($Action) {
        'config' { Write-Host 'Configuration valid. No secret values printed.' }
        'build' { & docker compose --env-file .env -f compose.yaml build }
        'up' { & docker compose --env-file .env -f compose.yaml up -d --build --wait --wait-timeout 240 }
        'down' { & docker compose --env-file .env -f compose.yaml down }
        'status' { & docker compose --env-file .env -f compose.yaml ps }
        'logs' { & docker compose --env-file .env -f compose.yaml logs --tail 100 frontend backend }
    }
    if ($LASTEXITCODE -ne 0) { throw "Deployment command failed (exit $LASTEXITCODE)." }
} finally { Pop-Location }
