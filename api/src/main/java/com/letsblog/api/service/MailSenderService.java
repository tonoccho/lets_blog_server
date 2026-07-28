package com.letsblog.api.service;

import jakarta.mail.internet.MimeMessage;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Service;

import java.util.Map;

@Service
@Slf4j
public class MailSenderService {

    private final JavaMailSender mailSender;
    private final MailTemplateService mailTemplateService;

    @Value("${app.mail.from:noreply@letsblog.example.com}")
    private String fromEmail;

    public MailSenderService(
            JavaMailSender mailSender,
            MailTemplateService mailTemplateService) {
        this.mailSender = mailSender;
        this.mailTemplateService = mailTemplateService;
    }

    /**
     * テンプレートを使用してメールを送信する。
     */
    public void sendMail(String to, String templateKey, Map<String, Object> variables) {
        MailTemplateService.MailContent content = mailTemplateService.renderTemplate(templateKey, variables);
        sendHtmlMail(to, content.subject(), content.bodyHtml());
    }

    /**
     * HTML形式のメールを送信する。
     */
    public void sendHtmlMail(String to, String subject, String htmlBody) {
        try {
            MimeMessage message = mailSender.createMimeMessage();
            MimeMessageHelper helper = new MimeMessageHelper(message, true, "UTF-8");

            helper.setFrom(fromEmail);
            helper.setTo(to);
            helper.setSubject(subject);
            helper.setText(htmlBody, true);

            mailSender.send(message);
            log.info("HTML mail sent to: {} (subject: {})", to, subject);
        } catch (Exception e) {
            // MimeMessageHelperの各setterはjakarta.mail.MessagingException(検査例外)を、
            // mailSender.send()はorg.springframework.mail.MailException(非検査例外)を投げうるため、
            // どちらも一括してEmailSendExceptionに変換する。
            log.error("Failed to send HTML mail to {}: {}", to, e.getMessage());
            throw new EmailSendException("メール送信に失敗しました", e);
        }
    }
}
