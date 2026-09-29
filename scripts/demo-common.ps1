$script:RepoRoot = Split-Path $PSScriptRoot -Parent
$script:RunRoot = Join-Path $script:RepoRoot ".run"
$script:StateFile = Join-Path $script:RunRoot "demo-processes.json"

function Initialize-DemoRunDirectory {
    New-Item -ItemType Directory -Path $script:RunRoot -Force | Out-Null
}

function Test-DemoEndpoint {
    param([Parameter(Mandatory = $true)][string]$Uri, [int]$TimeoutSeconds = 2)
    try {
        $response = Invoke-WebRequest -Uri $Uri -UseBasicParsing -TimeoutSec $TimeoutSeconds
        return $response.StatusCode -ge 200 -and $response.StatusCode -lt 400
    } catch {
        return $false
    }
}

function Wait-DemoEndpoint {
    param(
        [Parameter(Mandatory = $true)][string]$Uri,
        [int]$TimeoutSeconds = 90,
        [string]$Label = "服务"
    )
    $deadline = (Get-Date).AddSeconds($TimeoutSeconds)
    while ((Get-Date) -lt $deadline) {
        if (Test-DemoEndpoint -Uri $Uri) { return $true }
        Start-Sleep -Milliseconds 700
    }
    Write-Warning "$Label 未在 $TimeoutSeconds 秒内就绪。"
    return $false
}

function Assert-DemoPortFree {
    param([Parameter(Mandatory = $true)][int]$Port, [Parameter(Mandatory = $true)][string]$Label)
    $listener = Get-NetTCPConnection -LocalPort $Port -State Listen -ErrorAction SilentlyContinue
    if ($listener) {
        throw "$Label 的端口 $Port 已被其他进程占用。请先运行 scripts\demo-status.ps1 定位，再关闭冲突进程。"
    }
}

function Resolve-DemoModelEnvFile {
    $candidates = @()
    if ($env:EDUPATH_REAL_MODEL_ENV) { $candidates += $env:EDUPATH_REAL_MODEL_ENV }
    $candidates += (Join-Path $script:RepoRoot "ai-agent-service\.env.real-model.local")
    $workspaceRoot = Split-Path (Split-Path $script:RepoRoot -Parent) -Parent
    $candidates += (Join-Path $workspaceRoot "ai-agent-service\.env.real-model.local")
    return $candidates | Where-Object { $_ -and (Test-Path -LiteralPath $_) } | Select-Object -First 1
}

function Start-DemoManagedProcess {
    param(
        [Parameter(Mandatory = $true)][string]$Name,
        [Parameter(Mandatory = $true)][string]$ScriptPath,
        [string]$ExtraArguments = ""
    )
    $stdout = Join-Path $script:RunRoot "$Name.stdout.log"
    $stderr = Join-Path $script:RunRoot "$Name.stderr.log"
    $arguments = "-NoProfile -ExecutionPolicy Bypass -File `"$ScriptPath`" -RepoRoot `"$script:RepoRoot`""
    if ($ExtraArguments) { $arguments += " $ExtraArguments" }
    return Start-Process powershell.exe `
        -ArgumentList $arguments `
        -WindowStyle Hidden `
        -RedirectStandardOutput $stdout `
        -RedirectStandardError $stderr `
        -PassThru
}

function Save-DemoProcessState {
    param([Parameter(Mandatory = $true)]$State)
    $State | ConvertTo-Json -Depth 5 | Set-Content -LiteralPath $script:StateFile -Encoding UTF8
}

function Open-DemoBrowser {
    Start-Process "http://127.0.0.1:5173/login"
}
