# Mystix - arrête les services de développement.
# Usage : scripts\dev-down.ps1          -> arrête, conserve les données
#         scripts\dev-down.ps1 -Purge   -> arrête ET supprime les volumes (base locale perdue)
param([switch]$Purge)
$ErrorActionPreference = 'Stop'
Set-Location (Split-Path $PSScriptRoot -Parent)

$composeArgs = @('compose', '-f', 'docker-compose.dev.yml', '--env-file', '.env', '--profile', 'storage', 'down')
if ($Purge) {
    $answer = Read-Host 'Supprimer les volumes locaux (base PostgreSQL, MinIO) ? Taper OUI'
    if ($answer -ne 'OUI') { Write-Host 'Annulé.'; exit 0 }
    $composeArgs += '-v'
}
& docker @composeArgs
