$ErrorActionPreference = "Continue"
. (Join-Path $PSScriptRoot "demo-common.ps1")

if (-not (Test-Path -LiteralPath $script:StateFile)) {
    Write-Host "没有找到由一键启动脚本记录的进程。"
    return
}

$state = Get-Content -LiteralPath $script:StateFile -Raw -Encoding UTF8 | ConvertFrom-Json
foreach ($entry in $state.processes.PSObject.Properties) {
    $pidValue = [int]$entry.Value
    if (Get-Process -Id $pidValue -ErrorAction SilentlyContinue) {
        Write-Host "正在停止 $($entry.Name) (PID $pidValue)..."
        & taskkill.exe /PID $pidValue /T /F | Out-Null
    }
}
Remove-Item -LiteralPath $script:StateFile -Force -ErrorAction SilentlyContinue
Write-Host "一键启动脚本管理的服务已停止。" -ForegroundColor Green
