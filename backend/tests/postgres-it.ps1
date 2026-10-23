<#
.SYNOPSIS
  Runs the PostgresIntegration* tests against a throwaway PostGIS container (same image as deploy/docker-compose.yml).
  Each test class creates and drops its own database. The password is random and never printed.
  Run from the repo root on a Docker host (PowerShell 7).
.EXAMPLE
  pwsh backend/tests/postgres-it.ps1
#>
param([int]$Port = 5439, [string]$Image = 'postgis/postgis:17-3.5')
$ErrorActionPreference = 'Stop'
$pw = 'Pg-' + [Convert]::ToHexString([System.Security.Cryptography.RandomNumberGenerator]::GetBytes(12))
$name = 'gimano-pg-it'
docker rm -f $name 2>$null | Out-Null
docker run -d --name $name -p "127.0.0.1:${Port}:5432" -e POSTGRES_USER=gimano -e "POSTGRES_PASSWORD=$pw" -e POSTGRES_DB=postgres $Image | Out-Null
$ok = $false
for ($i = 0; $i -lt 40 -and -not $ok; $i++) {
    docker exec $name pg_isready -U gimano -d postgres 2>$null | Out-Null
    if ($LASTEXITCODE -eq 0) { $ok = $true } else { Start-Sleep 2 }
}
if (-not $ok) { docker logs --tail 30 $name; throw 'PostGIS did not start' }
Start-Sleep 3
docker run --rm --network host -v "${PWD}:/src" -w /src/backend `
  -e "GIMANO_PG_TEST=Host=localhost;Port=$Port;Database=postgres;Username=gimano;Password=$pw" `
  mcr.microsoft.com/dotnet/sdk:10.0 dotnet test tests/Gimano.Tests --filter "FullyQualifiedName~PostgresIntegration" 2>&1 |
  Select-String -Pattern 'passed|failed|skipped|total:|succeeded:|Assert|Expected|Actual|Exception|error' | ForEach-Object { $_.Line }
docker rm -f $name | Out-Null
Write-Host 'done'
