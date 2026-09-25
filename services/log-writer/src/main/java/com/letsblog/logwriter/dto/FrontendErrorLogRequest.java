package com.letsblog.logwriter.dto;

import com.letsblog.logwriter.domain.FrontendErrorLog;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.Set;

/**
 * {@code message}の検証(issue #1059)。DB列({@code frontend_error_logs.message}、
 * V1__create_log_tables.sql)がMySQLの{@code TEXT}かつ{@code NOT NULL}のため、それぞれに対応する
 * 制約を入口に付ける。{@code TEXT}の実容量上限は65,535バイトだが、UTF-8のマルチバイト文字を
 * 考慮せず文字数でその値をそのまま上限にする(バイト数で厳密に一致させる必要はなく、
 * 「実質的にTEXT列に収まる」ことを保証する趣旨のため)。
 */
public record FrontendErrorLogRequest(
        @NotBlank(message = "messageは必須です")
        @Size(max = 65535, message = "messageは65535文字以内である必要があります")
        String message,
        String stack,
        String componentStack,
        String level,
        Object context,
        String url,
        String userAgent,
        String timestamp
) {
    private static final Set<String> VALID_LEVELS = Set.of("ERROR", "WARN");

    public FrontendErrorLog toDomain() {
        FrontendErrorLog log = new FrontendErrorLog();
        log.setMessage(message);
        log.setStack(stack);
        log.setComponentStack(componentStack);
        log.setLevel(normalizeLevel(level));
        log.setContext(context != null ? context.toString() : null);
        log.setUrl(url);
        log.setUserAgent(userAgent);

        if (timestamp != null) {
            try {
                log.setTimestamp(LocalDateTime.parse(timestamp, DateTimeFormatter.ISO_DATE_TIME));
            } catch (Exception e) {
                log.setTimestamp(LocalDateTime.now());
            }
        } else {
            log.setTimestamp(LocalDateTime.now());
        }

        return log;
    }

    private static String normalizeLevel(String level) {
        String normalized = level != null ? level.toUpperCase(Locale.ROOT) : null;
        if (!VALID_LEVELS.contains(normalized)) {
            throw new IllegalArgumentException("levelはERRORまたはWARNである必要があります: " + level);
        }
        return normalized;
    }
}
