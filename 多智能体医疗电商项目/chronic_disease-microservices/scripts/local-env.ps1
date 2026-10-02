# 生成本地开发用的 .env.local（不入库）并加载到当前进程环境变量。
#   . .\scripts\local-env.ps1          # 加载/首次生成
#   . .\scripts\local-env.ps1 -Force   # 重新生成随机密钥
param([switch]$Force)

$ErrorActionPreference = "Stop"
$projectRoot = Split-Path -Parent $PSScriptRoot
$envFile = Join-Path $projectRoot ".env.local"

function New-Secret([int]$Bytes) {
    $buffer = New-Object byte[] $Bytes
    [System.Security.Cryptography.RandomNumberGenerator]::Create().GetBytes($buffer)
    return (($buffer | ForEach-Object { $_.ToString("x2") }) -join "")
}

if ((-not (Test-Path $envFile)) -or $Force) {
    $lines = @()
    $lines += "# 本地开发环境变量（自动生成，已被 .gitignore 忽略；生产请用密钥管理注入）"
    $lines += "JWT_SECRET=" + (New-Secret 48)
    $lines += "INTERNAL_TOKEN=" + (New-Secret 24)
    $lines += "XXL_JOB_ACCESS_TOKEN=" + (New-Secret 16)
    $lines += "AI_INTERNAL_TOKEN="
    # OSS 密钥无法随机生成（来自阿里云控制台），留占位提示用户手填；
    # 留空时 OSS Bean 不装配，图片上传接口报 503，不影响其他功能启动
    $lines += "OSS_BUCKET=qk-parent-xcu"
    $lines += "OSS_ACCESS_KEY_ID="
    $lines += "OSS_ACCESS_KEY_SECRET="
    # 接口文档面板开关：本地开发开着；生产注入 KNIFE4J_ENABLE=false（启动自检会断言）
    $lines += "KNIFE4J_ENABLE=true"
    $lines += "MYSQL_HOST=192.168.100.128"
    $lines += "MYSQL_PORT=3307"
    $lines += "MYSQL_USERNAME=root"
    $lines += "MYSQL_PASSWORD=root"
    $lines += "REDIS_HOST=192.168.100.128"
    $lines += "REDIS_PORT=6379"
    $lines += "REDIS_PASSWORD=1234"
    Set-Content -LiteralPath $envFile -Value $lines -Encoding UTF8
    Write-Host "已生成 $envFile（JWT/内部令牌为随机值；MySQL/Redis 口令取自本地 VM 的开发配置）"
}

Get-Content -LiteralPath $envFile | Where-Object { $_ -match '^[^#\s][^=]*=' } | ForEach-Object {
    $pair = $_ -split '=', 2
    Set-Item -Path ("Env:" + $pair[0].Trim()) -Value $pair[1].Trim()
}
Write-Host "已加载环境变量：JWT_SECRET / INTERNAL_TOKEN / XXL_JOB_ACCESS_TOKEN / MYSQL_* / REDIS_*"
