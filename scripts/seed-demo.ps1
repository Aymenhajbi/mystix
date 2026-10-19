# Mystix - reproducible demo data on a running local backend (dev profile), the same on every workstation.
# Synthetic or fictitious data only. Safe to run again: existing items are kept, invoices are replayed, not doubled.
#   1. first operator account, if the database has none (credentials written to .local\dev-credentials.txt);
#   2. two environments: "Fournisseur Synthetique SARL" (Mystix) and "Industrie Bois Demo (fictif) SARL" (Atelys);
#   3. three invoices dated today on the first one; 4. Atelys demo data on the second one.
# Usage: scripts\dev-up.ps1 ; scripts\run-backend.ps1 (other window) ; scripts\seed-demo.ps1
$ErrorActionPreference = 'Stop'
$root = Split-Path $PSScriptRoot -Parent
$envFile = Join-Path $root '.env'
if (-not (Test-Path $envFile)) { Write-Error '.env missing: run scripts\init-env.ps1 first.' }
$port = '8080'
foreach ($line in Get-Content $envFile) { if ($line -match '^SERVER_PORT=(\d+)$') { $port = $Matches[1] } }
$api = "http://localhost:$port"

try { Invoke-RestMethod "$api/actuator/health" | Out-Null } catch { Write-Error "Backend not reachable on ${api}: start scripts\run-backend.ps1 first." }

function Send-Json([string]$method, [string]$path, $body, [string]$company) {
    $headers = @{}
    if ($company) { $headers['X-Mystix-Company-Id'] = $company }
    $json = if ($null -eq $body) { $null } else { [System.Text.Encoding]::UTF8.GetBytes(($body | ConvertTo-Json -Depth 10)) }
    return Invoke-RestMethod -Method $method -Uri "$api$path" -Headers $headers -ContentType 'application/json' -Body $json
}

# 1. First operator account
$setup = Invoke-RestMethod "$api/api/v1/auth/setup"
if ($setup.setupNeeded) {
    $bytes = New-Object byte[] 18
    [System.Security.Cryptography.RandomNumberGenerator]::Create().GetBytes($bytes)
    $password = [Convert]::ToBase64String($bytes).Replace('+', '-').Replace('/', '_') + '!7a'
    Send-Json 'POST' '/api/v1/auth/setup' @{ email = 'admin@mystix.test'; displayName = 'Administrateur test'; password = $password } $null | Out-Null
    $credentials = Join-Path $root '.local\dev-credentials.txt'
    New-Item -ItemType Directory -Force (Split-Path $credentials) | Out-Null
    $content = "# Local dev only, synthetic test account. Created $(Get-Date -Format o)`nemail=admin@mystix.test`npassword=$password`n"
    [System.IO.File]::WriteAllText($credentials, $content, (New-Object System.Text.UTF8Encoding($false)))
    Write-Host "Operator account created: admin@mystix.test (password in $credentials, git-ignored)."
} else {
    Write-Host 'Operator account already exists: kept as is.'
}

# 2. Environments
function Get-Company([string]$ice, [string]$name) {
    $found = (Invoke-RestMethod "$api/api/v1/admin/environments") | Where-Object { $_.ice -eq $ice } | Select-Object -First 1
    if ($found) { return $found.id }
    $created = Send-Json 'POST' '/api/v1/companies' @{ ice = $ice; legalName = $name } $null
    Write-Host "Environment created: $name"
    return $created.id
}
$supplier = Get-Company '000000001000011' 'Fournisseur Synthetique SARL'
$wood = Get-Company '000000088000088' 'Industrie Bois Demo (fictif) SARL'

# 3. Invoices dated today (the seller ICE of the fixture is the supplier's)
$fixture = Get-Content -Raw -Encoding UTF8 (Join-Path $root 'backend\src\test\resources\fixtures\api\invoice-basic.request.json')
$today = (Get-Date).ToString('yyyy-MM-dd')
$due = (Get-Date).AddDays(30).ToString('yyyy-MM-dd')
foreach ($n in 1..3) {
    $number = 'FA-DEMO-{0:D4}' -f $n
    $body = $fixture.Replace('FA-2026-000123', $number) -replace '"issueDate"\s*:\s*"[0-9-]+"', "`"issueDate`": `"$today`"" -replace '"dueDate"\s*:\s*"[0-9-]+"', "`"dueDate`": `"$due`""
    $response = Invoke-WebRequest -UseBasicParsing -Method POST -Uri "$api/api/v1/invoices" -ContentType 'application/json' `
        -Headers @{ 'X-Mystix-Company-Id' = $supplier } -Body ([System.Text.Encoding]::UTF8.GetBytes($body))
    $state = if ($response.StatusCode -eq 201) { 'created' } else { 'already there' }
    Write-Host "Invoice ${number}: $state"
}

# 4. Atelys demo data
try {
    Send-Json 'POST' '/api/v1/atelys/demo' $null $wood | Out-Null
    Write-Host 'Atelys demo data loaded on Industrie Bois Demo (fictif) SARL.'
} catch {
    Write-Host "Atelys demo data not loaded (already there, or demo disabled outside the dev profile): $($_.Exception.Message)"
}
Write-Host "Done. Portal: http://localhost:3000"
