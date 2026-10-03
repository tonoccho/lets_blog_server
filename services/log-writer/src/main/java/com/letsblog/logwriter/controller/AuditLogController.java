package com.letsblog.logwriter.controller;

import com.letsblog.logwriter.dto.AuditLogResponse;
import com.letsblog.logwriter.service.AdminAuthorizationService;
import com.letsblog.logwriter.service.AuditLogService;
import java.time.LocalDateTime;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 監査ログの読み取りAPI(#572でlegacy-apiから移設)。admin限定。
 */
@RestController
@RequestMapping("/api/audit-logs")
public class AuditLogController {

    private final AuditLogService auditLogService;
    private final AdminAuthorizationService adminAuthorizationService;

    public AuditLogController(AuditLogService auditLogService, AdminAuthorizationService adminAuthorizationService) {
        this.auditLogService = auditLogService;
        this.adminAuthorizationService = adminAuthorizationService;
    }

    @GetMapping
    public Page<AuditLogResponse> list(
            @RequestParam(required = false) Long userId,
            @RequestParam(required = false) String action,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime startDate,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime endDate,
            Pageable pageable) {

        adminAuthorizationService.requireAdmin();

        if (userId != null) {
            return auditLogService.findByUserId(userId, pageable).map(AuditLogResponse::from);
        }
        if (action != null) {
            return auditLogService.findByAction(action, pageable).map(AuditLogResponse::from);
        }
        if (startDate != null && endDate != null) {
            return auditLogService.findByDateRange(startDate, endDate, pageable).map(AuditLogResponse::from);
        }
        return auditLogService.findAll(pageable).map(AuditLogResponse::from);
    }
}
