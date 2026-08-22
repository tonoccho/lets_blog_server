package com.letsblog.api.controller;

import com.letsblog.api.domain.FrontendErrorLog;
import com.letsblog.api.dto.FrontendErrorLogRequest;
import com.letsblog.api.service.AdminAuthorizationService;
import com.letsblog.api.service.FrontendErrorLogService;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;

@RestController
@RequestMapping("/api/logs")
public class FrontendErrorLogController {

    private final FrontendErrorLogService service;
    private final AdminAuthorizationService adminAuthorizationService;

    public FrontendErrorLogController(FrontendErrorLogService service, AdminAuthorizationService adminAuthorizationService) {
        this.service = service;
        this.adminAuthorizationService = adminAuthorizationService;
    }

    @PostMapping("/errors")
    public ResponseEntity<Void> logError(@RequestBody FrontendErrorLogRequest request) {
        FrontendErrorLog errorLog = request.toDomain();
        service.logError(errorLog);
        return ResponseEntity.status(HttpStatus.CREATED).build();
    }

    @GetMapping("/errors")
    public Page<FrontendErrorLog> getErrors(
            @RequestParam(required = false) FrontendErrorLog.ErrorLevel level,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime startDate,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime endDate,
            @RequestParam(required = false) String url,
            Pageable pageable) {

        adminAuthorizationService.requireAdmin();

        if (level != null) {
            return service.findByLevel(level, pageable);
        }
        if (startDate != null && endDate != null) {
            return service.findByDateRange(startDate, endDate, pageable);
        }
        if (url != null) {
            return service.findByUrl(url, pageable);
        }
        return service.findAll(pageable);
    }
}
