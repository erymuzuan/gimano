<#
.SYNOPSIS
  OpenSearch compatibility check: runs OpenSearchIntegrationTests against a throwaway OpenSearch container
  (default 3.0.0, security on) as a RESTRICTED user whose role only grants "gimano-*" + indices:data/write/bulk,
  i.e. the same permissions as the shared .149 cluster. Optionally also against another URL (e.g. dev 2.19).
  Passwords are random and never printed. Run from the repo root on a Docker host (PowerShell 7).
.EXAMPLE
  pwsh backend/tests/opensearch-it.ps1
  pwsh backend/tests/opensearch-it.ps1 -Version 2.19.1
  pwsh backend/tests/opensearch-it.ps1 -AlsoUrl http://localhost:9210
#>
param([string]$Version = '3.0.0', [int]$Port = 9230, [string]$AlsoUrl)
$ErrorActionPreference = 'Stop'
function New-Pw { 'Gm-' + [Convert]::ToHexString([System.Security.Cryptography.RandomNumberGenerator]::GetBytes(12)) + '-x9!' }
$name = "gimano-os-it-$($Version -replace '\.','')"
$admin = New-Pw; $userPw = New-Pw
docker rm -f $name 2>$null | Out-Null
docker run -d --name $name -p "127.0.0.1:${Port}:9200" -e discovery.type=single-node -e "OPENSEARCH_INITIAL_ADMIN_PASSWORD=$admin" `
  -e "OPENSEARCH_JAVA_OPTS=-Xms512m -Xmx512m" "opensearchproject/opensearch:$Version" | Out-Null
$base = "https://localhost:$Port"
$cred = New-Object PSCredential('admin', (ConvertTo-SecureString $admin -AsPlainText -Force))
$ok = $false
for ($i = 0; $i -lt 60 -and -not $ok; $i++) {
    try { Invoke-RestMethod "$base/_cluster/health" -Credential $cred -Authentication Basic -SkipCertificateCheck | Out-Null; $ok = $true } catch { Start-Sleep 3 }
}
if (-not $ok) { docker logs --tail 30 $name; throw "OpenSearch $Version did not start" }
$v = (Invoke-RestMethod "$base/" -Credential $cred -Authentication Basic -SkipCertificateCheck).version.number
Write-Host "OpenSearch $v up; creating restricted role/user (gimano-* only)…"
$sec = "$base/_plugins/_security/api"
$hdr = @{ 'Content-Type' = 'application/json' }
Invoke-RestMethod "$sec/roles/gimano_it" -Method Put -Credential $cred -Authentication Basic -SkipCertificateCheck -Headers $hdr -Body (@{
    cluster_permissions = @('indices:data/write/bulk')
    index_permissions = @(@{ index_patterns = @('gimano-*'); allowed_actions = @('indices_all') })
} | ConvertTo-Json -Depth 5) | Out-Null
Invoke-RestMethod "$sec/internalusers/gimano_it" -Method Put -Credential $cred -Authentication Basic -SkipCertificateCheck -Headers $hdr -Body (@{ password = $userPw } | ConvertTo-Json) | Out-Null
Invoke-RestMethod "$sec/rolesmapping/gimano_it" -Method Put -Credential $cred -Authentication Basic -SkipCertificateCheck -Headers $hdr -Body (@{ users = @('gimano_it') } | ConvertTo-Json) | Out-Null

function Run-It([string]$url, [string]$user, [string]$pw, [string]$insecure) {
    docker run --rm --network host -v "${PWD}:/src" -w /src/backend `
      -e "GIMANO_OS_URL=$url" -e "GIMANO_OS_USER=$user" -e "GIMANO_OS_PASSWORD=$pw" -e "GIMANO_OS_INSECURE=$insecure" `
      mcr.microsoft.com/dotnet/sdk:10.0 dotnet test tests/Gimano.Tests --filter "FullyQualifiedName~OpenSearchIntegration" 2>&1 |
      Select-String -Pattern 'passed|failed|skipped|total:|succeeded:|Assert|error' | ForEach-Object { $_.Line }
}
Write-Host "== OpenSearch $v, restricted user, TLS (demo cert, AllowInvalidCertificate)"
Run-It $base 'gimano_it' $userPw 'true'
if ($AlsoUrl) { Write-Host "== $AlsoUrl"; Run-It $AlsoUrl '' '' 'false' }
docker rm -f $name | Out-Null
Write-Host 'done'
