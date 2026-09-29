param([Parameter(Mandatory = $true)][string]$RepoRoot)

$ErrorActionPreference = "Stop"
$ToolsRoot = Join-Path (Split-Path $RepoRoot -Parent) ".tools"
$BundledJava = Join-Path $ToolsRoot "jdk-17.0.19+10"
$BundledMaven = Join-Path $ToolsRoot "apache-maven-3.9.16\bin\mvn.cmd"
$MavenSettings = Join-Path $ToolsRoot "maven-settings-aliyun.xml"
$MavenRepo = Join-Path $ToolsRoot "m2"

if (Test-Path -LiteralPath $BundledJava) { $env:JAVA_HOME = $BundledJava }
$maven = if (Test-Path -LiteralPath $BundledMaven) { $BundledMaven } else { (Get-Command mvn.cmd -ErrorAction Stop).Source }
$arguments = @()
if (Test-Path -LiteralPath $MavenSettings) { $arguments += @('-s', $MavenSettings) }
if (Test-Path -LiteralPath $MavenRepo) { $arguments += "-Dmaven.repo.local=$MavenRepo" }
$arguments += @('package', '-DskipTests')

$env:DEMO_MODE_ENABLED = "true"
$env:AI_SERVICE_BASE_URL = "http://127.0.0.1:8000"
Set-Location (Join-Path $RepoRoot "backend-api")
& $maven @arguments
if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }

$jar = Get-ChildItem -Path (Join-Path (Get-Location) "target") -Filter "backend-api-*.jar" |
    Where-Object { $_.Name -notlike "*.original" } |
    Sort-Object LastWriteTime -Descending |
    Select-Object -First 1
if (-not $jar) { throw "后端打包成功但未找到可执行 jar" }
$java = if ($env:JAVA_HOME) { Join-Path $env:JAVA_HOME "bin\java.exe" } else { (Get-Command java.exe -ErrorAction Stop).Source }
& $java -jar $jar.FullName --spring.profiles.active=test
exit $LASTEXITCODE
