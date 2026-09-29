package com.edupath.auth;

import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeMessage;
import java.nio.charset.StandardCharsets;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Service;

@Service
public class EmailService {

    private final JavaMailSender mailSender;
    private final boolean enabled;
    private final String from;
    private final String fromName;

    public EmailService(
            ObjectProvider<JavaMailSender> mailSenderProvider,
            @Value("${edupath.email.enabled:false}") boolean enabled,
            @Value("${edupath.email.from:}") String from,
            @Value("${spring.mail.username:}") String mailUsername,
            @Value("${edupath.email.from-name:知径 EduPath}") String fromName) {
        this.mailSender = mailSenderProvider.getIfAvailable();
        this.enabled = enabled;
        String configuredFrom = from == null ? "" : from.trim();
        String fallbackFrom = mailUsername == null ? "" : mailUsername.trim();
        this.from = configuredFrom.isBlank() ? fallbackFrom : configuredFrom;
        this.fromName = fromName == null || fromName.isBlank() ? "知径 EduPath" : fromName.trim();
    }

    public EmailDelivery sendRegistrationEmail(String to, String username) {
        if (!enabled) {
            return new EmailDelivery(false, "qq-smtp", "邮件服务未启用");
        }
        if (mailSender == null || from.isBlank()) {
            return new EmailDelivery(false, "qq-smtp", "邮件服务未完整配置");
        }
        try {
            MimeMessage message = mailSender.createMimeMessage();
            MimeMessageHelper helper = new MimeMessageHelper(message, StandardCharsets.UTF_8.name());
            helper.setFrom(new InternetAddress(from, fromName, StandardCharsets.UTF_8.name()));
            helper.setTo(to);
            helper.setSubject("知径 EduPath 注册成功");
            helper.setText(registrationText(username), false);
            mailSender.send(message);
            return new EmailDelivery(true, "qq-smtp", "注册邮件已发送");
        } catch (Exception exception) {
            return new EmailDelivery(false, "qq-smtp", "注册成功，但邮件发送失败: " + exception.getMessage());
        }
    }

    private String registrationText(String username) {
        return """
                你好，%s：

                你的知径 EduPath 学习账号已经创建成功。

                你现在可以登录系统，使用 ProfileAgent、PlannerAgent、ResourceAgent、TutorAgent 和 SafetyAgent 生成个性化学习路径、资源和答疑内容。

                如果这不是你本人操作，请忽略这封邮件。

                知径 EduPath
                """.formatted(username);
    }

    public record EmailDelivery(boolean sent, String provider, String message) {}
}
