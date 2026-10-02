# Start user-service with the dev profile (AI entry -> nginx:9000 on the VM).
# Usage:
#   powershell -File deploy\scripts\start-user-service-dev.ps1
#   powershell -File deploy\scripts\start-user-service-dev.ps1 -StopExisting
# NOTE: keep this file ASCII-only. Windows PowerShell 5.1 mis-decodes UTF-8 files
# without BOM, so non-ASCII comments can silently break parsing.
param(
    [string]$Java = "java",
    [int]$WaitSeconds = 90,
    [switch]$StopExisting
)

$ErrorActionPreference = "Stop"

# <repo>/deploy/scripts/ -> repo root
$repo = (Resolve-Path (Join-Path $PSScriptRoot "..\..")).Path
$jar = Join-Path $repo "chronic-user-service\target\chronic-user-service-1.0.0-SNAPSHOT.jar"
if (-not (Test-Path $jar)) { throw "jar not found, run 'mvn package' first: $jar" }

if ($StopExisting) {
    $owner = (Get-NetTCPConnection -LocalPort 8081 -State Listen -ErrorAction SilentlyContinue).OwningProcess | Select-Object -First 1
    if ($owner) {
        Stop-Process -Id $owner -Force
        Write-Host "stopped existing user-service pid=$owner"
        Start-Sleep -Seconds 2
    }
}

$logDir = Join-Path $repo "chronic-user-service\target"
$proc = Start-Process -FilePath $Java `
    -ArgumentList "-jar", $jar, "--spring.profiles.active=dev" `
    -WorkingDirectory $repo `
    -RedirectStandardOutput (Join-Path $logDir "run.out") `
    -RedirectStandardError  (Join-Path $logDir "run.err") `
    -PassThru -WindowStyle Hidden
Write-Host "user-service(dev) started pid=$($proc.Id)"

# Fast TCP probe: Test-NetConnection can block for tens of seconds when the port is closed.
function Test-PortOpen {
    param([string]$Target, [int]$Port, [int]$TimeoutMs = 1000)
    $client = New-Object System.Net.Sockets.TcpClient
    try {
        $async = $client.BeginConnect($Target, $Port, $null, $null)
        if (-not $async.AsyncWaitHandle.WaitOne($TimeoutMs)) { return $false }
        $client.EndConnect($async)
        return $true
    } catch {
        return $false
    } finally {
        $client.Close()
    }
}

for ($i = 0; $i -lt [Math]::Max(1, [int]($WaitSeconds / 2)); $i++) {
    Start-Sleep -Seconds 2
    if (Test-PortOpen -Target "127.0.0.1" -Port 8081) {
        Write-Host "8081 ready (dev profile, chronic.ai.base-url=http://192.168.100.128:9000)"
        exit 0
    }
}
throw "8081 not ready within ${WaitSeconds}s, check $logDir\run.err"
