# 知径 EduPath · 华为 ICT / AgentArts Demo
# 用法：.\scripts\run-demo.ps1 -Mode fixture -Port 8765
param(
    [ValidateSet("fixture", "edupath_ai", "agentarts")]
    [string]$Mode = "fixture",
    [int]$Port = 8765,
    [string]$AiBase = "http://127.0.0.1:8000",
    [string]$AgentArtsEndpoint = "",
    [string]$AgentArtsKey = ""
)

$ErrorActionPreference = "Stop"
$root = Split-Path -Parent $PSScriptRoot
Set-Location $root

$env:EDUPATH_BRIDGE_MODE = $Mode
if ($Mode -eq "edupath_ai") { $env:EDUPATH_AI_BASE_URL = $AiBase }
if ($Mode -eq "agentarts") {
    if (-not $AgentArtsEndpoint) { throw "agentarts 模式需要 -AgentArtsEndpoint" }
    $env:AGENTARTS_ENDPOINT = $AgentArtsEndpoint
    if ($AgentArtsKey) { $env:AGENTARTS_API_KEY = $AgentArtsKey }
}

Write-Host "Mode: $Mode | Port: $Port"
Write-Host "Open: http://127.0.0.1:$Port/"
python -m bridge.gateway --host 127.0.0.1 --port $Port
