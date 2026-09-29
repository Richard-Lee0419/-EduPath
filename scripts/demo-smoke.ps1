param(
    [string]$TeacherUsername = "teacher",
    [string]$TeacherPassword = "123456",
    [string]$StudentUsername = "demo",
    [string]$StudentPassword = "123456",
    [string]$ApiBase = "http://127.0.0.1:8080/api"
)

$ErrorActionPreference = "Stop"

function Invoke-DemoJson {
    param(
        [Parameter(Mandatory = $true)][ValidateSet('GET', 'POST')][string]$Method,
        [Parameter(Mandatory = $true)][string]$Uri,
        [hashtable]$Headers = @{},
        $Body = $null
    )
    $parameters = @{
        Method = $Method
        Uri = $Uri
        Headers = $Headers
        TimeoutSec = 15
        ContentType = 'application/json; charset=utf-8'
    }
    if ($null -ne $Body) { $parameters.Body = ($Body | ConvertTo-Json -Depth 8 -Compress) }
    return Invoke-RestMethod @parameters
}

function Assert-DemoCondition {
    param([bool]$Condition, [string]$Message)
    if (-not $Condition) { throw $Message }
}

Write-Host "[1/7] 后端健康检查" -ForegroundColor Cyan
$health = Invoke-DemoJson -Method GET -Uri "$ApiBase/system/health"
Assert-DemoCondition ($health.data.status -eq 'ok') "Java 后端健康检查失败"

Write-Host "[2/7] 教师登录与一键演示数据准备" -ForegroundColor Cyan
$teacherLogin = Invoke-DemoJson -Method POST -Uri "$ApiBase/auth/login" -Body @{ username = $TeacherUsername; password = $TeacherPassword }
$teacherHeaders = @{ Authorization = "Bearer $($teacherLogin.data.token)" }
$prepared = Invoke-DemoJson -Method POST -Uri "$ApiBase/demo/prepare" -Headers $teacherHeaders -Body @{ scenario_key = 'software_cup_a3' }
Assert-DemoCondition ($prepared.data.summary.ready -eq $true) "一键演示数据未达到完整性门槛"

Write-Host "[3/7] 演示前聚合预检" -ForegroundColor Cyan
$preflight = Invoke-DemoJson -Method GET -Uri "$ApiBase/demo/preflight" -Headers $teacherHeaders
Assert-DemoCondition ($preflight.data.overall_status -ne 'blocked') "聚合预检存在阻断项"

Write-Host "[4/7] 学生画像与七天路径" -ForegroundColor Cyan
$studentLogin = Invoke-DemoJson -Method POST -Uri "$ApiBase/auth/login" -Body @{ username = $StudentUsername; password = $StudentPassword }
$studentHeaders = @{ Authorization = "Bearer $($studentLogin.data.token)" }
$profile = Invoke-DemoJson -Method GET -Uri "$ApiBase/profile/current" -Headers $studentHeaders
$path = Invoke-DemoJson -Method GET -Uri "$ApiBase/path/current" -Headers $studentHeaders
Assert-DemoCondition (-not [string]::IsNullOrWhiteSpace([string]$profile.data.profile.learning_goal)) "学生画像缺少学习目标"
Assert-DemoCondition (@($path.data.daily_plan).Count -eq 7) "学习路径不是预期的七天结构"

Write-Host "[5/7] 六类资源、RAG 辅导与评估" -ForegroundColor Cyan
$resources = Invoke-DemoJson -Method GET -Uri "$ApiBase/resources?size=50" -Headers $studentHeaders
$evaluation = Invoke-DemoJson -Method GET -Uri "$ApiBase/evaluation/report" -Headers $studentHeaders
$sessions = Invoke-DemoJson -Method GET -Uri "$ApiBase/tutor/sessions?page=1&size=20" -Headers $studentHeaders
$demoResources = @($resources.data.items | Where-Object { $_.resource_id -like 'demo_showcase_*' })
Assert-DemoCondition ($demoResources.Count -eq 6) "演示资源数量不是 6"
Assert-DemoCondition ([int]$evaluation.data.overall_score -eq 67) "演示评估结果异常"
Assert-DemoCondition (@($sessions.data.items | Where-Object { $_.session_id -eq 'demo_tutor_software_cup' }).Count -eq 1) "RAG 辅导演示会话缺失"

Write-Host "[6/7] 教师班级洞察" -ForegroundColor Cyan
$classId = [int]$prepared.data.summary.class_id
$insights = Invoke-DemoJson -Method GET -Uri "$ApiBase/classes/$classId/insights?window_days=30&course_id=1" -Headers $teacherHeaders
Assert-DemoCondition ($null -ne $insights.data) "教师班级洞察接口未返回数据"

Write-Host "[7/7] 演示链路结论" -ForegroundColor Cyan
Write-Host "PASS  数据=$($prepared.data.summary.resource_count)类资源/$($prepared.data.summary.learning_event_count)条行为，模式=$($preflight.data.recommended_mode)" -ForegroundColor Green
