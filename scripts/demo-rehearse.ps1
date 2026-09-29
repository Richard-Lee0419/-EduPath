param([switch]$NoBrowser)

$ErrorActionPreference = "Stop"
Write-Host "=== 知径 EduPath 软件杯演示彩排 ===" -ForegroundColor Cyan
& (Join-Path $PSScriptRoot "demo-start.ps1") -NoBrowser

& (Join-Path $PSScriptRoot "demo-smoke.ps1")

Write-Host "彩排检查通过。浏览器中使用 teacher / 123456 登录，进入“演示模式”。" -ForegroundColor Green
if (-not $NoBrowser) {
    . (Join-Path $PSScriptRoot "demo-common.ps1")
    Open-DemoBrowser
}
