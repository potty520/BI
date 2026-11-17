# ============================================================
# v1.4 新增能力冒烟测试：
#   ① 令牌吊销  ② MFA(TOTP)  ③ 行级表达式  ④ 分组换页+条件样式  ⑤ API 数据集分页
#
# 前置：
#   node docs/dev-http-fixture.js 18099
#   $env:RBI_API_DATASET_HOSTS='127.0.0.1'
#   java -jar backend/target/report-bi-platform-1.0.0.jar --spring.profiles.active=dev --server.port=18088
#
# 运行：powershell -NoProfile -ExecutionPolicy Bypass -File docs/smoke-test-v14.ps1
# ============================================================
param(
    [string]$BaseUrl = 'http://127.0.0.1:18088',
    [string]$FixtureUrl = 'http://127.0.0.1:18099'
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

# TOTP 计算（纯 Python 标准库）
$totpPy = @'
import base64, hmac, hashlib, struct, sys, time
secret = sys.argv[1].strip().upper()
secret += '=' * ((8 - len(secret) % 8) % 8)
key = base64.b32decode(secret)
counter = int(time.time()) // 30
msg = struct.pack('>Q', counter)
h = hmac.new(key, msg, hashlib.sha1).digest()
o = h[-1] & 0x0f
print('%06d' % ((struct.unpack('>I', h[o:o+4])[0] & 0x7fffffff) % 1000000))
'@
$totpPath = 'D:\bi\.tmp\totp.py'
[System.IO.File]::WriteAllText($totpPath, $totpPy, (New-Object System.Text.UTF8Encoding($false)))
function Totp([string]$secret) { return (python $totpPath $secret).Trim() }

Write-Host "`n=== 0. 准备 ===" -ForegroundColor Cyan
$adminLogin = Login 'admin' '123456'
$admin = $adminLogin.token
Assert-True "admin 登录" ([bool]$admin)

# 建一个专用测试账号，避免影响内置账号
$uname = 'v14_' + (Get-Random -Maximum 99999)
$created = Get-Data (Invoke-Api -Method POST -Path '/api/admin/users' -Token $admin -Body @{ username = $uname; realName = 'v14测试'; status = 1 })
$uid = $created.id
$tempPwd = $created.initialPassword
$viewer1 = Login $uname $tempPwd
Assert-True "新建账号可登录" ([bool]$viewer1.token)

Write-Host "`n=== 1. 令牌吊销（token_version） ===" -ForegroundColor Cyan
$t1 = $viewer1.token
Assert-Equal "退出前令牌可用" 200 (Invoke-Api -Path '/api/auth/me' -Token $t1).Status
$out = Invoke-Api -Method POST -Path '/api/auth/logout' -Token $t1
Assert-Equal "退出登录" 200 $out.Status
Assert-Equal "退出后旧令牌立即失效(401)" 401 (Invoke-Api -Path '/api/auth/me' -Token $t1).Status

$v2 = Login $uname $tempPwd
Assert-True "重新登录拿到新令牌" ([bool]$v2.token)
$chg = Invoke-Api -Method POST -Path '/api/auth/password' -Token $v2.token -Body @{ oldPassword = $tempPwd; newPassword = 'V14Pass123' }
Assert-Equal "修改密码" 200 $chg.Status
Assert-Equal "改密后旧令牌立即失效(401)" 401 (Invoke-Api -Path '/api/auth/me' -Token $v2.token).Status
$v3 = Login $uname 'V14Pass123'
Assert-True "新密码可登录" ([bool]$v3.token)

$kick = Invoke-Api -Method POST -Path "/api/admin/users/$uid/kick" -Token $admin
Assert-Equal "管理员踢下线" 200 $kick.Status
Assert-Equal "被踢后令牌立即失效(401)" 401 (Invoke-Api -Path '/api/auth/me' -Token $v3.token).Status

Write-Host "`n=== 2. MFA（TOTP） ===" -ForegroundColor Cyan
$v4 = Login $uname 'V14Pass123'
$setup = Invoke-Api -Method POST -Path '/api/auth/mfa/setup' -Token $v4.token
Assert-Equal "生成 MFA 密钥" 200 $setup.Status
$secret = (Get-Data $setup).secret
Assert-True "返回 Base32 密钥" ($secret -match '^[A-Z2-7]{16,}$') ("secret=" + $secret)
Assert-True "返回 otpauth 链接" ([bool]((Get-Data $setup).otpauth -match 'otpauth://totp/'))

$badCode = Invoke-Api -Method POST -Path '/api/auth/mfa/enable' -Token $v4.token -Body @{ code = '000000' }
Assert-Equal "错误动态码不能启用" 400 $badCode.Status
$goodCode = Totp $secret
$enable = Invoke-Api -Method POST -Path '/api/auth/mfa/enable' -Token $v4.token -Body @{ code = $goodCode }
Assert-Equal "正确动态码启用 MFA" 200 $enable.Status

$loginMfa = Login $uname 'V14Pass123'
Assert-True "启用后登录需要二次验证" ([bool]$loginMfa.mfaRequired)
Assert-True "未拿到正式令牌" (-not [bool]$loginMfa.token)
$mfaTok = $loginMfa.mfaToken
$badMfa = Invoke-Api -Method POST -Path '/api/auth/login/mfa' -Body @{ mfaToken = $mfaTok; code = '111111' }
Assert-Equal "错误动态码被拒绝" 401 $badMfa.Status
$okMfa = Invoke-Api -Method POST -Path '/api/auth/login/mfa' -Body @{ mfaToken = $mfaTok; code = (Totp $secret) }
Assert-Equal "正确动态码换取令牌" 200 $okMfa.Status
$mfaToken = (Get-Data $okMfa).token
Assert-Equal "二次验证后的令牌可用" 200 (Invoke-Api -Path '/api/auth/me' -Token $mfaToken).Status

$disable = Invoke-Api -Method POST -Path '/api/auth/mfa/disable' -Token $mfaToken -Body @{ code = (Totp $secret) }
Assert-Equal "关闭 MFA" 200 $disable.Status
$plain = Login $uname 'V14Pass123'
Assert-True "关闭后恢复单步登录" ([bool]$plain.token -and -not [bool]$plain.mfaRequired)

Write-Host "`n=== 3. 行级权限表达式 ===" -ForegroundColor Cyan
$viewerTok = (Login 'viewer' '123456').token
$adminRows = @(Get-Data (Invoke-Api -Method POST -Path '/api/datasets/1/query' -Token $admin -Body @{}))
$expect = @($adminRows | Where-Object { [double]$_.amount -lt 5000 }).Count

$expr = Invoke-Api -Method POST -Path '/api/admin/data-scopes' -Token $admin -Body @{ roleId = 3; datasetId = 1; scopeType = 'expr'; filterExpr = 'amount < 5000' }
Assert-Equal "配置表达式范围" 200 $expr.Status
$exprId = (Get-Data $expr).id
$viewerRows = @(Get-Data (Invoke-Api -Method POST -Path '/api/datasets/1/query' -Token $viewerTok -Body @{}))
Assert-Equal "表达式过滤后行数一致" $expect $viewerRows.Count
Assert-True "所有行都满足 amount < 5000" (@($viewerRows | Where-Object { [double]$_.amount -ge 5000 }).Count -eq 0)

$badExpr = Invoke-Api -Method POST -Path '/api/admin/data-scopes' -Token $admin -Body @{ roleId = 3; datasetId = 1; scopeType = 'expr'; filterExpr = '1=1; DROP TABLE rbi_user' }
Assert-Equal "危险表达式被拒绝(400)" 400 $badExpr.Status
$badExpr2 = Invoke-Api -Method POST -Path '/api/admin/data-scopes' -Token $admin -Body @{ roleId = 3; datasetId = 1; scopeType = 'expr'; filterExpr = 'amount < (select 1)' }
Assert-Equal "子查询表达式被拒绝(400)" 400 $badExpr2.Status

# 上下文变量 ${deptId}：viewer 的 deptId=3，条件恒真 -> 返回全量
Invoke-Api -Method DELETE -Path "/api/admin/data-scopes/$exprId" -Token $admin | Out-Null
$ctx = Invoke-Api -Method POST -Path '/api/admin/data-scopes' -Token $admin -Body @{ roleId = 3; datasetId = 1; scopeType = 'expr'; filterExpr = '${deptId} >= 0' }
$ctxId = (Get-Data $ctx).id
$ctxRows = @(Get-Data (Invoke-Api -Method POST -Path '/api/datasets/1/query' -Token $viewerTok -Body @{}))
Assert-Equal "上下文变量生效（恒真返回全量）" $adminRows.Count $ctxRows.Count
Invoke-Api -Method DELETE -Path "/api/admin/data-scopes/$ctxId" -Token $admin | Out-Null

Write-Host "`n=== 4. 分组换页 + 条件样式 ===" -ForegroundColor Cyan
$layout = '{"cols":3,"rows":60,"headerRows":2,"breakOn":"region","footerText":"第 {page}/{pages} 页",' +
          '"cells":[' +
          '{"ref":"A1","value":"分组换页测试","type":"text","style":{"bold":true,"colspan":3}},' +
          '{"ref":"A2","value":"大区","type":"text","style":{"bold":true}},' +
          '{"ref":"B2","value":"省份","type":"text","style":{"bold":true}},' +
          '{"ref":"C2","value":"销售额","type":"text","style":{"bold":true}},' +
          '{"ref":"A3","type":"dataset","field":"region","expand":"v","parent":""},' +
          '{"ref":"B3","type":"dataset","field":"province","expand":"v","parent":"A3"},' +
          '{"ref":"C3","type":"formula","value":"SUM(amount)","expand":"none","style":{"rules":[{"when":">15000","bg":"#FFF2CC","color":"#C00000","bold":true}]}}]}'
$rep = Invoke-Api -Method POST -Path '/api/reports' -Token $admin -Body @{ report = @{ name = '分组换页测试'; datasetId = 1; layoutJson = $layout; paramsJson = '[]' }; remark = 'v14' }
Assert-Equal "创建分组换页报表" 200 $rep.Status
$repId = (Get-Data $rep).id

$pdf = Invoke-Api -Path "/api/reports/$repId/export.pdf" -Token $admin
Assert-Equal "导出 PDF" 200 $pdf.Status
if ($pdf.Raw) { [System.IO.File]::WriteAllBytes('D:\bi\.tmp\group-break.pdf', $pdf.Raw.RawContentStream.ToArray()) }
$xl = Invoke-Api -Path "/api/reports/$repId/export" -Token $admin
Assert-Equal "导出 Excel" 200 $xl.Status
if ($xl.Raw) { [System.IO.File]::WriteAllBytes('D:\bi\.tmp\group-break.xlsx', $xl.Raw.RawContentStream.ToArray()) }

$py = @'
import sys, zipfile
from pypdf import PdfReader
pdf, xlsx = sys.argv[1], sys.argv[2]
pages = [p.extract_text() or '' for p in PdfReader(pdf).pages]
regions = ['华北', '华东', '华南', '西北', '西南']
print('PAGES=%d' % len(pages))
print('PAGES_GE_REGIONS=%s' % (len(pages) >= 5))
print('EACH_PAGE_HAS_HEADER=%s' % all('大区' in t for t in pages))
# 每页只出现一个大区（分组换页的核心断言）
per_page = [sorted(set(r for r in regions if r in t)) for t in pages]
print('ONE_REGION_PER_PAGE=%s' % all(len(x) <= 1 for x in per_page))
print('PER_PAGE=%s' % per_page)
z = zipfile.ZipFile(xlsx)
styles = z.read('xl/styles.xml').decode('utf-8', 'ignore')
print('XLSX_HAS_BG_COLOR=%s' % ('FFF2CC' in styles.upper()))
print('XLSX_HAS_ROW_BREAK=%s' % ('rowBreaks' in z.read('xl/worksheets/sheet1.xml').decode('utf-8','ignore')))
'@
[System.IO.File]::WriteAllText('D:\bi\.tmp\check-v14.py', $py, (New-Object System.Text.UTF8Encoding($false)))
$out2 = python 'D:\bi\.tmp\check-v14.py' 'D:\bi\.tmp\group-break.pdf' 'D:\bi\.tmp\group-break.xlsx' 2>&1
Write-Host ($out2 -join "`n")
Assert-True "PDF 页数 ≥ 大区数(5)" ([bool]($out2 | Where-Object { $_ -match 'PAGES_GE_REGIONS=True' }))
Assert-True "每页都有表头" ([bool]($out2 | Where-Object { $_ -match 'EACH_PAGE_HAS_HEADER=True' }))
Assert-True "每页只出现一个大区（分组换页生效）" ([bool]($out2 | Where-Object { $_ -match 'ONE_REGION_PER_PAGE=True' })) (($out2 | Where-Object { $_ -match 'PER_PAGE=' }) -join '')
Assert-True "Excel 写入条件样式底色" ([bool]($out2 | Where-Object { $_ -match 'XLSX_HAS_BG_COLOR=True' }))
Assert-True "Excel 写入分组分页符" ([bool]($out2 | Where-Object { $_ -match 'XLSX_HAS_ROW_BREAK=True' }))

Write-Host "`n=== 5. API 数据集分页拉取 ===" -ForegroundColor Cyan
$apiDs = Invoke-Api -Method POST -Path '/api/datasets' -Token $admin -Body @{
    name = 'API分页数据集'; type = 'api'; cacheSeconds = 0
    configJson = '{"url":"http://127.0.0.1:18099/paged?page=${page}&size=${size}","method":"GET","dataPath":"data","paging":{"start":1,"size":100,"maxPages":10}}'
}
Assert-Equal "创建分页 API 数据集" 200 $apiDs.Status
$apiDsId = (Get-Data $apiDs).id
$rows = @(Get-Data (Invoke-Api -Method POST -Path "/api/datasets/$apiDsId/query" -Token $admin -Body @{}))
Assert-Equal "分页拉取到 250 行（3 页）" 250 $rows.Count
Assert-True "首尾行有序" (($rows[0].id -eq 1) -and ($rows[249].id -eq 250)) ("first=" + $rows[0].id + " last=" + $rows[249].id)

Write-Host "`n=== 6. 清理 ===" -ForegroundColor Cyan
Invoke-Api -Method DELETE -Path "/api/reports/$repId" -Token $admin | Out-Null
Invoke-Api -Method DELETE -Path "/api/datasets/$apiDsId" -Token $admin | Out-Null
Invoke-Api -Method DELETE -Path "/api/admin/users/$uid" -Token $admin | Out-Null
Assert-True "清理完成" $true

Write-Host "`n================================" -ForegroundColor Cyan
Write-Host ("通过 {0} 项，失败 {1} 项" -f $script:pass, $script:fail) -ForegroundColor $(if ($script:fail -eq 0) { 'Green' } else { 'Red' })
exit $(if ($script:fail -eq 0) { 0 } else { 1 })
