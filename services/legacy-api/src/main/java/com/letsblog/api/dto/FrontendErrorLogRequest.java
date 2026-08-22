package com.letsblog.api.dto;

import com.letsblog.api.domain.FrontendErrorLog;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

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
    public FrontendErrorLog toDomain() {
        FrontendErrorLog log = new FrontendErrorLog();
        log.setMessage(message);
        log.setStack(stack);
        log.setComponentStack(componentStack);
        log.setLevel(FrontendErrorLog.ErrorLevel.valueOf(level.toUpperCase()));
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
}
