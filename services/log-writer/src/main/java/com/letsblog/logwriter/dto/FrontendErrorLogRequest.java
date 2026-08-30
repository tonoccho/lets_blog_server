package com.letsblog.logwriter.dto;

import com.letsblog.logwriter.domain.FrontendErrorLog;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.Set;

public record FrontendErrorLogRequest(
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
