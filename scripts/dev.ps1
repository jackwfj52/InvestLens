param(
    [ValidateSet('init', 'up', 'down', 'status', 'test', 'run')]
    [string]$Action = 'status'
)

$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent $PSScriptRoot
$envFile = Join-Path $projectRoot '.env'

function New-LocalPassword {
    $bytes = New-Object byte[] 32
    $rng = [System.Security.Cryptography.RandomNumberGenerator]::Create()
    try { $rng.GetBytes($bytes) } finally { $rng.Dispose() }
    return [BitConverter]::ToString($bytes).Replace('-', '').ToLowerInvariant()
}

function Invoke-Checked([scriptblock]$Command) {
    & $Command
    if ($LASTEXITCODE -ne 0) { throw "Command failed (exit $LASTEXITCODE)." }
}

Push-Location $projectRoot
try {
    if ($Action -eq 'init') {
        if (Test-Path -LiteralPath $envFile) {
            throw '.env already exists. Edit it explicitly; init never overwrites credentials.'
        }
        $content = [IO.File]::ReadAllText((Join-Path $projectRoot '.env.example'))
        foreach ($key in @('MYSQL_PASSWORD', 'MYSQL_ROOT_PASSWORD', 'REDIS_PASSWORD')) {
            $content = [regex]::Replace($content, "(?m)^$key=.*$", "$key=$(New-LocalPassword)")
        }
        # CreateNew also protects against a concurrent init; no credentials go to stdout.
        $stream = [IO.File]::Open($envFile, [IO.FileMode]::CreateNew, [IO.FileAccess]::Write)
        $writer = New-Object IO.StreamWriter($stream, (New-Object Text.UTF8Encoding($false)))
        try { $writer.Write($content) } finally { $writer.Dispose() }
        Write-Host 'Created ignored .env with independent random passwords.'
        return
    }

    if (-not (Test-Path -LiteralPath $envFile)) { throw 'Run ./scripts/dev.ps1 init first.' }
    switch ($Action) {
        'up' {
            Invoke-Checked { docker compose --env-file .env -f compose.dev.yaml config --quiet }
            Invoke-Checked { docker compose --env-file .env -f compose.dev.yaml up -d --wait --wait-timeout 180 }
        }
        'down' { Invoke-Checked { docker compose --env-file .env -f compose.dev.yaml down } }
        'status' { Invoke-Checked { docker compose --env-file .env -f compose.dev.yaml ps } }
        'test' {
            Set-Location (Join-Path $projectRoot 'backend')
            Invoke-Checked { & .\mvnw.cmd -B -ntp test }
        }
        'run' {
            Set-Location (Join-Path $projectRoot 'backend')
            Invoke-Checked { & .\mvnw.cmd -B -ntp '-Dspring-boot.run.profiles=dev' spring-boot:run }
        }
    }
} finally { Pop-Location }
