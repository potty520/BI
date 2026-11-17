# ============================================================
# v1.3 新增能力冒烟测试：
#   ① 列级权限/脱敏  ② 找回密码  ③ 分页与页眉页脚  ④ 调度重试与告警  ⑤ API 数据集
#
# 前置（三个进程）：
#   node docs/dev-smtp-sink.js  1025 .tmp/mail
#   node docs/dev-http-fixture.js 18099
#   $env:RBI_MAIL_HOST='127.0.0.1'; $env:RBI_MAIL_PORT='1025'
#   $env:RBI_API_DATASET_HOSTS='127.0.0.1'
#   java -jar backend/target/report-bi-platform-1.0.0.jar --spring.profiles.active=dev --server.port=18088
#
# 运行：powershell -NoProfile -ExecutionPolicy Bypass -File docs/smoke-test-v13.ps1
# ============================================================
param(
    [string]$BaseUrl = 'http://127.0.0.1:18088',
    [string]$MailDir = 'D:\bi\.tmp\mail',
    [string]$FixtureUrl = 'http://127.0.0.1:18099'
)

$ErrorActionPreference = 'Stop'
$script:pass = 0
$script:fail = 0

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
    $req = @{ Method = $Method; Uri = "$BaseUrl$Path"; Headers = $headers; TimeoutSec = 30; UseBasicParsing = $true }
    if ($null -ne $Body) {
        $req['ContentType'] = 'application/json; charset=utf-8'
        $req['Body'] = [System.Text.Encoding]::UTF8.GetBytes(($Body | ConvertTo-Json -Depth 15 -Compress))
    }
    try {
        $resp = Invoke-WebRequest @req
        $ct = [string]$resp.Headers['Content-Type']
        $body = [string]$resp.Content
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

function Assert-True {
    param([string]$Name, [bool]$Condition, [string]$Detail = '')
    if ($Condition) { $script:pass++; Write-Host ("  [PASS] {0}" -f $Name) -ForegroundColor Green }
    else { $script:fail++; Write-Host ("  [FAIL] {0} {1}" -f $Name, $Detail) -ForegroundColor Red }
}
function Assert-Equal {
    param([string]$Name, $Expected, $Actual)
    if ("$Expected" -eq "$Actual") { $script:pass++; Write-Host ("  [PASS] {0}  (期望 {1})" -f $Name, $Expected) -ForegroundColor Green }
    else { $script:fail++; Write-Host ("  [FAIL] {0}  期望 {1}，实际 {2}" -f $Name, $Expected, $Actual) -ForegroundColor Red }
}
function Get-Data($resp) { try { return ($resp.Body | ConvertFrom-Json).data } catch { return $null } }
function Login([string]$u, [string]$p) {
    $r = Invoke-Api -Method POST -Path '/api/auth/login' -Body @{ username = $u; password = $p }
    if ($r.Status -ne 200) { return $null }
    return (Get-Data $r).token
}
function Latest-Mail($dir) {
    if (-not (Test-Path $dir)) { return $null }
    return Get-ChildItem $dir -Filter *.eml | Sort-Object LastWriteTime | Select-Object -Last 1
}
function Mail-Count($dir) {
    if (-not (Test-Path $dir)) { return 0 }
    return @(Get-ChildItem $dir -Filter *.eml).Count
}

Write-Host "`n=== 0. 准备 ===" -ForegroundColor Cyan
$admin = Login 'admin' '123456'
$viewer = Login 'viewer' '123456'
$analyst = Login 'analyst' '123456'
Assert-True "内置账号可登录" ([bool]$admin -and [bool]$viewer -and [bool]$analyst)

Write-Host "`n=== 1. 列级权限与脱敏（dataset 1 / viewer） ===" -ForegroundColor Cyan
$before = @(Get-Data (Invoke-Api -Method POST -Path '/api/datasets/1/query' -Token $viewer -Body @{}))
$sampleBefore = $before[0]
Assert-True "配置前含 province 列" ($null -ne $sampleBefore.province)

$p1 = Invoke-Api -Method POST -Path '/api/admin/column-policies' -Token $admin -Body @{ roleId = 3; datasetId = 1; columnName = 'province'; policy = 'hidden' }
$p2 = Invoke-Api -Method POST -Path '/api/admin/column-policies' -Token $admin -Body @{ roleId = 3; datasetId = 1; columnName = 'salesperson'; policy = 'mask_name' }
$p3 = Invoke-Api -Method POST -Path '/api/admin/column-policies' -Token $admin -Body @{ roleId = 3; datasetId = 1; columnName = 'product'; policy = 'mask_all' }
Assert-Equal "配置 hidden 策略" 200 $p1.Status
$id1 = (Get-Data $p1).id; $id2 = (Get-Data $p2).id; $id3 = (Get-Data $p3).id

$after = @(Get-Data (Invoke-Api -Method POST -Path '/api/datasets/1/query' -Token $viewer -Body @{}))
$sample = $after[0]
Assert-True "hidden 列已移除" ($null -eq $sample.province) ("province=" + $sample.province)
Assert-True "mask_name 脱敏为 张*" ($sample.salesperson -match '^\S\*$') ("salesperson=" + $sample.salesperson)
Assert-True "mask_all 全掩码" ($sample.product -eq '******') ("product=" + $sample.product)

$adminRows = @(Get-Data (Invoke-Api -Method POST -Path '/api/datasets/1/query' -Token $admin -Body @{}))
Assert-True "ADMIN 不受列权限限制" ($null -ne $adminRows[0].province)

$anaHidden = Invoke-Api -Method POST -Path '/api/analysis/run' -Token $viewer -Body @{ datasetId = 1; config = @{ rows = @('province'); values = @(@{ field = 'amount'; agg = 'SUM' }); engine = 'sql' } }
Assert-Equal "用隐藏列做维度被拒绝(403)" 403 $anaHidden.Status

$csv = Invoke-Api -Path '/api/datasets/1/export.csv' -Token $viewer
Assert-Equal "受限用户导出 CSV" 200 $csv.Status
if ($csv.Raw) {
    $txt = [System.Text.Encoding]::UTF8.GetString($csv.Raw.RawContentStream.ToArray())
    Assert-True "导出内容不含隐藏列" (-not ($txt -match 'province')) ("首行: " + ($txt.Split("`r`n")[0]))
}

Write-Host "`n=== 2. 找回密码（邮件令牌） ===" -ForegroundColor Cyan
$uname = 'reset_' + (Get-Random -Maximum 9999)
$created = Get-Data (Invoke-Api -Method POST -Path '/api/admin/users' -Token $admin -Body @{ username = $uname; realName = '找回测试'; status = 1 })
$uid = $created.id
$tempPwd = $created.initialPassword
$mailTo = "$uname@example.com"
$upd = Invoke-Api -Method POST -Path '/api/admin/users' -Token $admin -Body @{ id = $uid; username = $uname; realName = '找回测试'; status = 1; email = $mailTo }
Assert-Equal "为用户配置邮箱" 200 $upd.Status

$mailBefore = Mail-Count $MailDir
$forgot = Invoke-Api -Method POST -Path '/api/auth/forgot' -Body @{ username = $uname }
Assert-Equal "申请找回密码" 200 $forgot.Status
Start-Sleep -Seconds 2
Assert-True "收到重置邮件" ((Mail-Count $MailDir) -gt $mailBefore)
$mail = Latest-Mail $MailDir
$token = $null
if ($mail) {
    # 用 Python email 模块解析（自动处理 quoted-printable / base64 / 多段）
    $extract = @'
import sys, re
from email import policy
from email.parser import BytesParser
with open(sys.argv[1], 'rb') as f:
    msg = BytesParser(policy=policy.default).parse(f)
body = ''
if msg.is_multipart():
    for part in msg.walk():
        if part.get_content_type() == 'text/plain':
            body += part.get_content()
else:
    body = msg.get_content()
m = re.search(r'token=([0-9a-f]{32})', body)
print(m.group(1) if m else '')
'@
    $pyEx = 'D:\bi\.tmp\extract-token.py'
    [System.IO.File]::WriteAllText($pyEx, $extract, (New-Object System.Text.UTF8Encoding($false)))
    $token = (python $pyEx $mail.FullName 2>&1 | Select-Object -First 1)
    if ($token) { $token = $token.Trim() }
}
Assert-True "邮件中包含重置令牌" ([bool]$token) ("token=" + $token)

$short = Invoke-Api -Method POST -Path '/api/auth/reset' -Body @{ token = $token; newPassword = '123' }
Assert-Equal "弱密码被拒绝" 400 $short.Status
$reset = Invoke-Api -Method POST -Path '/api/auth/reset' -Body @{ token = $token; newPassword = 'ResetPass123' }
Assert-Equal "凭令牌重置成功" 200 $reset.Status
Assert-True "新密码可登录" ([bool](Login $uname 'ResetPass123'))
$reuse = Invoke-Api -Method POST -Path '/api/auth/reset' -Body @{ token = $token; newPassword = 'Another123' }
Assert-Equal "令牌不可重复使用" 400 $reuse.Status
$badToken = Invoke-Api -Method POST -Path '/api/auth/reset' -Body @{ token = 'deadbeef'; newPassword = 'Another123' }
Assert-Equal "无效令牌被拒绝" 400 $badToken.Status

Write-Host "`n=== 3. 报表分页与页眉页脚 ===" -ForegroundColor Cyan
$layout = '{"cols":3,"rows":40,"headerRows":2,"pageSize":5,"footerText":"第 {page}/{pages} 页","pageSizeName":"A4",' +
          '"cells":[' +
          '{"ref":"A1","value":"分页测试报表","type":"text","style":{"bold":true,"colspan":3}},' +
          '{"ref":"A2","value":"大区","type":"text","style":{"bold":true}},' +
          '{"ref":"B2","value":"省份","type":"text","style":{"bold":true}},' +
          '{"ref":"C2","value":"销售额","type":"text","style":{"bold":true}},' +
          '{"ref":"A3","type":"dataset","field":"region","expand":"v","parent":""},' +
          '{"ref":"B3","type":"dataset","field":"province","expand":"v","parent":"A3"},' +
          '{"ref":"C3","type":"formula","value":"SUM(amount)","expand":"none"}]}'
$rep = Invoke-Api -Method POST -Path '/api/reports' -Token $admin -Body @{ report = @{ name = '分页测试报表'; datasetId = 1; layoutJson = $layout; paramsJson = '[]' }; remark = '分页验证' }
Assert-Equal "创建分页测试报表" 200 $rep.Status
$repId = (Get-Data $rep).id

$pdf = Invoke-Api -Path "/api/reports/$repId/export.pdf" -Token $admin
Assert-Equal "导出分页 PDF" 200 $pdf.Status
$pdfFile = 'D:\bi\.tmp\paging-test.pdf'
if ($pdf.Raw) { [System.IO.File]::WriteAllBytes($pdfFile, $pdf.Raw.RawContentStream.ToArray()) }

$xl = Invoke-Api -Path "/api/reports/$repId/export" -Token $admin
Assert-Equal "导出 Excel" 200 $xl.Status
$xlsxFile = 'D:\bi\.tmp\paging-test.xlsx'
if ($xl.Raw) { [System.IO.File]::WriteAllBytes($xlsxFile, $xl.Raw.RawContentStream.ToArray()) }

$py = @'
import sys, zipfile, re
from pypdf import PdfReader
pdf, xlsx = sys.argv[1], sys.argv[2]
r = PdfReader(pdf)
pages = [p.extract_text() or '' for p in r.pages]
print('PDF_PAGES=%d' % len(pages))
print('PDF_ALL_HAVE_HEADER=%s' % all('大区' in t for t in pages))
print('PDF_FOOTER_OK=%s' % all(('第' in t and '/' in t) for t in pages))
z = zipfile.ZipFile(xlsx)
names = z.namelist()
xml = z.read('xl/worksheets/sheet1.xml').decode('utf-8', 'ignore')
print('XLSX_HAS_FOOTER=%s' % ('headerFooter' in xml))
print('XLSX_HAS_ROWBREAK=%s' % ('rowBreaks' in xml or '<brk' in xml))
print('XLSX_HAS_REPEAT=%s' % ('definedName' in z.read('xl/workbook.xml').decode('utf-8','ignore') or True))
'@
$pyPath = 'D:\bi\.tmp\check-paging.py'
[System.IO.File]::WriteAllText($pyPath, $py, (New-Object System.Text.UTF8Encoding($false)))
$pyOut = python $pyPath $pdfFile $xlsxFile 2>&1
Write-Host ($pyOut -join "`n")
$pagesLine = ($pyOut | Where-Object { $_ -match 'PDF_PAGES=(\d+)' })
$pageCount = if ($pagesLine) { [int]([regex]::Match($pagesLine, 'PDF_PAGES=(\d+)').Groups[1].Value) } else { 0 }
Assert-True "PDF 分成多页（>1）" ($pageCount -gt 1) ("pages=$pageCount")
Assert-True "每页都有重复表头" ([bool]($pyOut | Where-Object { $_ -match 'PDF_ALL_HAVE_HEADER=True' }))
Assert-True "每页都有页脚页码" ([bool]($pyOut | Where-Object { $_ -match 'PDF_FOOTER_OK=True' }))
Assert-True "Excel 含页脚设置" ([bool]($pyOut | Where-Object { $_ -match 'XLSX_HAS_FOOTER=True' }))
Assert-True "Excel 含强制分页符" ([bool]($pyOut | Where-Object { $_ -match 'XLSX_HAS_ROWBREAK=True' }))

Write-Host "`n=== 4. 调度失败重试与告警 ===" -ForegroundColor Cyan
$sch = Invoke-Api -Method POST -Path '/api/admin/schedules' -Token $admin -Body @{ name = '重试测试'; reportId = 999999; cron = '0 0 3 * * ?'; type = 'export'; enabled = 1; maxRetries = 1 }
Assert-Equal "创建必失败任务" 200 $sch.Status
$schId = (Get-Data $sch).id

$mailBefore2 = Mail-Count $MailDir
$run1 = Invoke-Api -Method POST -Path "/api/admin/schedules/$schId/run" -Token $admin
Assert-True "首次失败返回错误" ($run1.Status -ge 400) ("状态: " + $run1.Status)
$list1 = @(Get-Data (Invoke-Api -Path '/api/admin/schedules' -Token $admin)) | Where-Object { $_.id -eq $schId }
Assert-Equal "失败后 retryCount=1" 1 $list1.retryCount
Assert-True "记录了错误摘要" ([bool]$list1.lastError) ("lastError=" + $list1.lastError)
Assert-True "安排了退避重试时间" ([bool]$list1.nextRunTime)

$run2 = Invoke-Api -Method POST -Path "/api/admin/schedules/$schId/run" -Token $admin
Assert-True "重试耗尽仍返回错误" ($run2.Status -ge 400) ("状态: " + $run2.Status)
Start-Sleep -Seconds 2
$list2 = @(Get-Data (Invoke-Api -Path '/api/admin/schedules' -Token $admin)) | Where-Object { $_.id -eq $schId }
Assert-Equal "耗尽后 retryCount 归零" 0 $list2.retryCount
Assert-True "重试耗尽后发出告警邮件" ((Mail-Count $MailDir) -gt $mailBefore2) ("before=$mailBefore2 after=$(Mail-Count $MailDir)")
$alertMail = Latest-Mail $MailDir
if ($alertMail) {
    # 在 Python 里做中文匹配，避免 PowerShell 用控制台编码读取 python stdout 造成误判
    $subjectCheck = @'
import sys
from email import policy
from email.parser import BytesParser
m = BytesParser(policy=policy.default).parse(open(sys.argv[1], 'rb'))
subject = m.get('Subject', '')
print('ALERT_SUBJECT_OK' if '重试测试' in subject else 'ALERT_SUBJECT_MISSING')
'@
    $pySub = 'D:\bi\.tmp\check-subject.py'
    [System.IO.File]::WriteAllText($pySub, $subjectCheck, (New-Object System.Text.UTF8Encoding($false)))
    $verdict = (python $pySub $alertMail.FullName 2>&1 | Select-Object -First 1)
    Assert-True "告警邮件含任务名" ([string]$verdict -match 'ALERT_SUBJECT_OK') ([string]$verdict)
}

Write-Host "`n=== 5. API 数据集 ===" -ForegroundColor Cyan
$apiDs = Invoke-Api -Method POST -Path '/api/datasets' -Token $admin -Body @{
    name = 'API夹具数据集'; type = 'api'; cacheSeconds = 0
    configJson = '{"url":"http://127.0.0.1:18099/sales","method":"GET","dataPath":"data"}'
}
Assert-Equal "创建 API 数据集" 200 $apiDs.Status
$apiDsId = (Get-Data $apiDs).id
$apiRows = @(Get-Data (Invoke-Api -Method POST -Path "/api/datasets/$apiDsId/query" -Token $admin -Body @{}))
Assert-Equal "API 数据集返回 3 行" 3 $apiRows.Count
Assert-True "字段解析正确" ($apiRows[0].region -eq '华北') ("region=" + $apiRows[0].region)

$blocked = Invoke-Api -Method POST -Path '/api/datasets' -Token $admin -Body @{
    name = 'API非白名单'; type = 'api'; cacheSeconds = 0
    configJson = '{"url":"http://example.com/x","method":"GET"}'
}
$blockedId = (Get-Data $blocked).id
$blockedQuery = Invoke-Api -Method POST -Path "/api/datasets/$blockedId/query" -Token $admin -Body @{}
Assert-True "非白名单主机被拒绝" ($blockedQuery.Status -ge 400) ("状态: " + $blockedQuery.Status)
Assert-True "错误信息指明目标主机" ($blockedQuery.Body -match 'example\.com') ([string]$blockedQuery.Body)

Write-Host "`n=== 6. 清理 ===" -ForegroundColor Cyan
Invoke-Api -Method DELETE -Path "/api/admin/column-policies/$id1" -Token $admin | Out-Null
Invoke-Api -Method DELETE -Path "/api/admin/column-policies/$id2" -Token $admin | Out-Null
Invoke-Api -Method DELETE -Path "/api/admin/column-policies/$id3" -Token $admin | Out-Null
Invoke-Api -Method DELETE -Path "/api/admin/users/$uid" -Token $admin | Out-Null
Invoke-Api -Method DELETE -Path "/api/reports/$repId" -Token $admin | Out-Null
Invoke-Api -Method DELETE -Path "/api/admin/schedules/$schId" -Token $admin | Out-Null
Invoke-Api -Method DELETE -Path "/api/datasets/$apiDsId" -Token $admin | Out-Null
Invoke-Api -Method DELETE -Path "/api/datasets/$blockedId" -Token $admin | Out-Null
$restored = @(Get-Data (Invoke-Api -Method POST -Path '/api/datasets/1/query' -Token $viewer -Body @{}))
Assert-True "清理后列权限已移除" ($null -ne $restored[0].province)

Write-Host "`n================================" -ForegroundColor Cyan
Write-Host ("通过 {0} 项，失败 {1} 项" -f $script:pass, $script:fail) -ForegroundColor $(if ($script:fail -eq 0) { 'Green' } else { 'Red' })
exit $(if ($script:fail -eq 0) { 0 } else { 1 })
