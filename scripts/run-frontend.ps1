param([Parameter(Mandatory = $true)][string]$RepoRoot)

$ErrorActionPreference = "Stop"
Set-Location (Join-Path $RepoRoot "frontend")
if (-not (Test-Path -LiteralPath (Join-Path (Get-Location) "node_modules"))) {
    throw "frontend/node_modules 不存在，请先在 frontend 目录执行 npm install"
}
& npm.cmd run dev -- --host 127.0.0.1
exit $LASTEXITCODE
