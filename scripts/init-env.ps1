# Mystix - crée ou complète .env à partir de .env.example (versionné), sans jamais afficher une valeur.
# - .env absent : copie de .env.example, valeurs <generated> générées (mots de passe locaux, secret JWT,
#   dossier des secrets de canaux).
# - .env présent : rien n'est remplacé ; seules les clés manquantes sont ajoutées (nouvelle version de l'exemple).
# Usage : powershell -ExecutionPolicy Bypass -File scripts\init-env.ps1
$ErrorActionPreference = 'Stop'
$root = Split-Path $PSScriptRoot -Parent
$example = Join-Path $root '.env.example'
$envFile = Join-Path $root '.env'
$secrets = Join-Path $root '.local\secrets'

function New-Secret([int]$bytes = 32) {
    $buffer = New-Object byte[] $bytes
    [System.Security.Cryptography.RandomNumberGenerator]::Create().GetBytes($buffer)
    # URL-safe, no character that a .env reader or a JDBC URL would misread.
    return [Convert]::ToBase64String($buffer).TrimEnd('=').Replace('+', '-').Replace('/', '_')
}

New-Item -ItemType Directory -Force $secrets | Out-Null
# UTF-8 without BOM: docker compose and the PowerShell loader read the first line as is.
$utf8 = New-Object System.Text.UTF8Encoding($false)

$existing = @{}
if (Test-Path $envFile) {
    foreach ($line in Get-Content $envFile) {
        if ($line -match '^\s*([A-Za-z_][A-Za-z0-9_]*)=(.*)$') { $existing[$Matches[1]] = $Matches[2] }
    }
}

# Values generated once, shared where two keys must agree.
$postgresPassword = if ($existing.ContainsKey('POSTGRES_PASSWORD')) { $existing['POSTGRES_PASSWORD'] } else { New-Secret 24 }
$generated = @{
    'POSTGRES_PASSWORD'          = $postgresPassword
    'SPRING_DATASOURCE_PASSWORD' = $postgresPassword
    'MYSTIX_JWT_SECRET'          = (New-Secret 48)
    'MINIO_ROOT_PASSWORD'        = (New-Secret 24)
    'MYSTIX_SECRETS_DIRECTORY'   = $secrets
}

$output = New-Object System.Collections.Generic.List[string]
$added = New-Object System.Collections.Generic.List[string]
foreach ($line in Get-Content $example) {
    if ($line -match '^\s*([A-Za-z_][A-Za-z0-9_]*)=(.*)$') {
        $key = $Matches[1]; $value = $Matches[2]
        if ($existing.ContainsKey($key)) { continue }
        if ($value -eq '<generated>') { $value = $generated[$key] }
        $output.Add("$key=$value")
        $added.Add($key)
    } elseif (-not (Test-Path $envFile)) {
        $output.Add($line)
    }
}

if (-not (Test-Path $envFile)) {
    [System.IO.File]::WriteAllLines($envFile, $output, $utf8)
    Write-Host ".env created ($($added.Count) keys). Values are not displayed."
} elseif ($added.Count -gt 0) {
    $output.Insert(0, '# --- added by scripts/init-env.ps1 ---')
    $output.Insert(0, '')
    [System.IO.File]::AppendAllLines($envFile, $output, $utf8)
    Write-Host ".env completed with: $($added -join ', ')"
} else {
    Write-Host '.env already complete: nothing to do.'
}
Write-Host "Channel secrets directory: $secrets (git-ignored)"
