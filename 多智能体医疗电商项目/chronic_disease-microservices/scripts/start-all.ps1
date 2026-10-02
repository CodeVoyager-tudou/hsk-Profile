# Start the four Java services in the background (hidden windows).
# Health check: each service exposes /actuator/health/readiness
#
# NOTE: this file is ASCII-only ON PURPOSE. It carries no UTF-8 BOM (the repo's
# scripts/check-utf8-bom.py forbids BOM), and PowerShell 5.1 decodes a BOM-less script
# with the local ANSI codepage (GBK here), which turned the previous Chinese comments
# into a ParserError -- i.e. this script could not run at all. Keep it ASCII.
#
# -Dfile.encoding=UTF-8 is required: the config now lives in Nacos and the YAML bodies
# contain non-ASCII comments. A GBK-default JVM makes SCA's YAML parsing throw
# MalformedInputException, which the "optional:" import prefix swallows silently --
# it then surfaces as a misleading "Could not resolve placeholder 'chronic.jwt.secret'".
$ErrorActionPreference = "Stop"
$projectRoot = Split-Path -Parent $PSScriptRoot
. (Join-Path $PSScriptRoot "local-env.ps1")

$services = @("chronic-gateway", "chronic-user-service", "chronic-points-service", "chronic-shop-service")
foreach ($service in $services) {
    $jar = Join-Path $projectRoot "$service\target\$service-1.0.0-SNAPSHOT.jar"
    if (-not (Test-Path $jar)) {
        Write-Warning ("skip {0} : jar not found ({1}). Build first: mvn -pl {0} -am package -DskipTests" -f $service, $jar)
        continue
    }
    $process = Start-Process -FilePath "java" -ArgumentList @("-Dfile.encoding=UTF-8", "-jar", $jar) -WorkingDirectory $projectRoot -WindowStyle Hidden -PassThru
    Write-Host ("started {0} (PID {1})" -f $service, $process.Id)
}
