# ============================================================
# 接口级冒烟测试（鉴权 / 越权 / 审计 / 渲染 / 导出 / 调度）
#
# 前置：后端已启动（默认 dev 配置，H2 内存库，端口 18088）
#   java -jar backend/target/report-bi-platform-1.0.0.jar --spring.profiles.active=dev --server.port=18088
#
# 运行：pwsh -File docs/smoke-test.ps1
# ============================================================
param(
    [string]$BaseUrl = 'http://127.0.0.1:18088'
)

$ErrorActionPreference = 'Stop'
$script:pass = 0
$script:fail = 0

function Invoke-Api {
    param(
        [string]$Method = 'GET',
        [string]$Path,
        [string]$Token,
        $Body
    )
    $headers = @{}
    if ($Token) { $headers['Authorization'] = "Bearer $Token" }
    $req = @{
        Method     = $Method
        Uri        = "$BaseUrl$Path"
        Headers    = $headers
        TimeoutSec = 20
        UseBasicParsing = $true
    }
    if ($null -ne $Body) {
        $req['ContentType'] = 'application/json'
        $req['Body'] = ($Body | ConvertTo-Json -Depth 12 -Compress)
    }
    try {
        $resp = Invoke-WebRequest @req
        return [pscustomobject]@{ Status = [int]$resp.StatusCode; Body = $resp.Content; Raw = $resp }
    } catch {
        $status = 0
        if ($_.Exception.Response) { $status = [int]$_.Exception.Response.StatusCode }
        $content = $_.ErrorDetails.Message
        if (-not $content -and $_.Exception.Response) {
            try { $content = (New-Object System.IO.StreamReader($_.Exception.Response.GetResponseStream())).ReadToEnd() } catch { }
        }
        return [pscustomobject]@{ Status = $status; Body = $content; Raw = $null }
    }
}

function Assert-Equal {
    param([string]$Name, $Expected, $Actual)
    if ("$Expected" -eq "$Actual") {
        $script:pass++
        Write-Host ("  [PASS] {0}  (期望 {1})" -f $Name, $Expected) -ForegroundColor Green
    } else {
        $script:fail++
        Write-Host ("  [FAIL] {0}  期望 {1}，实际 {2}" -f $Name, $Expected, $Actual) -ForegroundColor Red
    }
}

function Assert-True {
    param([string]$Name, [bool]$Condition, [string]$Detail = '')
    if ($Condition) {
        $script:pass++
        Write-Host ("  [PASS] {0}" -f $Name) -ForegroundColor Green
    } else {
        $script:fail++
        Write-Host ("  [FAIL] {0} {1}" -f $Name, $Detail) -ForegroundColor Red
    }
}

function Get-Data($resp) {
    try { return ($resp.Body | ConvertFrom-Json).data } catch { return $null }
}

Write-Host "`n=== 1. 未登录访问（应全部 401） ===" -ForegroundColor Cyan
Assert-Equal "GET /api/admin/users 未登录" 401 (Invoke-Api -Path '/api/admin/users').Status
Assert-Equal "GET /api/datasets 未登录" 401 (Invoke-Api -Path '/api/datasets').Status
Assert-Equal "POST /api/sql/execute 未登录" 401 (Invoke-Api -Method POST -Path '/api/sql/execute' -Body @{ sql = 'select 1' }).Status
Assert-Equal "GET /api/admin/users 伪造令牌" 401 (Invoke-Api -Path '/api/admin/users' -Token 'fake.token.value').Status

Write-Host "`n=== 2. 健康检查（公开） ===" -ForegroundColor Cyan
$h = Invoke-Api -Path '/api/health'
Assert-Equal "GET /api/health 公开可访问" 200 $h.Status

Write-Host "`n=== 3. 登录 ===" -ForegroundColor Cyan
$login = Invoke-Api -Method POST -Path '/api/auth/login' -Body @{ username = 'admin'; password = '123456' }
Assert-Equal "admin 登录" 200 $login.Status
$adminToken = (Get-Data $login).token
Assert-True "admin 令牌非空" ([bool]$adminToken)

$bad = Invoke-Api -Method POST -Path '/api/auth/login' -Body @{ username = 'admin'; password = 'wrong-password' }
Assert-Equal "错误口令拒绝" 401 $bad.Status

$analystLogin = Invoke-Api -Method POST -Path '/api/auth/login' -Body @{ username = 'analyst'; password = '123456' }
$analystToken = (Get-Data $analystLogin).token
$viewerLogin = Invoke-Api -Method POST -Path '/api/auth/login' -Body @{ username = 'viewer'; password = '123456' }
$viewerToken = (Get-Data $viewerLogin).token

Write-Host "`n=== 4. 登录态与角色 ===" -ForegroundColor Cyan
$me = Invoke-Api -Path '/api/auth/me' -Token $adminToken
Assert-Equal "GET /api/auth/me" 200 $me.Status
$meData = Get-Data $me
Assert-True "admin 角色包含 ADMIN" (($meData.roles -join ',') -match 'ADMIN') ($meData.roles -join ',')

Write-Host "`n=== 5. 越权（RBAC 生效） ===" -ForegroundColor Cyan
Assert-Equal "admin 访问用户列表" 200 (Invoke-Api -Path '/api/admin/users' -Token $adminToken).Status
Assert-Equal "analyst 访问用户列表（应拒绝）" 403 (Invoke-Api -Path '/api/admin/users' -Token $analystToken).Status
Assert-Equal "viewer 访问用户列表（应拒绝）" 403 (Invoke-Api -Path '/api/admin/users' -Token $viewerToken).Status
Assert-Equal "analyst 重置他人密码（应拒绝）" 403 (Invoke-Api -Method POST -Path '/api/admin/users/1/resetpwd' -Token $analystToken).Status
Assert-Equal "viewer 读报表列表（应允许）" 200 (Invoke-Api -Path '/api/reports' -Token $viewerToken).Status
Assert-Equal "viewer 新建数据集（应拒绝）" 403 (Invoke-Api -Method POST -Path '/api/datasets' -Token $viewerToken -Body @{ name = 'x'; sqlText = 'select 1' }).Status
Assert-Equal "viewer 测试数据源连接（应拒绝，防 SSRF）" 403 (Invoke-Api -Method POST -Path '/api/datasources/test' -Token $viewerToken -Body @{ jdbcUrl = 'jdbc:mysql://127.0.0.1:3306/x' }).Status

Write-Host "`n=== 6. 任意 SQL 执行已收紧 ===" -ForegroundColor Cyan
Assert-Equal "admin 只读查询" 200 (Invoke-Api -Method POST -Path '/api/sql/execute' -Token $adminToken -Body @{ sql = 'select 1 as v' }).Status
Assert-Equal "拒绝 DELETE" 400 (Invoke-Api -Method POST -Path '/api/sql/execute' -Token $adminToken -Body @{ sql = 'delete from rbi_user' }).Status
Assert-Equal "拒绝多语句" 400 (Invoke-Api -Method POST -Path '/api/sql/execute' -Token $adminToken -Body @{ sql = 'select 1; drop table rbi_user' }).Status
Assert-Equal "analyst 调用 SQL 执行（应拒绝）" 403 (Invoke-Api -Method POST -Path '/api/sql/execute' -Token $analystToken -Body @{ sql = 'select 1' }).Status

Write-Host "`n=== 7. 报表渲染 ===" -ForegroundColor Cyan
$render = Invoke-Api -Method POST -Path '/api/reports/1/render' -Token $viewerToken -Body @{}
Assert-Equal "渲染内置报表示例" 200 $render.Status
$rd = Get-Data $render
Assert-True "渲染结果有行列" ([int]$rd.rows -gt 0 -and [int]$rd.cols -gt 0) ("rows=$($rd.rows) cols=$($rd.cols)")

Write-Host "`n=== 8. 导出 ===" -ForegroundColor Cyan
$export = Invoke-Api -Path '/api/reports/1/export' -Token $viewerToken
Assert-Equal "报表导出 Excel" 200 $export.Status
if ($export.Raw) {
    $bytes = $export.Raw.RawContentStream.ToArray()
    $isZip = ($bytes.Length -gt 2 -and $bytes[0] -eq 0x50 -and $bytes[1] -eq 0x4B)
    Assert-True "导出文件是 xlsx(zip) 格式" $isZip ("前两字节: {0:X2} {1:X2}" -f $bytes[0], $bytes[1])
    Write-Host ("         文件大小: {0:N0} 字节" -f $bytes.Length)
}
$csv = Invoke-Api -Path '/api/datasets/1/export.csv' -Token $viewerToken
Assert-Equal "数据集导出 CSV" 200 $csv.Status

Write-Host "`n=== 9. 审计日志 ===" -ForegroundColor Cyan
$logs = Invoke-Api -Path '/api/admin/logs' -Token $adminToken
Assert-Equal "查询操作日志" 200 $logs.Status
$logRows = @()
$logData = Get-Data $logs
if ($null -ne $logData) { $logRows = @($logData) }
Assert-True "写操作已落审计日志" ($logRows.Count -gt 0) ("条数: $($logRows.Count)")
Assert-True "日志含操作人" ([bool]($logRows[0].username))
Assert-True "日志含结果状态" ([bool]($logRows[0].status))

Write-Host "`n=== 10. 调度执行 ===" -ForegroundColor Cyan
$manual = Invoke-Api -Method POST -Path '/api/admin/schedules/1/run' -Token $adminToken
$manualStatus = $manual.Status
# 2026 起 email 任务已接 JavaMailSender：配置了 SMTP 返回 200，未配置返回明确失败。
# 两种结果都算通过——关键是"有明确结果"，不允许静默假成功（200 但没发信由 smoke-test-v12 覆盖）。
Assert-True "手动执行调度返回明确结果（200 或 4xx/5xx）" (@(200, 400, 403, 409, 500) -contains $manualStatus) ("状态: $manualStatus")

Write-Host "`n=== 11. 登录失败锁定 ===" -ForegroundColor Cyan
# 用不存在的账号做锁定测试，避免把演示账号锁住影响其它用例
$locked = $false
for ($i = 0; $i -lt 6; $i++) {
    $r = Invoke-Api -Method POST -Path '/api/auth/login' -Body @{ username = 'locktest-probe'; password = 'bad' }
    if ($r.Status -eq 429) { $locked = $true; break }
}
Assert-True "连续失败触发限流锁定(429)" $locked

Write-Host "`n================================" -ForegroundColor Cyan
Write-Host ("通过 {0} 项，失败 {1} 项" -f $script:pass, $script:fail) -ForegroundColor $(if ($script:fail -eq 0) { 'Green' } else { 'Red' })
exit $(if ($script:fail -eq 0) { 0 } else { 1 })
