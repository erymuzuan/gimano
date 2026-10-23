<#
.SYNOPSIS
  Idempotently configures IIS ARR on 172.30.30.13 to reverse-proxy https://gimano-api.bukitbunga.com
  to the Gimano API container on 172.30.30.150, following the existing server pattern
  (webFarm + server-level globalRules pair + a plain IIS site holding the host bindings).

.DESCRIPTION
  Creates or updates only these objects:
    - web farm            gimano-api-docker  ->  <BackendAddress>:<BackendPort>
    - global rewrite rules "Redirect-HTTP-to-HTTPS-gimano-api.bukitbunga.com" and "Proxy-gimano-api.bukitbunga.com"
      (HTTP_HOST ^gimano-api\.bukitbunga\.com$, /.well-known/acme-challenge/ excluded, rewrite to http://gimano-api-docker/{R:1})
    - allowed server variable HTTP_X_FORWARDED_PROTO (append only)
    - app pool + site "gimano-api.bukitbunga.com" (C:\apps\websites\gimano-api.bukitbunga.com, only a web.config)
      with http :80 and https :443 SNI bindings (cert by thumbprint), WebSockets on (SignalR /hubs/live)
  The only global setting it may change: it turns on the ARR proxy (system.webServer/proxy enabled) if it's off,
  and says so. Nothing else on the server is modified.

.PARAMETER CertThumbprint
  Thumbprint of the certificate for gimano-api.bukitbunga.com in LocalMachine\My (Let's Encrypt via the
  server's existing ACME client). If omitted, the https binding is created/kept without changing its cert.

.EXAMPLE
  .\gimano-api.ps1 -WhatIf
  .\gimano-api.ps1 -CertThumbprint 0123ABCD...
#>
[CmdletBinding(SupportsShouldProcess)]
param(
    [string]$HostName = 'gimano-api.bukitbunga.com',
    [string]$BackendAddress = '172.30.30.150',
    [int]$BackendPort = 5080,
    [string]$FarmName = 'gimano-api-docker',
    [string]$PhysicalPath = 'C:\apps\websites\gimano-api.bukitbunga.com',
    [string]$CertThumbprint
)
$ErrorActionPreference = 'Stop'
Import-Module WebAdministration
$apphost = 'MACHINE/WEBROOT/APPHOST'
$hostPattern = '^' + [regex]::Escape($HostName) + '$'
$redirectRule = "Redirect-HTTP-to-HTTPS-$HostName"
$proxyRule = "Proxy-$HostName"
$acme = '^/\.well-known/acme-challenge/'

function Step([string]$what, [scriptblock]$do) {
    if ($PSCmdlet.ShouldProcess($what)) { & $do; Write-Host "  done: $what" } else { Write-Host "  would: $what" }
}

Write-Host "== ARR proxy"
$proxyEnabled = (Get-WebConfigurationProperty -PSPath $apphost -Filter 'system.webServer/proxy' -Name enabled).Value
if (-not $proxyEnabled) {
    Write-Warning 'ARR proxy is DISABLED server-wide; enabling it (system.webServer/proxy enabled=true). This is the only global change.'
    Step 'enable ARR proxy' { Set-WebConfigurationProperty -PSPath $apphost -Filter 'system.webServer/proxy' -Name enabled -Value $true }
} else { Write-Host '  ok: ARR proxy already enabled (left as is)' }

Write-Host "== Web farm $FarmName -> ${BackendAddress}:$BackendPort"
$farm = Get-WebConfiguration -PSPath $apphost -Filter "webFarms/webFarm[@name='$FarmName']"
if (-not $farm) { Step "create web farm $FarmName" { Add-WebConfigurationProperty -PSPath $apphost -Filter 'webFarms' -Name '.' -Value @{ name = $FarmName; enabled = 'True' } } }
$server = Get-WebConfiguration -PSPath $apphost -Filter "webFarms/webFarm[@name='$FarmName']/server[@address='$BackendAddress']"
if (-not $server) { Step "add server $BackendAddress" { Add-WebConfigurationProperty -PSPath $apphost -Filter "webFarms/webFarm[@name='$FarmName']" -Name '.' -Value @{ address = $BackendAddress; enabled = 'True' } } }
Step "set backend http port $BackendPort" {
    Set-WebConfigurationProperty -PSPath $apphost -Filter "webFarms/webFarm[@name='$FarmName']/server[@address='$BackendAddress']/applicationRequestRouting" -Name httpPort -Value $BackendPort
}
Step 'farm health check /healthz' {
    Set-WebConfigurationProperty -PSPath $apphost -Filter "webFarms/webFarm[@name='$FarmName']/applicationRequestRouting/healthCheck" -Name url -Value "http://$FarmName/healthz"
    Set-WebConfigurationProperty -PSPath $apphost -Filter "webFarms/webFarm[@name='$FarmName']/applicationRequestRouting/healthCheck" -Name interval -Value '00:00:30'
}

Write-Host '== Allowed server variable HTTP_X_FORWARDED_PROTO'
$allowed = Get-WebConfiguration -PSPath $apphost -Filter "system.webServer/rewrite/allowedServerVariables/add[@name='HTTP_X_FORWARDED_PROTO']"
if (-not $allowed) { Step 'allow HTTP_X_FORWARDED_PROTO' { Add-WebConfigurationProperty -PSPath $apphost -Filter 'system.webServer/rewrite/allowedServerVariables' -Name '.' -Value @{ name = 'HTTP_X_FORWARDED_PROTO' } } }
else { Write-Host '  ok: already allowed' }

function Ensure-GlobalRule([string]$name) {
    $r = Get-WebConfiguration -PSPath $apphost -Filter "system.webServer/rewrite/globalRules/rule[@name='$name']"
    if (-not $r) { Step "create global rule $name" { Add-WebConfigurationProperty -PSPath $apphost -Filter 'system.webServer/rewrite/globalRules' -Name '.' -Value @{ name = $name; patternSyntax = 'ECMAScript'; stopProcessing = 'True' } } }
    return "system.webServer/rewrite/globalRules/rule[@name='$name']"
}

function Set-Conditions([string]$rule, [object[]]$conditions) {
    Step "set conditions on $rule" {
        Clear-WebConfiguration -PSPath $apphost -Filter "$rule/conditions"
        Set-WebConfigurationProperty -PSPath $apphost -Filter "$rule/conditions" -Name logicalGrouping -Value 'MatchAll'
        foreach ($c in $conditions) { Add-WebConfigurationProperty -PSPath $apphost -Filter "$rule/conditions" -Name '.' -Value $c }
    }
}

Write-Host "== Global rule $redirectRule"
$r1 = Ensure-GlobalRule $redirectRule
Step 'redirect rule match/action' {
    Set-WebConfigurationProperty -PSPath $apphost -Filter "$r1/match" -Name url -Value '(.*)'
    Set-WebConfigurationProperty -PSPath $apphost -Filter $r1 -Name stopProcessing -Value $true
    Set-WebConfigurationProperty -PSPath $apphost -Filter "$r1/action" -Name type -Value 'Redirect'
    Set-WebConfigurationProperty -PSPath $apphost -Filter "$r1/action" -Name url -Value 'https://{HTTP_HOST}/{R:1}'
    Set-WebConfigurationProperty -PSPath $apphost -Filter "$r1/action" -Name redirectType -Value 'Permanent'
}
# Cloudflare may reach the origin over http while the visitor used https: don't redirect those (no loop).
Set-Conditions $r1 @(
    @{ input = '{HTTP_HOST}'; pattern = $hostPattern },
    @{ input = '{HTTPS}'; pattern = '^OFF$' },
    @{ input = '{HTTP_X_FORWARDED_PROTO}'; pattern = '^https$'; negate = 'True' },
    @{ input = '{URL}'; pattern = $acme; negate = 'True' }
)

Write-Host "== Global rule $proxyRule"
$r2 = Ensure-GlobalRule $proxyRule
Step 'proxy rule match/action' {
    Set-WebConfigurationProperty -PSPath $apphost -Filter "$r2/match" -Name url -Value '(.*)'
    Set-WebConfigurationProperty -PSPath $apphost -Filter $r2 -Name stopProcessing -Value $true
    Set-WebConfigurationProperty -PSPath $apphost -Filter "$r2/action" -Name type -Value 'Rewrite'
    Set-WebConfigurationProperty -PSPath $apphost -Filter "$r2/action" -Name url -Value "http://$FarmName/{R:1}"
}
Set-Conditions $r2 @(
    @{ input = '{HTTP_HOST}'; pattern = $hostPattern },
    @{ input = '{URL}'; pattern = $acme; negate = 'True' }
)
Step 'proxy rule server variable X-Forwarded-Proto=https' {
    Clear-WebConfiguration -PSPath $apphost -Filter "$r2/serverVariables"
    Add-WebConfigurationProperty -PSPath $apphost -Filter "$r2/serverVariables" -Name '.' -Value @{ name = 'HTTP_X_FORWARDED_PROTO'; value = 'https'; replace = 'True' }
}
# ARR adds X-Forwarded-For itself (server-wide proxy setting, left unchanged); Cloudflare's CF-Connecting-IP passes through.

Write-Host "== Site $HostName"
Step "folder $PhysicalPath + web.config" {
    New-Item -ItemType Directory -Force -Path $PhysicalPath | Out-Null
    @'
<?xml version="1.0" encoding="utf-8"?>
<!-- Placeholder site for gimano-api.bukitbunga.com. Requests are proxied by the server-level rule
     "Proxy-gimano-api.bukitbunga.com" to the gimano-api-docker web farm. WebSockets are needed for SignalR /hubs/live. -->
<configuration>
  <system.webServer>
    <webSocket enabled="true" />
  </system.webServer>
</configuration>
'@ | Set-Content -Path (Join-Path $PhysicalPath 'web.config') -Encoding utf8
}
if (-not (Test-Path "IIS:\AppPools\$HostName")) {
    Step "app pool $HostName (no managed code)" { New-WebAppPool -Name $HostName | Out-Null; Set-ItemProperty "IIS:\AppPools\$HostName" managedRuntimeVersion '' }
}
if (-not (Get-Website -Name $HostName)) {
    Step "create site $HostName (http :80)" { New-Website -Name $HostName -PhysicalPath $PhysicalPath -ApplicationPool $HostName -HostHeader $HostName -Port 80 -IPAddress '*' | Out-Null }
} else { Write-Host '  ok: site exists' }
if (-not (Get-WebBinding -Name $HostName -Protocol http -HostHeader $HostName -Port 80)) {
    Step 'add http :80 binding' { New-WebBinding -Name $HostName -Protocol http -Port 80 -HostHeader $HostName -IPAddress '*' }
}
if (-not (Get-WebBinding -Name $HostName -Protocol https -HostHeader $HostName -Port 443)) {
    Step 'add https :443 SNI binding' { New-WebBinding -Name $HostName -Protocol https -Port 443 -HostHeader $HostName -IPAddress '*' -SslFlags 1 }
}
if ($CertThumbprint) {
    $thumb = $CertThumbprint -replace '\s', ''
    if (-not (Test-Path "Cert:\LocalMachine\My\$thumb")) { throw "Certificate $thumb not found in LocalMachine\My." }
    Step "attach certificate $thumb to https binding" {
        # Replace only this host's SNI certificate mapping (renewals bring a new thumbprint).
        if (Test-Path "IIS:\SslBindings\!443!$HostName") { Remove-Item "IIS:\SslBindings\!443!$HostName" }
        (Get-WebBinding -Name $HostName -Protocol https -HostHeader $HostName -Port 443).AddSslCertificate($thumb, 'My')
    }
} else { Write-Warning 'No -CertThumbprint: the https binding has no certificate change. Re-run with the LE thumbprint once issued.' }

Write-Host "Done. Test: curl -I http://$HostName/healthz (301), https://$HostName/readyz (200), and a WebSocket to wss://$HostName/hubs/live."
