param(
    [switch]$SkipStart,
    [switch]$NoBrowser,
    [switch]$ConfirmExternalModelTransfer,
    [ValidateRange(60, 600)][int]$RealModelTimeoutSeconds = 240
)

$ErrorActionPreference = "Stop"
. (Join-Path $PSScriptRoot "demo-common.ps1")

if (-not $ConfirmExternalModelTransfer) {
    throw "Real-model acceptance sends course-derived prompts to the configured external model provider. Re-run with -ConfirmExternalModelTransfer only after approving that transfer."
}

if (-not $SkipStart) {
    Write-Host "[1/5] Start and verify all services" -ForegroundColor Cyan
    & (Join-Path $PSScriptRoot "demo-start.ps1") -NoBrowser
    if (-not $?) { throw "Demo services failed to start." }
} else {
    Write-Host "[1/5] Reuse running services" -ForegroundColor Cyan
}

if (-not (Test-DemoEndpoint -Uri "http://127.0.0.1:8000/health" -TimeoutSeconds 5)) {
    throw "Python AI is unavailable. Final acceptance cannot use prepared-data fallback."
}
if (-not (Test-DemoEndpoint -Uri "http://127.0.0.1:8080/api/system/health" -TimeoutSeconds 5)) {
    throw "Java backend is unavailable."
}
if (-not (Test-DemoEndpoint -Uri "http://127.0.0.1:5173/task-center" -TimeoutSeconds 5)) {
    throw "Vue frontend or the task-center route is unavailable."
}

$venvPython = Join-Path $script:RepoRoot "ai-agent-service\.venv\Scripts\python.exe"
$python = if (Test-Path -LiteralPath $venvPython) {
    $venvPython
} else {
    (Get-Command python.exe -ErrorAction Stop).Source
}
$env:PYTHONUTF8 = "1"

Write-Host "[2/5] Verify prepared student and teacher demo routes" -ForegroundColor Cyan
& (Join-Path $PSScriptRoot "demo-smoke.ps1")
if (-not $?) { throw "Prepared demo route acceptance failed." }

Write-Host "[3/5] Verify real course-corpus retrieval" -ForegroundColor Cyan
& $python (Join-Path $PSScriptRoot "smoke_kb_retrieval.py") --course-id 2 --top-k 2
if ($LASTEXITCODE -ne 0) { throw "Real knowledge retrieval acceptance failed." }

Write-Host "[4/5] Run one-resource real-model closure" -ForegroundColor Cyan
& $python (Join-Path $PSScriptRoot "smoke_real_model_agents.py") `
    --skip-ai-direct `
    --resource-types lecture `
    --timeout $RealModelTimeoutSeconds
if ($LASTEXITCODE -ne 0) { throw "Real-model resource closure acceptance failed." }

Write-Host "[5/5] Final acceptance result" -ForegroundColor Cyan
Write-Host "PASS  Services, demo data, real RAG, real model, task center, persistence, and quality gate." -ForegroundColor Green

if (-not $NoBrowser) {
    Start-Process "http://127.0.0.1:5173/task-center"
}
