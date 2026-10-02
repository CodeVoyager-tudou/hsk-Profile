# 前台启动单个服务（会先加载 .env.local）
#   .\scripts\start-service.ps1 chronic-shop-service
#   .\scripts\start-service.ps1 chronic-user-service -Profile dev
param(
    [Parameter(Mandatory = $true)][string]$Service,
    [string]$Profile = ""
)

$ErrorActionPreference = "Stop"
$projectRoot = Split-Path -Parent $PSScriptRoot
. (Join-Path $PSScriptRoot "local-env.ps1")

$jar = Join-Path $projectRoot "$Service\target\$Service-1.0.0-SNAPSHOT.jar"
if (-not (Test-Path $jar)) {
    throw "未找到 $jar，请先执行：mvn -pl $Service -am package -DskipTests"
}

# Same as start-all.ps1: Nacos config bodies contain non-ASCII, and a GBK-default JVM
# makes the YAML parse fail silently. Keep this file ASCII-only (it carries no BOM).
$arguments = @("-Dfile.encoding=UTF-8", "-jar", $jar)
if ($Profile -ne "") {
    $arguments += "--spring.profiles.active=$Profile"
}
Write-Host "启动 $Service ..."
& java @arguments
