param([switch]$NoBrowser)

$ErrorActionPreference = "Stop"
& (Join-Path $PSScriptRoot "demo-stop.ps1")
Start-Sleep -Seconds 1
& (Join-Path $PSScriptRoot "demo-start.ps1") -NoBrowser:$NoBrowser
