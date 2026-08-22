package com.letsblog.api.controller;

import com.letsblog.api.domain.AuditLog;
import com.letsblog.api.domain.AuditLogAction;
import com.letsblog.api.service.AdminAuthorizationService;
import com.letsblog.api.service.AuditLogService;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDateTime;

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
    public Page<AuditLog> list(
            @RequestParam(required = false) Long userId,
            @RequestParam(required = false) AuditLogAction action,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime startDate,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime endDate,
            Pageable pageable) {

        adminAuthorizationService.requireAdmin();

        if (userId != null) {
            return auditLogService.findByUserId(userId, pageable);
        }
        if (action != null) {
            return auditLogService.findByAction(action, pageable);
        }
        if (startDate != null && endDate != null) {
            return auditLogService.findByDateRange(startDate, endDate, pageable);
        }
        return auditLogService.findAll(pageable);
    }
}
