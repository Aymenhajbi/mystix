# Mystix - lance le backend en local contre le PostgreSQL de docker-compose.dev.yml.
# Charge .env dans le processus courant (valeurs jamais affichées), profil dev, port SERVER_PORT (8080 par défaut).
# Usage : scripts\run-backend.ps1
$ErrorActionPreference = 'Stop'
$root = Split-Path $PSScriptRoot -Parent
$envFile = Join-Path $root '.env'

if (-not (Test-Path $envFile)) {
    Write-Error '.env absent. Faire : Copy-Item .env.example .env, puis remplir les mots de passe locaux.'
}

foreach ($line in Get-Content $envFile) {
    if ($line -match '^\s*([A-Za-z_][A-Za-z0-9_]*)=(.*)$') {
        [Environment]::SetEnvironmentVariable($Matches[1], $Matches[2], 'Process')
    }
}

if (-not $env:JAVA_HOME) {
    $env:JAVA_HOME = [Environment]::GetEnvironmentVariable('JAVA_HOME', 'Machine')
}

# Windows: NIO pipes use AF_UNIX sockets in %TEMP%; keep them on a short path without spaces.
$udsDir = Join-Path $root 'backend\target'
New-Item -ItemType Directory -Force $udsDir | Out-Null
$env:JDK_JAVA_OPTIONS = "-Djdk.net.unixdomain.tmpdir=$udsDir -Duser.timezone=Africa/Casablanca"

Set-Location (Join-Path $root 'backend')
& .\mvnw.cmd -B -ntp spring-boot:run
