# ============================================================
# v1.2 新增能力冒烟测试：账号自助 / 行级数据权限 / 报表可见性 / 邮件推送 / 调度租约
#
# 前置：
#   1) 后端已启动（dev 配置，端口 18088，且配置了邮件）：
#      $env:RBI_MAIL_HOST='127.0.0.1'; $env:RBI_MAIL_PORT='1025'
#      java -jar backend/target/report-bi-platform-1.0.0.jar --spring.profiles.active=dev --server.port=18088
#   2) 若验证邮件：先起本地 SMTP sink
#      node docs/dev-smtp-sink.js 1025 .tmp/mail
#
# 运行：powershell -NoProfile -ExecutionPolicy Bypass -File docs/smoke-test-v12.ps1
# ============================================================
param(
    [string]$BaseUrl = 'http://127.0.0.1:18088',
    [string]$MailDir = 'D:\bi\.tmp\mail'
)

$ErrorActionPreference = 'Stop'
$script:pass = 0
$script:fail = 0
$script:cleanup = @()

function Fix-Encoding([string]$s) {
    if (-not $s) { return $s }
    try {
        $bytes = [System.Text.Encoding]::GetEncoding(28591).GetBytes($s)
        return [System.Text.Encoding]::UTF8.GetString($bytes)
    } catch { return $s }
}

function Invoke-Api {
    param([string]$Method = 'GET', [string]$Path, [string]$Token, $Body)
    $headers = @{}
    if ($Token) { $headers['Authorization'] = "Bearer $Token" }
    $req = @{ Method = $Method; Uri = "$BaseUrl$Path"; Headers = $headers; TimeoutSec = 25; UseBasicParsing = $true }
    if ($null -ne $Body) {
        $req['ContentType'] = 'application/json; charset=utf-8'
        # Windows PowerShell 5.1 默认按 Latin-1 发送字符串体，中文会变成 '??'，必须显式转成 UTF-8 字节
        $req['Body'] = [System.Text.Encoding]::UTF8.GetBytes(($Body | ConvertTo-Json -Depth 12 -Compress))
    }
    try {
        $resp = Invoke-WebRequest @req
        $ct = [string]$resp.Headers['Content-Type']
        $body = [string]$resp.Content
        # Windows PowerShell 5.1 对没有 charset 的 application/json 按 Latin-1 解码，需要还原成 UTF-8
        if ($ct -and $ct -notmatch 'charset') { $body = Fix-Encoding $body }
        return [pscustomobject]@{ Status = [int]$resp.StatusCode; Body = $body; Raw = $resp }
    } catch {
        $status = 0
        if ($_.Exception.Response) { $status = [int]$_.Exception.Response.StatusCode }
        $content = $_.ErrorDetails.Message
        if (-not $content -and $_.Exception.Response) {
            try { $content = (New-Object System.IO.StreamReader($_.Exception.Response.GetResponseStream())).ReadToEnd() } catch { }
        }
        return [pscustomobject]@{ Status = $status; Body = (Fix-Encoding ([string]$content)); Raw = $null }
    }
}

function Assert-Equal {
    param([string]$Name, $Expected, $Actual)
    if ("$Expected" -eq "$Actual") { $script:pass++; Write-Host ("  [PASS] {0}  (期望 {1})" -f $Name, $Expected) -ForegroundColor Green }
    else { $script:fail++; Write-Host ("  [FAIL] {0}  期望 {1}，实际 {2}" -f $Name, $Expected, $Actual) -ForegroundColor Red }
}

function Assert-True {
    param([string]$Name, [bool]$Condition, [string]$Detail = '')
    if ($Condition) { $script:pass++; Write-Host ("  [PASS] {0}" -f $Name) -ForegroundColor Green }
    else { $script:fail++; Write-Host ("  [FAIL] {0} {1}" -f $Name, $Detail) -ForegroundColor Red }
}

function Get-Data($resp) {
    try { return ($resp.Body | ConvertFrom-Json).data } catch { return $null }
}

function Login([string]$user, [string]$pwd) {
    $r = Invoke-Api -Method POST -Path '/api/auth/login' -Body @{ username = $user; password = $pwd }
    if ($r.Status -ne 200) { return $null }
    return (Get-Data $r).token
}

Write-Host "`n=== 0. 准备账号 ===" -ForegroundColor Cyan
$admin = Login 'admin' '123456'
$analyst = Login 'analyst' '123456'
$viewer = Login 'viewer' '123456'
Assert-True "三个内置账号均可登录" ([bool]$admin -and [bool]$analyst -and [bool]$viewer)

Write-Host "`n=== 1. 账号自助：新建用户/临时密码/强制改密 ===" -ForegroundColor Cyan
$uname = 'smoke_' + (Get-Random -Maximum 9999)
$created = Invoke-Api -Method POST -Path '/api/admin/users' -Token $admin -Body @{ username = $uname; realName = '冒烟测试'; status = 1 }
Assert-Equal "管理员新建用户" 200 $created.Status
$createdData = Get-Data $created
$temp = [string]$createdData.initialPassword
Assert-True "返回初始密码（随机）" ($temp.Length -ge 8) ([string]$createdData | ConvertTo-Json -Compress)
$users = Get-Data (Invoke-Api -Path '/api/admin/users' -Token $admin)
$newUser = @($users) | Where-Object { $_.username -eq $uname } | Select-Object -First 1
Assert-True "新用户已入库" ([bool]$newUser)

$tok = Login $uname $temp
Assert-True "临时密码可登录" ([bool]$tok)
$me = Get-Data (Invoke-Api -Path '/api/auth/me' -Token $tok)
Assert-True "标记为必须改密" ([bool]$me.mustChangePwd)

$wrong = Invoke-Api -Method POST -Path '/api/auth/password' -Token $tok -Body @{ oldPassword = 'wrong'; newPassword = 'NewPass123' }
Assert-Equal "原密码错误被拒绝" 400 $wrong.Status
$weak = Invoke-Api -Method POST -Path '/api/auth/password' -Token $tok -Body @{ oldPassword = $temp; newPassword = '123' }
Assert-Equal "弱密码被拒绝" 400 $weak.Status
$changed = Invoke-Api -Method POST -Path '/api/auth/password' -Token $tok -Body @{ oldPassword = $temp; newPassword = 'NewPass123' }
Assert-Equal "修改密码成功" 200 $changed.Status
$tok2 = Login $uname 'NewPass123'
Assert-True "新密码可登录" ([bool]$tok2)
$me2 = Get-Data (Invoke-Api -Path '/api/auth/me' -Token $tok2)
Assert-True "改密后强制标记已清除" (-not [bool]$me2.mustChangePwd)
$old = Login $uname $temp
Assert-True "旧密码已失效" (-not [bool]$old)

$prof = Invoke-Api -Method PUT -Path '/api/auth/profile' -Token $tok2 -Body @{ realName = '改名后' }
Assert-Equal "修改个人资料" 200 $prof.Status
$me3 = Get-Data (Invoke-Api -Path '/api/auth/me' -Token $tok2)
Assert-Equal "资料已更新" '改名后' $me3.realName

Write-Host "`n=== 2. 行级数据权限（dataset 1, viewer 仅见华北） ===" -ForegroundColor Cyan
$before = Get-Data (Invoke-Api -Method POST -Path '/api/datasets/1/query' -Token $viewer -Body @{})
$beforeCount = @($before).Count
$scope = Invoke-Api -Method POST -Path '/api/admin/data-scopes' -Token $admin -Body @{ roleId = 3; datasetId = 1; columnName = 'region'; scopeValue = '华北' }
Assert-Equal "配置 viewer 的数据范围" 200 $scope.Status
$scopeId = (Get-Data $scope).id

$after = @(Get-Data (Invoke-Api -Method POST -Path '/api/datasets/1/query' -Token $viewer -Body @{}))
Assert-True "viewer 行数被收窄" ($after.Count -lt $beforeCount) ("before=$beforeCount after=$($after.Count)")
$regions = ($after | ForEach-Object { $_.region }) | Sort-Object -Unique
Assert-Equal "viewer 只看到华北" '华北' ($regions -join ',')

$adminRows = @(Get-Data (Invoke-Api -Method POST -Path '/api/datasets/1/query' -Token $admin -Body @{}))
Assert-True "ADMIN 不受行级限制" ($adminRows.Count -eq $beforeCount) ("admin=$($adminRows.Count) before=$beforeCount")

$render = Invoke-Api -Method POST -Path '/api/reports/1/render' -Token $viewer -Body @{}
Assert-Equal "受限用户仍可渲染报表" 200 $render.Status

# fail-closed：数据集缺少 ${row_filter} 时拒绝执行
$dsBody = @{ name = 'smoke-no-filter'; datasourceId = 1; type = 'sql'; sqlText = 'SELECT * FROM demo_sales'; cacheSeconds = 0 }
$ds = Invoke-Api -Method POST -Path '/api/datasets' -Token $admin -Body $dsBody
$dsId = (Get-Data $ds).id
$scope2 = Invoke-Api -Method POST -Path '/api/admin/data-scopes' -Token $admin -Body @{ roleId = 3; datasetId = $dsId; columnName = 'region'; scopeValue = '华东' }
$scope2Id = (Get-Data $scope2).id
$denied = Invoke-Api -Method POST -Path "/api/datasets/$dsId/query" -Token $viewer -Body @{}
Assert-Equal "缺少 row_filter 占位符时 fail-closed(403)" 403 $denied.Status

Write-Host "`n=== 3. 报表可见性（rbi_permission 生效） ===" -ForegroundColor Cyan
# 种子数据里 viewer 已对报表1 有 view 权限，先验证"被授权就能看"
$seedView = Invoke-Api -Method POST -Path '/api/reports/1/render' -Token $viewer -Body @{}
Assert-Equal "种子授权：viewer 可渲染报表1" 200 $seedView.Status

# 再新建一张报表，只授权给 analyst，验证 viewer 被拒
$layout = '{"cols":4,"rows":6,"cells":[{"ref":"A1","value":"冒烟报表","type":"text","style":{"bold":true}},{"ref":"A2","type":"dataset","field":"region","expand":"v"},{"ref":"B2","type":"dataset","field":"amount","expand":"v"}]}'
$newReport = Invoke-Api -Method POST -Path '/api/reports' -Token $admin -Body @{ report = @{ name = 'smoke-report'; directoryId = $null; datasetId = 1; layoutJson = $layout; paramsJson = '[]' }; remark = '冒烟' }
Assert-Equal "创建测试报表" 200 $newReport.Status
$reportId = (Get-Data $newReport).id

$perm = Invoke-Api -Method POST -Path '/api/admin/permissions' -Token $admin -Body @{ roleId = 2; resourceType = 'report'; resourceId = $reportId; perm = 'view' }
Assert-Equal "仅授权 analyst 查看该报表" 200 $perm.Status
$permId = (Get-Data $perm).id

$viewerList = @(Get-Data (Invoke-Api -Path '/api/reports' -Token $viewer))
Assert-True "viewer 列表不含未授权报表" (-not ($viewerList | Where-Object { $_.id -eq $reportId }))
$viewerRender = Invoke-Api -Method POST -Path "/api/reports/$reportId/render" -Token $viewer -Body @{}
Assert-Equal "viewer 渲染未授权报表被拒绝(403)" 403 $viewerRender.Status
$analystRender = Invoke-Api -Method POST -Path "/api/reports/$reportId/render" -Token $analyst -Body @{}
Assert-Equal "analyst 渲染已授权报表允许" 200 $analystRender.Status
$adminRender = Invoke-Api -Method POST -Path "/api/reports/$reportId/render" -Token $admin -Body @{}
Assert-Equal "ADMIN 不受资源权限限制" 200 $adminRender.Status
$adminList = @(Get-Data (Invoke-Api -Path '/api/reports' -Token $admin))
Assert-True "ADMIN 列表包含该报表" ([bool]($adminList | Where-Object { $_.id -eq $reportId }))

Write-Host "`n=== 4. 邮件推送（本地 SMTP sink） ===" -ForegroundColor Cyan
$mailBefore = 0
if (Test-Path $MailDir) { $mailBefore = @(Get-ChildItem $MailDir -Filter *.eml).Count }
$run = Invoke-Api -Method POST -Path '/api/admin/schedules/1/run' -Token $admin
Assert-Equal "email 任务执行成功" 200 $run.Status
Start-Sleep -Seconds 2
$mailAfter = 0
if (Test-Path $MailDir) { $mailAfter = @(Get-ChildItem $MailDir -Filter *.eml).Count }
Assert-True "SMTP sink 收到邮件" ($mailAfter -gt $mailBefore) ("before=$mailBefore after=$mailAfter")
if ($mailAfter -gt $mailBefore) {
    $latest = Get-ChildItem $MailDir -Filter *.eml | Sort-Object LastWriteTime | Select-Object -Last 1
    $raw = Get-Content $latest.FullName -Raw
    Assert-True "邮件含报表附件(xlsx)" ($raw -match 'report|\.xlsx|application/vnd.openxmlformats') $latest.Name
    Write-Host ("         邮件文件: {0} ({1:N0} 字节)" -f $latest.Name, (Get-Item $latest.FullName).Length)
}

Write-Host "`n=== 5. 调度租约（多节点防重字段） ===" -ForegroundColor Cyan
$sched = @(Get-Data (Invoke-Api -Path '/api/admin/schedules' -Token $admin)) | Where-Object { $_.id -eq 1 }
Assert-True "任务执行后租约已释放" ($null -eq $sched.lockedUntil) ("lockedUntil=$($sched.lockedUntil)")
Assert-True "任务记录了上次执行时间" ([bool]$sched.lastRunTime)

Write-Host "`n=== 6. PDF 导出（中文） ===" -ForegroundColor Cyan
$pdf = Invoke-Api -Path '/api/reports/1/export.pdf' -Token $admin
Assert-Equal "报表导出 PDF" 200 $pdf.Status
if ($pdf.Raw) {
    $bytes = $pdf.Raw.RawContentStream.ToArray()
    $head = [System.Text.Encoding]::ASCII.GetString($bytes[0..4])
    Assert-Equal "PDF 文件头" '%PDF-' $head
    Assert-True "PDF 内容非空" ($bytes.Length -gt 1000) ("大小: {0} 字节" -f $bytes.Length)
    $ascii = [System.Text.Encoding]::ASCII.GetString($bytes)
    Assert-True "使用中文字体 STSong-Light" ($ascii -match 'STSong')
}

Write-Host "`n=== 7. 清理 ===" -ForegroundColor Cyan
Invoke-Api -Method DELETE -Path "/api/admin/data-scopes/$scopeId" -Token $admin | Out-Null
Invoke-Api -Method DELETE -Path "/api/admin/data-scopes/$scope2Id" -Token $admin | Out-Null
Invoke-Api -Method DELETE -Path "/api/datasets/$dsId" -Token $admin | Out-Null
Invoke-Api -Method DELETE -Path "/api/admin/permissions/$permId" -Token $admin | Out-Null
Invoke-Api -Method DELETE -Path "/api/reports/$reportId" -Token $admin | Out-Null
Invoke-Api -Method DELETE -Path "/api/admin/users/$($newUser.id)" -Token $admin | Out-Null
$restored = @(Get-Data (Invoke-Api -Method POST -Path '/api/datasets/1/query' -Token $viewer -Body @{}))
Assert-True "清理后 viewer 恢复全量" ($restored.Count -eq $beforeCount) ("count=$($restored.Count)")

Write-Host "`n================================" -ForegroundColor Cyan
Write-Host ("通过 {0} 项，失败 {1} 项" -f $script:pass, $script:fail) -ForegroundColor $(if ($script:fail -eq 0) { 'Green' } else { 'Red' })
exit $(if ($script:fail -eq 0) { 0 } else { 1 })
