package com.letsblog.api.controller;

import com.letsblog.api.domain.OperationLog;
import com.letsblog.api.dto.OperationLogRequest;
import com.letsblog.api.service.CurrentActorService;
import com.letsblog.api.service.ForbiddenException;
import com.letsblog.api.service.OperationLogService;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * Web BFFが記録する操作ログ(デバッグ/サポート共有用のAPI呼び出しトレース)。
 * 常にログイン中の本人のログのみを対象とする(他ユーザーのログは参照不可)。
 */
@RestController
@RequestMapping("/api/operation-logs")
public class OperationLogController {

    private final OperationLogService service;
    private final CurrentActorService currentActorService;

    public OperationLogController(OperationLogService service, CurrentActorService currentActorService) {
        this.service = service;
        this.currentActorService = currentActorService;
    }

    @PostMapping
    public ResponseEntity<Void> record(@RequestBody OperationLogRequest request) {
        Long userId = currentActorService.getCurrentActorId();
        if (userId == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }
        service.record(request.toDomain(userId));
        return ResponseEntity.status(HttpStatus.CREATED).build();
    }

    @GetMapping
    public Page<OperationLog> list(Pageable pageable) {
        return service.findByUser(requireActorId(), pageable);
    }

    @GetMapping("/{operationId}")
    public List<OperationLog> trace(@PathVariable String operationId) {
        return service.findTrace(requireActorId(), operationId);
    }

    private Long requireActorId() {
        Long userId = currentActorService.getCurrentActorId();
        if (userId == null) {
            throw new ForbiddenException("この操作にはログインが必要です");
        }
        return userId;
    }
}
