<#
.SYNOPSIS
  Adds missing secrets to a git-ignored env file (deploy/.env by default) without printing them.
  Existing non-placeholder values are never changed. Keys: JWT_SIGNING_KEY, FEEDBACK_CONTACT_KEY, ADMIN_KEY.
.EXAMPLE
  pwsh deploy/ensure-secrets.ps1
  pwsh deploy/ensure-secrets.ps1 -EnvFile deploy/.env.prod
#>
param([string]$EnvFile = 'deploy/.env')
$ErrorActionPreference = 'Stop'
if (-not (Test-Path $EnvFile)) { throw "$EnvFile not found." }
function Rand([int]$n) { [Convert]::ToBase64String([System.Security.Cryptography.RandomNumberGenerator]::GetBytes($n)) }
$wanted = [ordered]@{ JWT_SIGNING_KEY = { (Rand 48) }; FEEDBACK_CONTACT_KEY = { (Rand 32) }; ADMIN_KEY = { (Rand 36) } }
$lines = [System.Collections.Generic.List[string]](Get-Content $EnvFile)
foreach ($k in $wanted.Keys) {
    $i = $lines.FindIndex({ param($l) $l -match "^\s*$k=" })
    $current = if ($i -ge 0) { $lines[$i] -replace "^\s*$k=", '' } else { '' }
    if ($current -and $current -ne 'CHANGE_ME') { Write-Host "  kept: $k"; continue }
    $line = "$k=$(& $wanted[$k])"
    if ($i -ge 0) { $lines[$i] = $line } else { $lines.Add($line) }
    Write-Host "  set:  $k (value not shown)"
}
Set-Content -Path $EnvFile -Value $lines -Encoding ascii
