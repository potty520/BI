# ============================================================
# v1.5 新增能力冒烟测试：
#   ① 单设备踢出  ② 密码过期  ③ 部门树/函数表达式  ④ 审计导出+水印  ⑤ OIDC 单点登录
#   （⑥ 独立调度进程用单独的双进程脚本验证，见 docs/smoke-test-worker.ps1）
#
# 前置（三个进程）：
#   node docs/dev-smtp-sink.js 1025 .tmp/mail
#   node docs/dev-http-fixture.js 18099
#   node docs/dev-oidc-fixture.js 18100
#   $env:RBI_API_DATASET_HOSTS='127.0.0.1'
#   $env:RBI_OIDC_ENABLED='true'; $env:RBI_OIDC_CLIENT_ID='bi'; $env:RBI_OIDC_CLIENT_SECRET='secret'
#   $env:RBI_OIDC_AUTHORIZE_URL='http://127.0.0.1:18100/authorize'
#   $env:RBI_OIDC_TOKEN_URL='http://127.0.0.1:18100/token'
#   $env:RBI_OIDC_USERINFO_URL='http://127.0.0.1:18100/userinfo'
#   $env:RBI_OIDC_REDIRECT_URI='http://127.0.0.1:18088/#/sso-callback'
#   java -jar backend/target/report-bi-platform-1.0.0.jar --spring.profiles.active=dev --server.port=18088
# ============================================================
param(
    [string]$BaseUrl = 'http://127.0.0.1:18088',
    [string]$OidcUrl = 'http://127.0.0.1:18100'
)

$ErrorActionPreference = 'Stop'
$script:pass = 0
$script:fail = 0

function Fix-Encoding([string]$s) {
    if (-not $s) { return $s }
    try {
        $b = [System.Text.Encoding]::GetEncoding(28591).GetBytes($s)
        return [System.Text.Encoding]::UTF8.GetString($b)
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
    return (Get-Data $r)
}

Write-Host "`n=== 0. 准备 ===" -ForegroundColor Cyan
$admin = (Login 'admin' '123456').token
Assert-True "admin 登录" ([bool]$admin)
$uname = 'v15_' + (Get-Random -Maximum 99999)
$created = Get-Data (Invoke-Api -Method POST -Path '/api/admin/users' -Token $admin -Body @{ username = $uname; realName = 'v15测试'; status = 1 })
$uid = $created.id
$tempPwd = $created.initialPassword

Write-Host "`n=== 1. 单设备踢出 ===" -ForegroundColor Cyan
$s1 = Login $uname $tempPwd
$s2 = Login $uname $tempPwd
Assert-True "两次登录得到两个会话" ($s1.sessionId -ne $s2.sessionId)
$list = @(Get-Data (Invoke-Api -Path '/api/auth/sessions' -Token $s1.token))
Assert-Equal "会话列表有 2 条" 2 $list.Count
Assert-True "列表标记当前会话" ([bool]($list | Where-Object { $_.current }))

$rev = Invoke-Api -Method DELETE -Path "/api/auth/sessions/$($s2.sessionId)" -Token $s1.token
Assert-Equal "注销另一台设备" 200 $rev.Status
Assert-Equal "被注销设备令牌失效(401)" 401 (Invoke-Api -Path '/api/auth/me' -Token $s2.token).Status
Assert-Equal "本设备仍可用(200)" 200 (Invoke-Api -Path '/api/auth/me' -Token $s1.token).Status
$list2 = @(Get-Data (Invoke-Api -Path '/api/auth/sessions' -Token $s1.token))
Assert-Equal "会话列表剩 1 条" 1 $list2.Count

$out = Invoke-Api -Method POST -Path '/api/auth/logout' -Token $s1.token
Assert-Equal "退出当前会话" 200 $out.Status
Assert-Equal "退出后令牌失效(401)" 401 (Invoke-Api -Path '/api/auth/me' -Token $s1.token).Status

Write-Host "`n=== 2. 密码过期策略 ===" -ForegroundColor Cyan
$v = Login $uname $tempPwd
Assert-True "正常登录 pwdExpired=false" (-not [bool]$v.pwdExpired)
$exp = Invoke-Api -Method POST -Path "/api/admin/users/$uid/expire-password" -Token $admin
Assert-Equal "管理员置密码过期" 200 $exp.Status
$v2 = Login $uname $tempPwd
Assert-True "过期后登录返回 pwdExpired=true" ([bool]$v2.pwdExpired -or [bool]$v2.mustChangePwd)
$chg = Invoke-Api -Method POST -Path '/api/auth/password' -Token $v2.token -Body @{ oldPassword = $tempPwd; newPassword = 'V15Pass123' }
Assert-Equal "改密成功" 200 $chg.Status
$v3 = Login $uname 'V15Pass123'
Assert-True "改密后不再过期" (-not [bool]$v3.pwdExpired -and -not [bool]$v3.mustChangePwd)

Write-Host "`n=== 3. 部门树与函数表达式 ===" -ForegroundColor Cyan
# 先清理历史残留的同名部门，保证部门树断言可重复
$existing = @(Get-Data (Invoke-Api -Path '/api/admin/depts' -Token $admin))
foreach ($d in ($existing | Where-Object { $_.name -eq 'v15子部门' })) {
    Invoke-Api -Method DELETE -Path "/api/admin/depts/$($d.id)" -Token $admin | Out-Null
}
$dept = Invoke-Api -Method POST -Path '/api/admin/depts' -Token $admin -Body @{ name = 'v15子部门'; parentId = 3; sortNo = 9 }
Assert-Equal "创建子部门" 200 $dept.Status
$deptId = (Get-Data $dept).id

$viewer = (Login 'viewer' '123456').token
$ds = Invoke-Api -Method POST -Path '/api/datasets' -Token $admin -Body @{ name = 'v15部门树数据集'; datasourceId = 1; type = 'sql'; sqlText = 'SELECT id, region, amount FROM demo_sales WHERE 1=1 ${row_filter}'; cacheSeconds = 0 }
$dsId = (Get-Data $ds).id
$scope = Invoke-Api -Method POST -Path '/api/admin/data-scopes' -Token $admin -Body @{ roleId = 3; datasetId = $dsId; scopeType = 'expr'; filterExpr = 'id IN ${deptTree}' }
Assert-Equal "配置部门树表达式" 200 $scope.Status
$scopeId = (Get-Data $scope).id
$rows = @(Get-Data (Invoke-Api -Method POST -Path "/api/datasets/$dsId/query" -Token $viewer -Body @{}))
# viewer 部门为 3，子树 = (3, 新建子部门)；对应 demo_sales 中 id 命中的行
$expected = @(3, $deptId)
$allRows = @(Get-Data (Invoke-Api -Method POST -Path "/api/datasets/$dsId/query" -Token $admin -Body @{}))
$expectCount = @($allRows | Where-Object { $expected -contains $_.id }).Count
Assert-Equal "部门树展开为 IN 列表后行数正确" $expectCount $rows.Count

Invoke-Api -Method DELETE -Path "/api/admin/data-scopes/$scopeId" -Token $admin | Out-Null
$fn = Invoke-Api -Method POST -Path '/api/admin/data-scopes' -Token $admin -Body @{ roleId = 3; datasetId = $dsId; scopeType = 'expr'; filterExpr = "LOWER(region) = '华北'" }
Assert-Equal "白名单函数 LOWER 可用" 200 $fn.Status
$fnId = (Get-Data $fn).id
$fnRows = @(Get-Data (Invoke-Api -Method POST -Path "/api/datasets/$dsId/query" -Token $viewer -Body @{}))
Assert-True "函数表达式过滤生效" ($fnRows.Count -gt 0 -and (@($fnRows | Where-Object { $_.region -ne '华北' }).Count -eq 0)) ("rows=" + $fnRows.Count)
$badFn = Invoke-Api -Method POST -Path '/api/admin/data-scopes' -Token $admin -Body @{ roleId = 3; datasetId = $dsId; scopeType = 'expr'; filterExpr = 'SLEEP(1) = 0' }
Assert-Equal "非白名单函数被拒绝(400)" 400 $badFn.Status
Invoke-Api -Method DELETE -Path "/api/admin/data-scopes/$fnId" -Token $admin | Out-Null

Write-Host "`n=== 4. 审计导出与导出水印 ===" -ForegroundColor Cyan
$logCsv = Invoke-Api -Path '/api/admin/logs/export' -Token $admin
Assert-Equal "导出审计日志" 200 $logCsv.Status
if ($logCsv.Raw) {
    $txt = [System.Text.Encoding]::UTF8.GetString($logCsv.Raw.RawContentStream.ToArray())
    Assert-True "审计 CSV 带导出人水印" ($txt -match '导出人 admin') ("首行: " + ($txt.Split("`r`n")[0]))
    Assert-True "审计 CSV 含表头字段" ($txt -match 'username' -and $txt -match 'operation')
}
$dsCsv = Invoke-Api -Path '/api/datasets/1/export.csv' -Token $admin
if ($dsCsv.Raw) {
    $txt2 = [System.Text.Encoding]::UTF8.GetString($dsCsv.Raw.RawContentStream.ToArray())
    Assert-True "数据集 CSV 带水印" ($txt2 -match '导出人 admin' -and $txt2 -match '仅限内部使用')
}
$pdf = Invoke-Api -Path '/api/reports/1/export.pdf' -Token $admin
Assert-Equal "导出 PDF" 200 $pdf.Status
if ($pdf.Raw) { [System.IO.File]::WriteAllBytes('D:\bi\.tmp\wm.pdf', $pdf.Raw.RawContentStream.ToArray()) }
$xl = Invoke-Api -Path '/api/reports/1/export' -Token $admin
if ($xl.Raw) { [System.IO.File]::WriteAllBytes('D:\bi\.tmp\wm.xlsx', $xl.Raw.RawContentStream.ToArray()) }

$py = @'
import sys, zipfile
from pypdf import PdfReader
pdf, xlsx = sys.argv[1], sys.argv[2]
pages = [p.extract_text() or '' for p in PdfReader(pdf).pages]
print('PDF_WM=%s' % any('导出人' in t and '仅限内部使用' in t for t in pages))
z = zipfile.ZipFile(xlsx)
xml = z.read('xl/worksheets/sheet1.xml').decode('utf-8', 'ignore')
wb = z.read('xl/workbook.xml').decode('utf-8', 'ignore')
print('XLSX_HEADER_WM=%s' % (('导出人' in xml) or ('导出人' in wb)))
'@
[System.IO.File]::WriteAllText('D:\bi\.tmp\check-wm.py', $py, (New-Object System.Text.UTF8Encoding($false)))
$out2 = python 'D:\bi\.tmp\check-wm.py' 'D:\bi\.tmp\wm.pdf' 'D:\bi\.tmp\wm.xlsx' 2>&1
Write-Host ($out2 -join "`n")
Assert-True "PDF 页脚带导出水印" ([bool]($out2 | Where-Object { $_ -match 'PDF_WM=True' }))
Assert-True "Excel 页眉带导出水印" ([bool]($out2 | Where-Object { $_ -match 'XLSX_HEADER_WM=True' }))

Write-Host "`n=== 5. OIDC 单点登录 ===" -ForegroundColor Cyan
$cfg = Get-Data (Invoke-Api -Path '/api/auth/oidc/config')
Assert-True "SSO 已启用" ([bool]$cfg.enabled)
$auth = Get-Data (Invoke-Api -Path '/api/auth/oidc/authorize')
Assert-True "返回授权地址" ([bool]($auth.url -match 'redirect_uri='))
$state = $auth.state

# 模拟浏览器跳转 IdP：用 curl 取 302 的 Location（PS 5.1 对 302 的响应头处理不可靠）
$loc = (& curl.exe -s -o NUL -w '%{redirect_url}' $auth.url 2>$null)
if ($loc) { $loc = $loc.Trim() }
Assert-True "IdP 返回重定向" ([bool]$loc) ("loc=" + $loc)
$code = $null
if ($loc) { $m = [regex]::Match([string]$loc, 'code=([^&]+)'); if ($m.Success) { $code = $m.Groups[1].Value } }
Assert-True "拿到授权码" ([bool]$code)

$cb = Invoke-Api -Path "/api/auth/oidc/callback?code=$code&state=$state"
Assert-Equal "回调换取平台令牌" 200 $cb.Status
$ssoToken = (Get-Data $cb).token
Assert-True "SSO 令牌可访问接口" ([bool]$ssoToken)
$me = Get-Data (Invoke-Api -Path '/api/auth/me' -Token $ssoToken)
Assert-Equal "SSO 登录身份为 analyst" 'analyst' $me.username
$reuse = Invoke-Api -Path "/api/auth/oidc/callback?code=$code&state=$state"
Assert-Equal "state 不可重复使用(400)" 400 $reuse.Status

Write-Host "`n=== 6. 清理 ===" -ForegroundColor Cyan
Invoke-Api -Method DELETE -Path "/api/datasets/$dsId" -Token $admin | Out-Null
Invoke-Api -Method DELETE -Path "/api/admin/depts/$deptId" -Token $admin | Out-Null
Invoke-Api -Method DELETE -Path "/api/admin/users/$uid" -Token $admin | Out-Null
Assert-True "清理完成" $true

Write-Host "`n================================" -ForegroundColor Cyan
Write-Host ("通过 {0} 项，失败 {1} 项" -f $script:pass, $script:fail) -ForegroundColor $(if ($script:fail -eq 0) { 'Green' } else { 'Red' })
exit $(if ($script:fail -eq 0) { 0 } else { 1 })
