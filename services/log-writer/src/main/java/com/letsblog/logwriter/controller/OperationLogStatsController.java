package com.letsblog.logwriter.controller;

import com.letsblog.logwriter.dto.OperationStat;
import com.letsblog.logwriter.dto.RouteStat;
import com.letsblog.logwriter.service.AdminAuthorizationService;
import com.letsblog.logwriter.service.OperationLogStatsService;
import java.time.LocalDateTime;
import java.util.List;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 操作ログの集計API(issue #1471)。admin限定。期間(startDate/endDate)は必須で、
 * ルート別(p50/p95/最大)と操作別(合計所要時間)を遅い順に返す。
 * パスは{@code /{operationId}}と衝突しない{@code /stats/...}の2セグメント。
 */
@RestController
@RequestMapping("/api/operation-logs/stats")
public class OperationLogStatsController {

    private final OperationLogStatsService service;
    private final AdminAuthorizationService adminAuthorizationService;

    public OperationLogStatsController(
            OperationLogStatsService service, AdminAuthorizationService adminAuthorizationService) {
        this.service = service;
        this.adminAuthorizationService = adminAuthorizationService;
    }

    @GetMapping("/routes")
    public List<RouteStat> routes(
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime startDate,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime endDate,
            @RequestParam(required = false) String sort,
            @RequestParam(required = false) String direction,
            @RequestParam(required = false) Integer limit) {
        adminAuthorizationService.requireAdmin();
        return service.routeStats(startDate, endDate, sort, direction, limit);
    }

    @GetMapping("/operations")
    public List<OperationStat> operations(
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime startDate,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime endDate,
            @RequestParam(required = false) String sort,
            @RequestParam(required = false) String direction,
            @RequestParam(required = false) Integer limit) {
        adminAuthorizationService.requireAdmin();
        return service.operationStats(startDate, endDate, sort, direction, limit);
    }
}
