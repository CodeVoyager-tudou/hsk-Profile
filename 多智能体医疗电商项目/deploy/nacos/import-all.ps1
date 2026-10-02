# Publish the 9 yaml files in this directory to Nacos (Group=DEFAULT_GROUP, format YAML).
# Usage (inject credentials first; see scripts/local-env.ps1):
#   $env:NACOS_ADDR='192.168.100.128:8848'
#   $env:NACOS_USERNAME='xxx'; $env:NACOS_PASSWORD='xxx'
#   $env:NACOS_NAMESPACE='<namespace ID>'   # optional, empty = public
#   .\deploy\nacos\import-all.ps1
# Idempotent: re-running overwrites the same dataIds with the current file contents.
# NOTE: this file is intentionally ASCII-only and BOM-less -- scripts/check-utf8-bom.py
# rejects UTF-8 BOM in source files, and PowerShell 5.1 would misread non-ASCII here.
$ErrorActionPreference = 'Stop'

$nacos = if ($env:NACOS_ADDR) { $env:NACOS_ADDR } else { '192.168.100.128:8848' }
if ($nacos -notmatch '^https?://') { $nacos = "http://$nacos" }
if (-not $env:NACOS_USERNAME -or -not $env:NACOS_PASSWORD) {
    throw 'Set NACOS_USERNAME / NACOS_PASSWORD environment variables first'
}
$tenant = $env:NACOS_NAMESPACE
$dir = $PSScriptRoot

$login = Invoke-RestMethod -Method Post -Uri "$nacos/nacos/v1/auth/login" `
    -Body @{ username = $env:NACOS_USERNAME; password = $env:NACOS_PASSWORD }
$token = $login.accessToken
if (-not $token) { throw 'Login returned no accessToken; check the Nacos address and credentials' }

$files = 'chronic-ai-prompts', 'chronic-common', 'chronic-common-service', 'chronic-common-redis',
         'chronic-common-oss', 'chronic-common-security',
         'chronic-gateway', 'chronic-user-service', 'chronic-points-service', 'chronic-shop-service'

foreach ($f in $files) {
    $path = Join-Path $dir "$f.yaml"
    # ReadAllText as UTF-8 so the non-ASCII comments survive the upload
    $content = [System.IO.File]::ReadAllText($path, [System.Text.Encoding]::UTF8)
    $body = @{
        accessToken = $token
        dataId      = "$f.yaml"
        group       = 'DEFAULT_GROUP'
        type        = 'yaml'
        content     = $content
    }
    if ($tenant) { $body['tenant'] = $tenant }
    $resp = Invoke-RestMethod -Method Post -Uri "$nacos/nacos/v1/cs/configs" -Body $body
    Write-Host "$f.yaml -> $resp"
}
