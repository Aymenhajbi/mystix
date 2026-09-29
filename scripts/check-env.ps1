# Mystix - vérifie le poste de développement Windows (lecture seule, ne modifie rien).
# Usage : powershell -ExecutionPolicy Bypass -File scripts\check-env.ps1
$ErrorActionPreference = 'Continue'
$ok = $true

function Test-Tool($name, $cmd, $pattern, $hint) {
    $out = try { & cmd /c "$cmd 2>&1" } catch { $null }
    $line = ($out | Select-Object -First 1)
    if ($out -and ($out -join ' ') -match $pattern) {
        Write-Host ("[OK]   {0,-10} {1}" -f $name, $line) -ForegroundColor Green
    } else {
        Write-Host ("[KO]   {0,-10} {1}" -f $name, $hint) -ForegroundColor Red
        $script:ok = $false
    }
}

Test-Tool 'JDK 21'  'java -version'           'version "21'  'Absent ou mauvaise version : winget install EclipseAdoptium.Temurin.21.JDK puis rouvrir le terminal'
Test-Tool 'Git'     'git --version'           'git version'  'winget install Git.Git'
Test-Tool 'Docker'  'docker version --format "{{.Server.Version}}"' '^\d'  'Docker Desktop installé mais arrêté ? Le démarrer.'
Test-Tool 'Compose' 'docker compose version'  'v2|version'   'Docker Compose v2 requis'
Test-Tool 'Node'    'node --version'          '^v2[0-9]'     'Node.js >= 20 requis'
Test-Tool 'npm'     'npm --version'           '^\d'          'npm absent'
Test-Tool 'OpenSSL' 'openssl version'         'OpenSSL'      'Optionnel (tests AS2) : fourni avec Git Bash'

if (-not $env:JAVA_HOME) {
    Write-Host '[WARN] JAVA_HOME non défini (le wrapper Maven/Gradle peut en avoir besoin).' -ForegroundColor Yellow
}
if (-not (Test-Path '.env')) {
    Write-Host '[WARN] .env absent : Copy-Item .env.example .env puis remplir.' -ForegroundColor Yellow
}
$autocrlf = (git config --get core.autocrlf)
if ($autocrlf -eq 'true') {
    Write-Host '[WARN] core.autocrlf=true : .gitattributes protège les fixtures, mais préférer core.autocrlf=input.' -ForegroundColor Yellow
}

if ($ok) { Write-Host "`nPoste prêt." -ForegroundColor Green; exit 0 }
else     { Write-Host "`nCorriger les points KO ci-dessus." -ForegroundColor Red; exit 1 }
