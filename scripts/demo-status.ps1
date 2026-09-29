$ErrorActionPreference = "Stop"
. (Join-Path $PSScriptRoot "demo-common.ps1")

$services = @(
    [pscustomobject]@{ Service = "Vue 前端"; Port = 5173; Ready = Test-DemoEndpoint "http://127.0.0.1:5173"; Required = $true },
    [pscustomobject]@{ Service = "Java 后端"; Port = 8080; Ready = Test-DemoEndpoint "http://127.0.0.1:8080/api/system/health"; Required = $true },
    [pscustomobject]@{ Service = "Python AI"; Port = 8000; Ready = Test-DemoEndpoint "http://127.0.0.1:8000/health"; Required = $false }
)
$services | Select-Object Service, Port, @{Name='Status';Expression={ if ($_.Ready) { 'READY' } elseif ($_.Required) { 'BLOCKED' } else { 'DEGRADED' } }} | Format-Table -AutoSize

if (($services | Where-Object { $_.Required -and -not $_.Ready }).Count -gt 0) { exit 1 }
exit 0
