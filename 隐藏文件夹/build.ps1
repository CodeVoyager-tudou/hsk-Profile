# HiddenDisk 编译脚本：用系统自带 csc.exe（仅支持 C#5），输出 bin\HiddenDisk.exe
# 用法：powershell -NoProfile -ExecutionPolicy Bypass -File build.ps1
$ErrorActionPreference = "Stop"
$root = Split-Path -Parent $MyInvocation.MyCommand.Path

$csc = Join-Path $env:WINDIR "Microsoft.NET\Framework64\v4.0.30319\csc.exe"
if (-not (Test-Path $csc)) { $csc = Join-Path $env:WINDIR "Microsoft.NET\Framework\v4.0.30319\csc.exe" }
if (-not (Test-Path $csc)) { throw "未找到 csc.exe（需要 .NET Framework 4.x）" }

New-Item -ItemType Directory -Force -Path (Join-Path $root "bin") | Out-Null

$sources = Get-ChildItem -Path (Join-Path $root "src") -Filter *.cs | Sort-Object Name | ForEach-Object { $_.FullName }
if (-not $sources) { throw "src 目录下没有 .cs 源文件" }

# /codepage:65001 —— 源码含中文 UI 文本，必须按 UTF-8 解析防乱码
& $csc /nologo /target:winexe /platform:anycpu /codepage:65001 /optimize+ `
    /out:"$root\bin\HiddenDisk.exe" `
    /r:System.dll /r:System.Core.dll /r:System.Drawing.dll /r:System.Windows.Forms.dll `
    $sources

if ($LASTEXITCODE -ne 0) { throw "编译失败，退出码 $LASTEXITCODE" }
Write-Host "OK -> $root\bin\HiddenDisk.exe"
