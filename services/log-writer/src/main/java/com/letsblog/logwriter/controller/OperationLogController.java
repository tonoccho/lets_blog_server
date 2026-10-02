package com.letsblog.logwriter.controller;

import com.letsblog.logwriter.domain.OperationLog;
import com.letsblog.logwriter.dto.OperationLogRequest;
import com.letsblog.logwriter.dto.UnifiedLogEntryResponse;
import com.letsblog.logwriter.service.CurrentActorService;
import com.letsblog.logwriter.service.ForbiddenException;
import com.letsblog.logwriter.service.OperationLogService;
import com.letsblog.logwriter.service.UnifiedOperationLogService;
import java.time.LocalDateTime;
import java.util.List;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Web BFFが記録する操作ログ(デバッグ/サポート共有用のAPI呼び出しトレース)。
 * 一覧は常にログイン中の本人のログのみを対象とする。トレース({operationId})は本人、またはadminなら全利用者(issue #1471)。
 * /unified はAIジョブ・監査ログも合わせた統合ビュー向け(issue #187)。#572でlegacy-apiから移設。
 */
@RestController
@RequestMapping("/api/operation-logs")
public class OperationLogController {

    private final OperationLogService service;
    private final UnifiedOperationLogService unifiedOperationLogService;
    private final CurrentActorService currentActorService;

    public OperationLogController(
            OperationLogService service,
            UnifiedOperationLogService unifiedOperationLogService,
            CurrentActorService currentActorService) {
        this.service = service;
        this.unifiedOperationLogService = unifiedOperationLogService;
        this.currentActorService = currentActorService;
    }

    /**
     * 認可不要: クライアントが自分の操作を記録するための書き込み専用の窓口(issue #830)。
     * {@link FrontendErrorLogController#logError}と同じ扱いで、操作者はトークンから解決する。
     * 読み取り側({@code GET}/{@code /unified})には別途認可が掛かっている。
     */
    @PostMapping
    public ResponseEntity<Void> record(@RequestBody OperationLogRequest request) {
        Long userId = currentActorService.tryGetCurrentActorId();
        if (userId == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }
        service.record(request.toDomain(userId, currentActorService.getCurrentActorKeycloakSub()));
        return ResponseEntity.status(HttpStatus.CREATED).build();
    }

    @GetMapping
    public Page<OperationLog> list(Pageable pageable) {
        return service.findByUser(requireActorId(), pageable);
    }

    @GetMapping("/{operationId}")
    public List<OperationLog> trace(@PathVariable String operationId) {
        Long userId = requireActorId();
        // adminは集計画面から任意の利用者の操作を辿れる(issue #1471)。それ以外は従来どおり本人の行だけ。
        if (currentActorService.isAdmin()) {
            return service.findTraceAsAdmin(operationId);
        }
        return service.findTrace(userId, operationId);
    }

    @GetMapping("/unified")
    public Page<UnifiedLogEntryResponse> listUnified(
            @RequestParam(required = false) String type,
            @RequestParam(required = false) String q,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime startDate,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime endDate,
            Pageable pageable) {
        Long userId = requireActorId();
        return unifiedOperationLogService.list(
                userId, currentActorService.isAdmin(), type, q, startDate, endDate, pageable,
                currentActorService.getAuthorizationHeader());
    }

    private Long requireActorId() {
        Long userId = currentActorService.getCurrentActorId();
        if (userId == null) {
            throw new ForbiddenException("この操作にはログインが必要です");
        }
        return userId;
    }
}
