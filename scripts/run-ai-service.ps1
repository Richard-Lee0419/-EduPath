param(
    [Parameter(Mandatory = $true)][string]$RepoRoot,
    [string]$EnvFile = ""
)

$ErrorActionPreference = "Stop"
if ($EnvFile -and (Test-Path -LiteralPath $EnvFile)) {
    foreach ($line in Get-Content -LiteralPath $EnvFile -Encoding UTF8) {
        if ($line -match '^\s*#' -or $line -notmatch '=') { continue }
        $name, $value = $line -split '=', 2
        $name = $name.Trim()
        if (-not $name) { continue }
        $value = $value.Trim()
        if (($value.StartsWith('"') -and $value.EndsWith('"')) -or ($value.StartsWith("'") -and $value.EndsWith("'"))) {
            $value = $value.Substring(1, $value.Length - 2)
        }
        [Environment]::SetEnvironmentVariable($name, $value, 'Process')
    }
}

$env:PYTHONUTF8 = "1"
Set-Location (Join-Path $RepoRoot "ai-agent-service")
$venvPython = Join-Path (Get-Location) ".venv\Scripts\python.exe"
$python = if (Test-Path -LiteralPath $venvPython) { $venvPython } else { (Get-Command python.exe -ErrorAction Stop).Source }
& $python -m uvicorn app.main:app --host 127.0.0.1 --port 8000
exit $LASTEXITCODE
