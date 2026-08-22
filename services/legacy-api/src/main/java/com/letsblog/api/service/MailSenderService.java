package com.letsblog.api.service;

import jakarta.mail.internet.MimeMessage;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.mail.autoconfigure.MailProperties;
import org.springframework.mail.javamail.JavaMailSenderImpl;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.Properties;

/**
 * メール送信を行う。接続設定(ホスト/ポート/ユーザー名/パスワード)と送信元アドレスは
 * AppSettingServiceから呼び出しの都度取得する(issue #403でWeb管理画面のシステム設定から変更可能に
 * なったため、Spring Bootが自動構成する単一のJavaMailSenderBeanを固定で使い続けるのではなく、
 * 送信の都度JavaMailSenderImplを構築する)。auth/starttls等のその他のSMTPプロパティは
 * MailProperties(spring.mail.properties.*、環境変数由来)からそのまま引き継ぐ。
 */
@Service
@Slf4j
public class MailSenderService {

    private final MailTemplateService mailTemplateService;
    private final AppSettingService appSettingService;
    private final MailProperties mailProperties;

    public MailSenderService(
            MailTemplateService mailTemplateService,
            AppSettingService appSettingService,
            MailProperties mailProperties) {
        this.mailTemplateService = mailTemplateService;
        this.appSettingService = appSettingService;
        this.mailProperties = mailProperties;
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
            JavaMailSenderImpl mailSender = buildMailSender();
            MimeMessage message = mailSender.createMimeMessage();
            MimeMessageHelper helper = new MimeMessageHelper(message, true, "UTF-8");

            helper.setFrom(appSettingService.getMailFrom());
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

    /** package-privateはテストからモックに差し替えられるようにするため(Mockito spyでオーバーライド)。 */
    JavaMailSenderImpl buildMailSender() {
        JavaMailSenderImpl mailSender = new JavaMailSenderImpl();
        mailSender.setHost(appSettingService.getMailHost());
        mailSender.setPort(appSettingService.getMailPort());
        mailSender.setUsername(appSettingService.getMailUsername());
        mailSender.setPassword(appSettingService.getMailPassword());
        mailSender.setDefaultEncoding("UTF-8");
        Properties properties = new Properties();
        properties.putAll(mailProperties.getProperties());
        mailSender.setJavaMailProperties(properties);
        return mailSender;
    }
}
