<#
.SYNOPSIS
  Rotates the dev stack's Postgres and RabbitMQ passwords (and optionally the JWT key) in place.

.DESCRIPTION
  Run from the repo root on the Docker host. It generates strong random secrets and writes them
  into the git-ignored deploy/.env. It applies them to the running services: ALTER USER for Postgres
  (the data volume keeps the old password otherwise) and rabbitmqctl change_password. Then it
  recreates the stack so api and worker pick up the new environment.
  Secrets are never printed. They are passed to psql/rabbitmqctl on stdin, not on the command line.

.PARAMETER RotateJwt
  Also replace JWT_SIGNING_KEY. Every app has to sign in again (POST /auth/device).
#>
[CmdletBinding()]
param([switch]$RotateJwt)
$ErrorActionPreference = 'Stop'
$compose = @('compose', '-f', 'deploy/docker-compose.yml')
$envFile = 'deploy/.env'
if (-not (Test-Path $envFile)) { throw "$envFile not found (copy deploy/.env.example first)." }

function New-Secret([int]$bytes = 32) {
    $b = [byte[]]::new($bytes)
    [System.Security.Cryptography.RandomNumberGenerator]::Fill($b)
    # URL/connection-string safe: no ';', '=', quotes or spaces.
    ([Convert]::ToBase64String($b)).TrimEnd('=').Replace('+', '-').Replace('/', '_')
}

function Set-EnvValue([string[]]$lines, [string]$key, [string]$value) {
    $found = $false
    $out = foreach ($l in $lines) {
        if ($l -match "^\s*$key=") { $found = $true; "$key=$value" } else { $l }
    }
    if (-not $found) { $out += "$key=$value" }
    , $out
}

$lines = Get-Content $envFile
$user = (($lines | Where-Object { $_ -match '^\s*POSTGRES_USER=' }) -replace '^\s*POSTGRES_USER=', '') ; if (-not $user) { $user = 'gimano' }
$db = (($lines | Where-Object { $_ -match '^\s*POSTGRES_DB=' }) -replace '^\s*POSTGRES_DB=', '') ; if (-not $db) { $db = 'gimano' }
$rmqUser = (($lines | Where-Object { $_ -match '^\s*RABBITMQ_USER=' }) -replace '^\s*RABBITMQ_USER=', '') ; if (-not $rmqUser) { $rmqUser = 'gimano' }

$pg = New-Secret
$rmq = New-Secret

Write-Host "Postgres: ALTER USER $user ..."
"ALTER USER `"$user`" WITH PASSWORD '$pg';" | docker @compose exec -T postgis psql -v ON_ERROR_STOP=1 -q -U $user -d $db | Out-Null
if ($LASTEXITCODE -ne 0) { throw 'Postgres password change failed.' }

Write-Host "RabbitMQ: change_password $rmqUser ..."
$rmq | docker @compose exec -T rabbitmq rabbitmqctl change_password $rmqUser | Out-Null
if ($LASTEXITCODE -ne 0) { throw 'RabbitMQ password change failed.' }

$lines = Set-EnvValue $lines 'POSTGRES_PASSWORD' $pg
$lines = Set-EnvValue $lines 'RABBITMQ_PASSWORD' $rmq
if ($RotateJwt) { $lines = Set-EnvValue $lines 'JWT_SIGNING_KEY' (New-Secret 48); Write-Host 'JWT signing key rotated (apps must sign in again).' }
Set-Content -Path $envFile -Value $lines -Encoding ascii
Remove-Variable pg, rmq

Write-Host 'Recreating containers with the new environment (infra ports now bound to 127.0.0.1)...'
docker @compose up -d 2>&1 | Select-String -Pattern 'Recreated|Started|Running|Error' | ForEach-Object { $_.Line }
Write-Host 'Done. Secrets were written to deploy/.env only.'
