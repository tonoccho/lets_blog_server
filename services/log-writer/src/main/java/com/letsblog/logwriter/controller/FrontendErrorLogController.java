package com.letsblog.logwriter.controller;

import com.letsblog.logwriter.domain.FrontendErrorLog;
import com.letsblog.logwriter.dto.FrontendErrorLogRequest;
import com.letsblog.logwriter.service.AdminAuthorizationService;
import com.letsblog.logwriter.service.FrontendErrorLogService;
import jakarta.validation.Valid;
import java.time.LocalDateTime;
import java.util.Locale;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * フロントエンドエラーログの記録・読み取りAPI(#572でlegacy-apiから移設)。読み取りはadmin限定。
 */
@RestController
@RequestMapping("/api/logs")
public class FrontendErrorLogController {

    private final FrontendErrorLogService service;
    private final AdminAuthorizationService adminAuthorizationService;

    public FrontendErrorLogController(
            FrontendErrorLogService service, AdminAuthorizationService adminAuthorizationService) {
        this.service = service;
        this.adminAuthorizationService = adminAuthorizationService;
    }

    /**
     * 認可不要: クライアント(Web/VSCode拡張)が自分で遭遇したエラーを送ってくる書き込み専用の窓口
     * (issue #830)。他人のデータを読み書きするものではなく、操作者は記録側でトークンから解決する。
     * 読み取り側({@code GET /errors})には別途認可が掛かっている。
     */
    @PostMapping("/errors")
    public ResponseEntity<Void> logError(@Valid @RequestBody FrontendErrorLogRequest request) {
        FrontendErrorLog errorLog = request.toDomain();
        service.logError(errorLog);
        return ResponseEntity.status(HttpStatus.CREATED).build();
    }

    @GetMapping("/errors")
    public Page<FrontendErrorLog> getErrors(
            @RequestParam(required = false) String level,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime startDate,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime endDate,
            @RequestParam(required = false) String url,
            Pageable pageable) {

        adminAuthorizationService.requireAdmin();

        if (level != null) {
            return service.findByLevel(level.toUpperCase(Locale.ROOT), pageable);
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
