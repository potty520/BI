package com.rbi.schedule;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.rbi.common.BizException;
import com.rbi.entity.Report;
import com.rbi.entity.Schedule;
import com.rbi.mapper.ReportMapper;
import com.rbi.mapper.ScheduleMapper;
import com.rbi.security.AuthContext;
import com.rbi.security.CurrentUser;
import com.rbi.service.DatasetQueryService;
import com.rbi.service.MailService;
import com.rbi.export.ExportService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.scheduling.support.CronExpression;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Date;
import java.util.List;

/**
 * 定时调度执行器 (M09-03)
 *
 * 修复点：原实现只有 rbi_schedule 表与 CRUD 界面，没有任何执行器，任务永远不会跑。
 * 现在由 Spring Scheduling 每 30 秒扫描一次到期任务，按 cron 计算下次执行时间：
 *  - refresh：刷新报表所依赖数据集的缓存；
 *  - export ：按任务执行一次报表导出，落盘到 rbi.export.dir；
 *  - email  ：邮件推送未配置 SMTP，记录失败状态并在日志中提示（避免静默假成功）。
 *
 * 单节点实现：多节点部署需引入分布式锁（如 ShedLock），否则会重复执行。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ScheduleRunner {

    private static final int SCAN_BATCH = 50;
    /** 任务执行租约：多节点部署时防止重复执行 */
    private static final long LEASE_MS = 5 * 60 * 1000L;

    private final ScheduleMapper scheduleMapper;
    private final ReportMapper reportMapper;
    private final DatasetQueryService datasetQueryService;
    private final ExportService exportService;
    private final MailService mailService;

    @Value("${rbi.export.dir:./export}")
    private String exportDir;

    /** 失败告警接收人（逗号分隔）；为空则只记日志 */
    @Value("${rbi.alert-to:}")
    private String alertTo;

    /** 重试退避分钟数，逗号分隔，按重试次序取值 */
    @Value("${rbi.schedule.retry-backoff:1,5,15}")
    private String retryBackoff;

    /**
     * 是否在本进程运行调度。
     * 应用实例默认 false（只提供手动触发接口），独立 worker 进程（profile=worker）为 true。
     */
    @Value("${rbi.schedule.enabled:false}")
    private boolean schedulingEnabled;

    @Scheduled(initialDelay = 20000, fixedDelay = 30000)
    public void tick() {
        // 应用实例不跑调度，由独立 worker 负责；手动触发不受此开关影响
        if (!schedulingEnabled) return;
        List<Schedule> due;
        try {
            due = findDue();
        } catch (Exception e) {
            log.warn("[调度] 扫描任务失败: {}", e.getMessage());
            return;
        }
        for (Schedule s : due) {
            runOne(s);
        }
    }

    private List<Schedule> findDue() {
        Date now = new Date();
        LambdaQueryWrapper<Schedule> w = new LambdaQueryWrapper<>();
        w.eq(Schedule::getEnabled, 1)
                .and(q -> q.isNull(Schedule::getNextRunTime).or().le(Schedule::getNextRunTime, now))
                .and(q -> q.isNull(Schedule::getLockedUntil).or().lt(Schedule::getLockedUntil, now))
                .last("LIMIT " + SCAN_BATCH);
        return scheduleMapper.selectList(w);
    }

    /** 执行单个任务并推进 nextRunTime（失败也推进，避免同一任务每个 tick 重复失败） */
    void runOne(Schedule schedule) {
        // 领取任务：多节点部署时只有拿到租约的节点执行
        Date nowDate = new Date();
        if (scheduleMapper.claim(schedule.getId(), new Date(nowDate.getTime() + LEASE_MS), nowDate) != 1) {
            log.debug("[调度] 任务 #{} 已被其它节点领取，跳过", schedule.getId());
            return;
        }

        String cron = schedule.getCron();
        LocalDateTime now = LocalDateTime.now();
        LocalDateTime next = null;
        try {
            if (cron != null && !cron.trim().isEmpty()) {
                CronExpression expr = CronExpression.parse(cron.trim());
                next = expr.next(now);
            }
        } catch (IllegalArgumentException e) {
            log.warn("[调度] 任务 {} 的 cron 表达式非法: {}", schedule.getId(), cron);
        }

        boolean exhausted = false;
        String errorText = null;
        // 调度线程没有登录上下文：临时以系统身份执行，否则资源权限校验会以 403 拦下后台任务
        CurrentUser previous = AuthContext.get();
        try {
            AuthContext.set(AuthContext.systemUser());
            log.info("[调度] 执行任务 #{} {}（type={}）", schedule.getId(), schedule.getName(), schedule.getType());
            execute(schedule);
            schedule.setLastRunTime(new Date());
            schedule.setRetryCount(0);
            schedule.setLastError(null);
            log.info("[调度] 任务 #{} 执行成功", schedule.getId());
        } catch (Exception e) {
            schedule.setLastRunTime(new Date());
            errorText = e.getMessage();
            exhausted = handleFailure(schedule, e);
            log.error("[调度] 任务 #{} 执行失败: {}", schedule.getId(), e.getMessage(), e);
        } finally {
            // 恢复调用方上下文（手动触发时是当前管理员）
            if (previous != null) AuthContext.set(previous);
            else AuthContext.clear();
            if (!exhausted) {
                // 成功：按 cron 取下个时间点；失败未耗尽：保留 handleFailure 设置的退避时间
                if (errorText == null) {
                    schedule.setNextRunTime(next == null ? null
                            : Date.from(next.atZone(ZoneId.systemDefault()).toInstant()));
                }
            }
            try {
                scheduleMapper.updateById(schedule);
            } catch (Exception e) {
                log.warn("[调度] 回写任务状态失败: {}", e.getMessage());
            } finally {
                scheduleMapper.release(schedule.getId());
            }
        }
    }

    /**
     * 失败处理：未达最大重试次数则按退避时间安排重试，达到上限则告警并回到正常 cron 周期。
     * @return 是否已耗尽重试（耗尽时调用方不再按 cron 计算 next）
     */
    private boolean handleFailure(Schedule schedule, Exception e) {
        int retry = schedule.getRetryCount() == null ? 0 : schedule.getRetryCount();
        int max = schedule.getMaxRetries() == null ? 3 : schedule.getMaxRetries();
        String err = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
        schedule.setLastError(err.length() > 500 ? err.substring(0, 500) : err);

        if (retry < max) {
            schedule.setRetryCount(retry + 1);
            long minutes = backoffMinutes(retry);
            schedule.setNextRunTime(new Date(System.currentTimeMillis() + minutes * 60_000L));
            log.warn("[调度] 任务 #{} 第 {}/{} 次失败，{} 分钟后重试: {}",
                    schedule.getId(), retry + 1, max, minutes, err);
            return false;
        }

        // 重试耗尽：重置计数，回到正常周期，并发出告警
        schedule.setRetryCount(0);
        String subject = "调度任务失败: " + (schedule.getName() == null ? ("#" + schedule.getId()) : schedule.getName());
        String content = "任务 #" + schedule.getId() + "（" + schedule.getType() + "）已重试 " + max + " 次仍失败。\n"
                + "最近错误: " + err + "\n"
                + "关联报表: " + schedule.getReportId() + "\n"
                + "请登录平台检查任务配置或数据源。";
        mailService.sendAlert(alertTo, subject, content);
        log.error("[调度] 任务 #{} 重试耗尽，已发送告警", schedule.getId());
        return true;
    }

    private long backoffMinutes(int retryIndex) {
        String spec = retryBackoff == null || retryBackoff.trim().isEmpty() ? "1,5,15" : retryBackoff;
        String[] parts = spec.split(",");
        int idx = Math.min(retryIndex, parts.length - 1);
        try {
            return Math.max(1, Long.parseLong(parts[idx].trim()));
        } catch (NumberFormatException e) {
            return 5;
        }
    }

    private void execute(Schedule schedule) throws IOException {
        String type = schedule.getType() == null ? "refresh" : schedule.getType().toLowerCase();
        Long reportId = schedule.getReportId();

        switch (type) {
            case "refresh": {
                if (reportId == null) return;
                Report report = reportMapper.selectById(reportId);
                if (report == null) throw new BizException("报表不存在: " + reportId);
                if (report.getDatasetId() != null) {
                    datasetQueryService.clearCache(report.getDatasetId());
                    log.info("[调度] 已刷新数据集 {} 缓存", report.getDatasetId());
                }
                return;
            }
            case "export": {
                if (reportId == null) throw new BizException("导出任务缺少报表ID");
                Report report = reportMapper.selectById(reportId);
                if (report == null) throw new BizException("报表不存在: " + reportId);
                byte[] bytes = exportService.reportToXlsx(reportId, new java.util.HashMap<>(),
                        "导出人 system(定时任务) · " + new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm").format(new Date()) + " · 仅限内部使用");
                Path dir = Paths.get(exportDir).toAbsolutePath().normalize();
                Files.createDirectories(dir);
                String fileName = "report-" + reportId + "-" + System.currentTimeMillis() + ".xlsx";
                Path target = dir.resolve(fileName).normalize();
                if (!target.startsWith(dir)) throw new BizException("导出路径非法");
                Files.write(target, bytes);
                log.info("[调度] 报表 {} 已导出到 {}", reportId, target);
                return;
            }
            case "email": {
                if (reportId == null) throw new BizException("邮件任务缺少报表ID");
                Report report = reportMapper.selectById(reportId);
                if (report == null) throw new BizException("报表不存在: " + reportId);
                mailService.sendReport(reportId, report.getName(), schedule.getReceiver());
                return;
            }
            default:
                log.warn("[调度] 未知任务类型: {}", type);
        }
    }

    /** 供管理端手动触发（不推进 cron，只执行一次） */
    public void runNow(Long scheduleId) {
        Schedule s = scheduleMapper.selectById(scheduleId);
        if (s == null) throw new BizException(404, "任务不存在");
        Date now = new Date();
        if (scheduleMapper.claim(scheduleId, new Date(now.getTime() + LEASE_MS), now) != 1) {
            throw new BizException(409, "任务正在执行中，请稍后重试");
        }
        log.info("[调度] 管理员 {} 手动触发任务 #{}", AuthContext.usernameOrSystem(), scheduleId);
        CurrentUser previous = AuthContext.get();
        try {
            AuthContext.set(AuthContext.systemUser());
            execute(s);
            s.setLastRunTime(new Date());
            s.setRetryCount(0);
            s.setLastError(null);
        } catch (Exception e) {
            log.error("[调度] 手动执行失败", e);
            s.setLastRunTime(new Date());
            handleFailure(s, e);
            throw new BizException("执行失败: " + e.getMessage());
        } finally {
            if (previous != null) AuthContext.set(previous);
            else AuthContext.clear();
            scheduleMapper.updateById(s);
            scheduleMapper.release(scheduleId);
        }
    }
}
