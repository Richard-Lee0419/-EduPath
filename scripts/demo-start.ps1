param(
    [switch]$NoBrowser,
    [switch]$SkipAi,
    [ValidateRange(20, 180)][int]$StartupTimeoutSeconds = 90
)

$ErrorActionPreference = "Stop"
. (Join-Path $PSScriptRoot "demo-common.ps1")
Initialize-DemoRunDirectory

$hadExistingState = Test-Path -LiteralPath $script:StateFile
$managedProcesses = [ordered]@{}
$existingStartedAt = $null
if ($hadExistingState) {
    try {
        $existingState = Get-Content -LiteralPath $script:StateFile -Raw -Encoding UTF8 | ConvertFrom-Json
        $existingStartedAt = $existingState.started_at
        foreach ($entry in $existingState.processes.PSObject.Properties) {
            $pidValue = [int]$entry.Value
            if (Get-Process -Id $pidValue -ErrorAction SilentlyContinue) {
                $managedProcesses[$entry.Name] = $pidValue
            }
        }
    } catch {
        Write-Warning "现有演示进程状态无法读取，将在本次启动后重建。"
    }
}

$state = [ordered]@{
    started_at = if ($existingStartedAt) { $existingStartedAt } else { (Get-Date).ToString("o") }
    repo_root = $script:RepoRoot
    processes = $managedProcesses
}
if ($hadExistingState) { Save-DemoProcessState -State $state }

Write-Host "[1/3] 检查 Python AI 服务..." -ForegroundColor Cyan
$aiReady = Test-DemoEndpoint -Uri "http://127.0.0.1:8000/health"
if (-not $aiReady -and -not $SkipAi) {
    Assert-DemoPortFree -Port 8000 -Label "Python AI 服务"
    $envFile = Resolve-DemoModelEnvFile
    $extra = if ($envFile) { "-EnvFile `"$envFile`"" } else { "" }
    $process = Start-DemoManagedProcess -Name "ai" -ScriptPath (Join-Path $PSScriptRoot "run-ai-service.ps1") -ExtraArguments $extra
    $state.processes['ai'] = $process.Id
    Save-DemoProcessState -State $state
    $aiReady = Wait-DemoEndpoint -Uri "http://127.0.0.1:8000/health" -TimeoutSeconds ([Math]::Min(50, $StartupTimeoutSeconds)) -Label "Python AI 服务"
}
if ($aiReady) {
    Write-Host "      AI 服务已就绪。" -ForegroundColor Green
} else {
    Write-Warning "AI 服务当前不可用，将继续启动已准备数据演示模式。"
}

Write-Host "[2/3] 检查 Java 后端..." -ForegroundColor Cyan
$backendReady = Test-DemoEndpoint -Uri "http://127.0.0.1:8080/api/system/health"
if (-not $backendReady) {
    Assert-DemoPortFree -Port 8080 -Label "Java 后端"
    $process = Start-DemoManagedProcess -Name "backend" -ScriptPath (Join-Path $PSScriptRoot "run-backend.ps1")
    $state.processes['backend'] = $process.Id
    Save-DemoProcessState -State $state
    $backendReady = Wait-DemoEndpoint -Uri "http://127.0.0.1:8080/api/system/health" -TimeoutSeconds $StartupTimeoutSeconds -Label "Java 后端"
}
if (-not $backendReady) {
    throw "Java 后端启动失败，请查看 .run\backend.stderr.log。"
}
Write-Host "      后端已就绪。" -ForegroundColor Green

Write-Host "[3/3] 检查 Vue 前端..." -ForegroundColor Cyan
$frontendReady = Test-DemoEndpoint -Uri "http://127.0.0.1:5173"
if (-not $frontendReady) {
    Assert-DemoPortFree -Port 5173 -Label "Vue 前端"
    $process = Start-DemoManagedProcess -Name "frontend" -ScriptPath (Join-Path $PSScriptRoot "run-frontend.ps1")
    $state.processes['frontend'] = $process.Id
    Save-DemoProcessState -State $state
    $frontendReady = Wait-DemoEndpoint -Uri "http://127.0.0.1:5173" -TimeoutSeconds $StartupTimeoutSeconds -Label "Vue 前端"
}
if (-not $frontendReady) {
    throw "Vue 前端启动失败，请查看 .run\frontend.stderr.log。"
}
Write-Host "      前端已就绪。" -ForegroundColor Green

Write-Host ""
Write-Host "知径 EduPath 已启动：http://127.0.0.1:5173/login" -ForegroundColor Green
Write-Host "教师账号登录后进入“演示模式”，先准备数据，再运行系统预检。"
if (-not $NoBrowser) { Open-DemoBrowser }
