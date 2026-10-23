<#
.SYNOPSIS
  DEV ONLY (WS556): seed complaints for app testing through POST /api/v1/admin/dev/issues (needs Features:DevSeed=true,
  which only deploy/docker-compose.yml sets, and ADMIN_KEY from deploy/.env, read here and never printed).
.EXAMPLE
  pwsh deploy/dev-seed-issues.ps1 -Kind road_closed_temp -Lat 3.1196 -Lng 101.6214 -Heading 0 -Subtype flood
  pwsh deploy/dev-seed-issues.ps1 -Kind place_closed_perm -PlaceId n12345 -State suspected
  pwsh deploy/dev-seed-issues.ps1 -Kind place_closed_temp -PlaceId n12345 -Until 2026-11-15
  pwsh deploy/dev-seed-issues.ps1 -Delete 0193...   # remove a seeded issue
#>
param(
    [string]$Kind = 'road_closed_temp', [double]$Lat = 0, [double]$Lng = 0, [double]$Heading = -1, [string]$Subtype,
    [string]$PlaceId, [ValidateSet('suspected', 'confirmed')][string]$State = 'confirmed', [string]$Until, [string]$OpeningHours,
    [string]$Delete, [string]$Api = 'http://localhost:5080')
$ErrorActionPreference = 'Stop'
$line = Get-Content (Join-Path $PSScriptRoot '.env') | Where-Object { $_ -match '^\s*ADMIN_KEY\s*=' } | Select-Object -First 1
if (-not $line) { throw 'ADMIN_KEY is not set in deploy/.env (admin endpoints are disabled without it).' }
$headers = @{ 'X-Admin-Key' = ($line -replace '^\s*ADMIN_KEY\s*=\s*', '').Trim().Trim('"'); 'Accept-Language' = 'ms' }
if ($Delete) {
    Invoke-RestMethod "$Api/api/v1/admin/dev/issues/$Delete" -Method Delete -Headers $headers | Out-Null
    Write-Host "deleted $Delete"; return
}
$issue = @{ kind = $Kind; lat = $Lat; lng = $Lng; source = 'report' }
if ($Heading -ge 0) { $issue.heading = $Heading }
if ($Subtype) { $issue.subtype = $Subtype }
if ($PlaceId) { $issue.placeId = $PlaceId }
$payload = @{}
if ($Until) { $payload.until = $Until }
if ($OpeningHours) { $payload.openingHours = $OpeningHours }
if ($payload.Count -gt 0) { $issue.payload = $payload }
$body = @{ issue = $issue; state = $State } | ConvertTo-Json -Depth 5
$r = Invoke-RestMethod "$Api/api/v1/admin/dev/issues" -Method Post -Headers $headers -ContentType 'application/json' -Body $body
"{0}  {1}  {2}  {3}  ({4:N5}, {5:N5})" -f $r.id, $r.kind, $r.state, $r.summary, $r.lat, $r.lng
