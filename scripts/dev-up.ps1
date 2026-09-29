# Mystix - démarre les services de développement.
# Usage : scripts\dev-up.ps1                 -> PostgreSQL
#         scripts\dev-up.ps1 -Storage        -> + MinIO (Lot 4)
param([switch]$Storage)
$ErrorActionPreference = 'Stop'
Set-Location (Split-Path $PSScriptRoot -Parent)

if (-not (Test-Path '.env')) {
    Write-Error '.env absent. Faire : Copy-Item .env.example .env, puis remplir les mots de passe locaux.'
}

$composeArgs = @('compose', '-f', 'docker-compose.dev.yml', '--env-file', '.env')
if ($Storage) { $composeArgs += @('--profile', 'storage') }
$composeArgs += @('up', '-d', '--wait')

& docker @composeArgs
if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
& docker compose -f docker-compose.dev.yml ps
Write-Host "`nPostgreSQL (base + file de travail, ADR-0003) : localhost:$((Select-String -Path .env -Pattern '^POSTGRES_PORT=(.*)').Matches.Groups[1].Value)"
if ($Storage) { Write-Host 'Console MinIO : http://localhost:9001' }
