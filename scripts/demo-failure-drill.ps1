param(
    [string]$TeacherUsername = "teacher",
    [string]$TeacherPassword = "123456",
    [switch]$IncludeBackendRestart,
    [ValidateRange(30, 180)][int]$RecoveryTimeoutSeconds = 120
)

$ErrorActionPreference = "Stop"
. (Join-Path $PSScriptRoot "demo-common.ps1")
$resultFile = Join-Path $script:RunRoot "demo-failure-drill.result.json"
Remove-Item -LiteralPath $resultFile -Force -ErrorAction SilentlyContinue

function Assert-DrillCondition {
    param([bool]$Condition, [string]$Message)
    if (-not $Condition) { throw $Message }
}

function Get-ManagedServicePid {
    param([Parameter(Mandatory = $true)][string]$Service)
    if (-not (Test-Path -LiteralPath $script:StateFile)) {
        throw "Missing .run\demo-processes.json. Run demo-recover.ps1 before the failure drill."
    }
    $state = Get-Content -LiteralPath $script:StateFile -Raw -Encoding UTF8 | ConvertFrom-Json
    $property = $state.processes.PSObject.Properties[$Service]
    if ($null -eq $property) {
        throw "$Service is not managed by the demo launcher. The drill stopped to avoid terminating an unrelated process."
    }
    $pidValue = [int]$property.Value
    if (-not (Get-Process -Id $pidValue -ErrorAction SilentlyContinue)) {
        throw "Managed $Service process $pidValue no longer exists. Run demo-recover.ps1 first."
    }
    return $pidValue
}

function Stop-ManagedServiceForDrill {
    param(
        [Parameter(Mandatory = $true)][string]$Service,
        [Parameter(Mandatory = $true)][string]$HealthUri
    )
    $pidValue = Get-ManagedServicePid -Service $Service
    & taskkill.exe /PID $pidValue /T /F | Out-Null
    if ($LASTEXITCODE -ne 0) { throw "Unable to stop managed $Service process $pidValue." }
    $deadline = (Get-Date).AddSeconds(15)
    while ((Get-Date) -lt $deadline -and (Test-DemoEndpoint -Uri $HealthUri)) {
        Start-Sleep -Milliseconds 400
    }
    Assert-DrillCondition (-not (Test-DemoEndpoint -Uri $HealthUri)) "$Service remains reachable after the stop operation."
}

function Get-TeacherHeaders {
    $body = @{ username = $TeacherUsername; password = $TeacherPassword } | ConvertTo-Json -Compress
    $login = Invoke-RestMethod -Method POST -Uri "http://127.0.0.1:8080/api/auth/login" `
        -ContentType "application/json; charset=utf-8" -Body $body -TimeoutSec 15
    return @{ Authorization = "Bearer $($login.data.token)" }
}

function Get-DemoPreflight {
    param([Parameter(Mandatory = $true)][hashtable]$Headers)
    return Invoke-RestMethod -Method GET -Uri "http://127.0.0.1:8080/api/demo/preflight" `
        -Headers $Headers -TimeoutSec 15
}

Write-Host "=== EduPath failure fallback and recovery drill ===" -ForegroundColor Cyan
& (Join-Path $PSScriptRoot "demo-start.ps1") -NoBrowser
& (Join-Path $PSScriptRoot "demo-smoke.ps1")
$teacherHeaders = Get-TeacherHeaders

Write-Host "[1/4] Simulate Python AI outage" -ForegroundColor Cyan
$aiStopped = $false
try {
    Stop-ManagedServiceForDrill -Service "ai" -HealthUri "http://127.0.0.1:8000/health"
    $aiStopped = $true
    $preflight = Get-DemoPreflight -Headers $teacherHeaders
    Assert-DrillCondition ($preflight.data.overall_status -eq "degraded") "AI outage did not select degraded status."
    Assert-DrillCondition ($preflight.data.recommended_mode -eq "prepared_data_only") "AI outage did not select prepared_data_only."
    Assert-DrillCondition ($preflight.data.prepared_demo_available -eq $true) "Prepared demo data became unavailable during the AI outage."
    Assert-DrillCondition ($preflight.data.live_ai_available -eq $false) "Live AI was still reported as available during the outage."

    Write-Host "[2/4] Verify the prepared-data route remains available" -ForegroundColor Cyan
    & (Join-Path $PSScriptRoot "demo-smoke.ps1")
} finally {
    if ($aiStopped) {
        Write-Host "      Recovering Python AI..." -ForegroundColor Yellow
        & (Join-Path $PSScriptRoot "demo-start.ps1") -NoBrowser -StartupTimeoutSeconds $RecoveryTimeoutSeconds
    }
}

Write-Host "[3/4] Verify live mode after AI recovery" -ForegroundColor Cyan
Assert-DrillCondition (Wait-DemoEndpoint -Uri "http://127.0.0.1:8000/health" -TimeoutSeconds $RecoveryTimeoutSeconds -Label "Python AI") "Python AI recovery failed."
$teacherHeaders = Get-TeacherHeaders
$recoveredPreflight = Get-DemoPreflight -Headers $teacherHeaders
Assert-DrillCondition ($recoveredPreflight.data.overall_status -eq "ready") "Preflight did not return to ready after AI recovery."
Assert-DrillCondition ($recoveredPreflight.data.recommended_mode -eq "live_ai") "Preflight did not return to live_ai after AI recovery."

if ($IncludeBackendRestart) {
    Write-Host "      Simulate Java backend outage and blocking detection" -ForegroundColor Cyan
    $backendStopped = $false
    try {
        Stop-ManagedServiceForDrill -Service "backend" -HealthUri "http://127.0.0.1:8080/api/system/health"
        $backendStopped = $true
        Assert-DrillCondition (Test-DemoEndpoint -Uri "http://127.0.0.1:5173") "The frontend entry also became unavailable during the backend outage."
        Assert-DrillCondition (Test-DemoEndpoint -Uri "http://127.0.0.1:8000/health") "The AI service also became unavailable during the backend outage."
    } finally {
        if ($backendStopped) {
            Write-Host "      Recovering Java backend..." -ForegroundColor Yellow
            & (Join-Path $PSScriptRoot "demo-start.ps1") -NoBrowser -StartupTimeoutSeconds $RecoveryTimeoutSeconds
        }
    }
    Assert-DrillCondition (Wait-DemoEndpoint -Uri "http://127.0.0.1:8080/api/system/health" -TimeoutSeconds $RecoveryTimeoutSeconds -Label "Java backend") "Java backend recovery failed."
}

Write-Host "[4/4] Rerun the complete demo route after recovery" -ForegroundColor Cyan
& (Join-Path $PSScriptRoot "demo-smoke.ps1")
$backendResult = if ($IncludeBackendRestart) { ", backend blocking detection and recovery" } else { "" }
Write-Host "PASS  AI fallback, prepared-data route, live-mode recovery$backendResult." -ForegroundColor Green
[ordered]@{
    status = "pass"
    ai_fallback = $true
    prepared_data_route = $true
    live_mode_recovery = $true
    backend_blocking_and_recovery = [bool]$IncludeBackendRestart
    completed_at = (Get-Date).ToString("o")
} | ConvertTo-Json | Set-Content -LiteralPath $resultFile -Encoding UTF8
