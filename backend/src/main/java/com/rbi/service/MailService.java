package com.rbi.service;

import com.rbi.common.BizException;
import com.rbi.export.ExportService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Service;

import javax.mail.internet.MimeMessage;
import java.util.HashMap;

/**
 * 邮件推送（M09 定时任务的 email 类型）。
 *
 * 未配置 SMTP（spring.mail.host 为空）时抛业务异常，由调度器记 FAIL 并写审计，
 * 保证"配了任务但没配邮箱"是可观测的失败，而不是静默成功。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MailService {

    private final JavaMailSender mailSender;
    private final ExportService exportService;

    @Value("${spring.mail.host:}")
    private String host;

    @Value("${rbi.mail-from:bi@localhost}")
    private String from;

    public boolean configured() {
        return host != null && !host.trim().isEmpty();
    }

    /** 找回密码邮件 */
    public void sendResetLink(String to, String link, String username) {
        if (!configured()) throw new BizException("未配置 SMTP，无法发送重置邮件");
        try {
            MimeMessage message = mailSender.createMimeMessage();
            MimeMessageHelper helper = new MimeMessageHelper(message, false, "UTF-8");
            helper.setFrom(from);
            helper.setTo(to.trim());
            helper.setSubject("【BI 平台】重置登录密码");
            helper.setText("您好 " + username + "：\n\n"
                    + "请点击以下链接重置密码（30 分钟内有效，仅可使用一次）：\n" + link + "\n\n"
                    + "如非本人操作，请忽略本邮件并联系管理员。", false);
            mailSender.send(message);
            log.info("[邮件] 重置密码邮件已发送至 {}", to);
        } catch (BizException e) {
            throw e;
        } catch (Exception e) {
            throw new BizException("重置邮件发送失败: " + e.getMessage());
        }
    }

    /** 调度失败告警 */
    public void sendAlert(String to, String subject, String content) {
        if (!configured() || to == null || to.trim().isEmpty()) {
            log.warn("[告警] 未配置 SMTP 或接收人，告警未发送：{} - {}", subject, content);
            return;
        }
        try {
            MimeMessage message = mailSender.createMimeMessage();
            MimeMessageHelper helper = new MimeMessageHelper(message, false, "UTF-8");
            helper.setFrom(from);
            helper.setTo(to.split("[,;]"));
            helper.setSubject("【BI 告警】" + subject);
            helper.setText(content, false);
            mailSender.send(message);
            log.info("[告警] 已发送: {}", subject);
        } catch (Exception e) {
            log.error("[告警] 发送失败: {}", e.getMessage());
        }
    }

    /** 把报表渲染成 xlsx 作为附件发给接收人（逗号分隔） */
    public void sendReport(Long reportId, String reportName, String receivers) {
        if (!configured()) {
            throw new BizException("未配置 SMTP：请设置 RBI_MAIL_HOST / RBI_MAIL_PORT 等环境变量后重试");
        }
        if (receivers == null || receivers.trim().isEmpty()) {
            throw new BizException("任务未配置接收人");
        }
        String[] to = receivers.split("[,;]");
        for (int i = 0; i < to.length; i++) to[i] = to[i].trim();

        String name = reportName == null || reportName.trim().isEmpty() ? ("报表" + reportId) : reportName;
        try {
            byte[] xlsx = exportService.reportToXlsx(reportId, new HashMap<>());
            MimeMessage message = mailSender.createMimeMessage();
            MimeMessageHelper helper = new MimeMessageHelper(message, true, "UTF-8");
            helper.setFrom(from);
            helper.setTo(to);
            helper.setSubject("【BI 报表】" + name);
            helper.setText("您好，报表《" + name + "》见附件，由定时任务自动生成。", false);
            helper.addAttachment(name + ".xlsx", new ByteArrayResource(xlsx),
                    "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");
            mailSender.send(message);
            log.info("[邮件] 报表 {} 已发送至 {}", reportId, receivers);
        } catch (BizException e) {
            throw e;
        } catch (Exception e) {
            throw new BizException("邮件发送失败: " + e.getMessage());
        }
    }
}
