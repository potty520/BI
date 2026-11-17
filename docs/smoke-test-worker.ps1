# ============================================================
# v1.5 ⑥ 独立调度进程验证（双进程，共享文件型 H2）
#
# 验证点：
#   1. 应用实例（rbi.schedule.enabled=false）不会自己执行到期任务；
#   2. 独立 worker 进程（profile=dev,worker，web-application-type=none）会执行并写回 last_run_time；
#   3. 导出任务在 worker 里真实落盘。
#
# 运行：powershell -NoProfile -ExecutionPolicy Bypass -File docs/smoke-test-worker.ps1
# ============================================================
param(
    [string]$JavaHome = 'D:\tools\jdk-17.0.21+9',
    [string]$Jar = 'D:\bi\backend\target\report-bi-platform-1.0.0.jar',
    [string]$BaseUrl = 'http://127.0.0.1:18090'
)

$ErrorActionPreference = 'Stop'
$script:pass = 0
$script:fail = 0
$work = 'D:\bi\.tmp\worker-test'
$dbUrl = "jdbc:h2:file:$($work.Replace('\','/'))/rbi;MODE=MySQL;DATABASE_TO_LOWER=TRUE;CASE_INSENSITIVE_IDENTIFIERS=TRUE;AUTO_SERVER=TRUE"
$exportDir = "$work\export"

function Assert-True {
    param([string]$Name, [bool]$Condition, [string]$Detail = '')
    if ($Condition) { $script:pass++; Write-Host ("  [PASS] {0}" -f $Name) -ForegroundColor Green }
    else { $script:fail++; Write-Host ("  [FAIL] {0} {1}" -f $Name, $Detail) -ForegroundColor Red }
}
function Wait-Health([int]$seconds) {
    for ($i = 0; $i -lt $seconds; $i++) {
        Start-Sleep -Seconds 1
        try { $null = Invoke-RestMethod -Uri "$BaseUrl/api/health" -TimeoutSec 2; return $true } catch { }
    }
    return $false
}
function Api($method, $path, $token, $body) {
    $h = @{}
    if ($token) { $h['Authorization'] = "Bearer $token" }
    $req = @{ Method = $method; Uri = "$BaseUrl$path"; Headers = $h; UseBasicParsing = $true; TimeoutSec = 20 }
    if ($null -ne $body) {
        $req['ContentType'] = 'application/json; charset=utf-8'
        $req['Body'] = [System.Text.Encoding]::UTF8.GetBytes(($body | ConvertTo-Json -Depth 8 -Compress))
    }
    try {
        $r = Invoke-WebRequest @req
        return [pscustomobject]@{ Status = [int]$r.StatusCode; Body = $r.Content }
    } catch {
        $s = 0
        if ($_.Exception.Response) { $s = [int]$_.Exception.Response.StatusCode }
        return [pscustomobject]@{ Status = $s; Body = $_.ErrorDetails.Message }
    }
}

New-Item -ItemType Directory -Force $work | Out-Null
Get-Process java -ErrorAction SilentlyContinue | Where-Object { $_.Path -like "$JavaHome*" } | ForEach-Object { Stop-Process -Id $_.Id -Force }
Start-Sleep -Seconds 2
Remove-Item "$work\rbi*" -Force -ErrorAction SilentlyContinue
Remove-Item $exportDir -Recurse -Force -ErrorAction SilentlyContinue

Write-Host "`n=== 1. 启动应用实例（关闭调度） ===" -ForegroundColor Cyan
$appArgs = @('-jar', $Jar, '--spring.profiles.active=dev', '--server.port=18090',
    "--spring.datasource.url=$dbUrl", "--rbi.test-datasource.url=$dbUrl",
    '--rbi.schedule.enabled=false', "--rbi.export.dir=$exportDir")
$app = Start-Process -FilePath "$JavaHome\bin\java.exe" -ArgumentList $appArgs -PassThru -WindowStyle Hidden `
    -RedirectStandardOutput "$work\app.log" -RedirectStandardError "$work\app.err"
Assert-True "应用实例启动成功" (Wait-Health 60)
$login = Invoke-RestMethod -Method POST -Uri "$BaseUrl/api/auth/login" -ContentType 'application/json' -Body '{"username":"admin","password":"123456"}'
$admin = $login.data.token
Assert-True "admin 可登录" ([bool]$admin)

# 建一个立即到期的导出任务（next_run_time 为空）
$body = @{ name = 'worker验证任务'; reportId = 1; cron = '0 0 3 * * ?'; type = 'export'; enabled = 1 } | ConvertTo-Json -Compress
$created = Api 'POST' '/api/admin/schedules' $admin ($body | ConvertFrom-Json)
$schId = (($created.Body | ConvertFrom-Json).data).id
Assert-True "创建到期任务" ([bool]$schId)

Write-Host "  等待 35 秒，确认应用实例不会执行调度…" -ForegroundColor DarkGray
Start-Sleep -Seconds 35
$list = (Api 'GET' '/api/admin/schedules' $admin).Body | ConvertFrom-Json
$row = $list.data | Where-Object { $_.id -eq $schId }
Assert-True "应用实例未执行调度（lastRunTime 为空）" ($null -eq $row.lastRunTime) ("lastRunTime=" + $row.lastRunTime)

Write-Host "`n=== 2. 启动独立 worker 进程（profile=dev,worker） ===" -ForegroundColor Cyan
$workerArgs = @('-jar', $Jar, '--spring.profiles.active=dev,worker',
    "--spring.datasource.url=$dbUrl", "--rbi.test-datasource.url=$dbUrl",
    "--rbi.export.dir=$exportDir")
$worker = Start-Process -FilePath "$JavaHome\bin\java.exe" -ArgumentList $workerArgs -PassThru -WindowStyle Hidden `
    -RedirectStandardOutput "$work\worker.log" -RedirectStandardError "$work\worker.err"
Start-Sleep -Seconds 8
Assert-True "worker 进程存活（非 Web 进程）" (-not $worker.HasExited)

Write-Host "  等待 50 秒，让 worker 的 30 秒 tick 扫描到任务…" -ForegroundColor DarkGray
Start-Sleep -Seconds 50
$list2 = (Api 'GET' '/api/admin/schedules' $admin).Body | ConvertFrom-Json
$row2 = $list2.data | Where-Object { $_.id -eq $schId }
Assert-True "worker 执行了任务（lastRunTime 已写回）" ([bool]$row2.lastRunTime) ("lastRunTime=" + $row2.lastRunTime)

$files = @(Get-ChildItem $exportDir -Filter *.xlsx -ErrorAction SilentlyContinue)
Assert-True "导出文件已落盘" ($files.Count -ge 1) ("files=" + $files.Count)
$workerLog = Get-Content "$work\worker.log" -Raw -ErrorAction SilentlyContinue
Assert-True "worker 日志出现调度记录" ($workerLog -match '\[调度\]')

Write-Host "`n=== 3. 清理 ===" -ForegroundColor Cyan
if ($worker -and -not $worker.HasExited) { Stop-Process -Id $worker.Id -Force }
if ($app -and -not $app.HasExited) { Stop-Process -Id $app.Id -Force }
Start-Sleep -Seconds 2
Assert-True "进程已停止" $true

Write-Host "`n================================" -ForegroundColor Cyan
Write-Host ("通过 {0} 项，失败 {1} 项" -f $script:pass, $script:fail) -ForegroundColor $(if ($script:fail -eq 0) { 'Green' } else { 'Red' })
exit $(if ($script:fail -eq 0) { 0 } else { 1 })
