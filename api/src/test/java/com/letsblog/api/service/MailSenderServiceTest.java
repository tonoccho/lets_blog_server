package com.letsblog.api.service;

import jakarta.mail.internet.MimeMessage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.boot.mail.autoconfigure.MailProperties;
import org.springframework.mail.javamail.JavaMailSenderImpl;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * MailSenderServiceの回帰テスト。接続設定(ホスト/ポート等)と送信元アドレスはAppSettingServiceから
 * 呼び出しの都度取得する(issue #403)ため、内部で構築されるJavaMailSenderImplをMockito spyで
 * モックに差し替えて検証する。
 */
@ExtendWith(MockitoExtension.class)
class MailSenderServiceTest {

    @Mock
    private MailTemplateService mailTemplateService;
    @Mock
    private AppSettingService appSettingService;
    @Mock
    private JavaMailSenderImpl mailSender;

    private MailSenderService service;

    @BeforeEach
    void setUp() {
        service = spy(new MailSenderService(mailTemplateService, appSettingService, new MailProperties()));
        org.mockito.Mockito.lenient().doReturn(mailSender).when(service).buildMailSender();
        org.mockito.Mockito.lenient().when(appSettingService.getMailFrom()).thenReturn("noreply@letsblog.example.com");
    }

    @Test
    void sendMail_テンプレートをレンダリングしてHTMLメールを送信する() {
        MimeMessage mimeMessage = mock(MimeMessage.class);
        when(mailSender.createMimeMessage()).thenReturn(mimeMessage);
        when(mailTemplateService.renderTemplate("password-reset", Map.of("resetLink", "https://example.com")))
                .thenReturn(new MailTemplateService.MailContent("件名", "<p>本文</p>"));

        service.sendMail("user@example.com", "password-reset", Map.of("resetLink", "https://example.com"));

        verify(mailSender, times(1)).send(mimeMessage);
    }

    @Test
    void sendHtmlMail_送信失敗時はEmailSendExceptionを投げる() {
        MimeMessage mimeMessage = mock(MimeMessage.class);
        when(mailSender.createMimeMessage()).thenReturn(mimeMessage);
        doThrow(new org.springframework.mail.MailSendException("smtp down"))
                .when(mailSender).send(any(MimeMessage.class));

        assertThrows(EmailSendException.class,
                () -> service.sendHtmlMail("user@example.com", "件名", "<p>本文</p>"));
    }

    @Test
    void sendMail_テンプレートが見つからない場合は例外がそのまま伝播しメールは送信されない() {
        when(mailTemplateService.renderTemplate("unknown", Map.of()))
                .thenThrow(new MailTemplateNotFoundException("テンプレート 'unknown' が見つかりません"));

        assertThrows(MailTemplateNotFoundException.class,
                () -> service.sendMail("user@example.com", "unknown", Map.of()));

        verify(mailSender, never()).send(any(MimeMessage.class));
    }
}
