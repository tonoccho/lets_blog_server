package com.letsblog.logwriter.service;

import com.letsblog.logwriter.dto.OperationStat;
import com.letsblog.logwriter.dto.RouteDurationRow;
import com.letsblog.logwriter.dto.RouteStat;
import com.letsblog.logwriter.repository.OperationLogRepository;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 記録済みの操作ログ(operation_logs.duration_ms)の集計(issue #1471)。新しいテーブル・バッチ・
 * キャッシュは持たず、期間で絞った行をその場で集計する。パスの正規化は集計時に行い、記録側は変えない。
 *
 * <p>正規化をSQL({@code REGEXP_REPLACE})ではなくJavaで行うのは、p50/p95(nearest-rank)が
 * 正規化後のグループごとの全件を要するため。DBからはmethod/path/duration_msの3列だけを取る。
 */
@Service
public class OperationLogStatsService {

    static final int DEFAULT_LIMIT = 100;
    static final int MAX_LIMIT = 1000;

    private static final Pattern NUMERIC_SEGMENT = Pattern.compile("\\d+");
    private static final Pattern UUID_SEGMENT = Pattern.compile(
            "[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}");

    private final OperationLogRepository repository;

    public OperationLogStatsService(OperationLogRepository repository) {
        this.repository = repository;
    }

    @Transactional(readOnly = true)
    public List<RouteStat> routeStats(
            LocalDateTime start, LocalDateTime end, String sort, String direction, Integer limit) {
        validatePeriod(start, end);
        String sortKey = sort == null ? "p95" : sort;
        Comparator<RouteStat> primary = switch (sortKey) {
            case "count" -> Comparator.comparingLong(RouteStat::count);
            case "p50" -> Comparator.comparingLong(RouteStat::p50Ms);
            case "p95" -> Comparator.comparingLong(RouteStat::p95Ms);
            case "max" -> Comparator.comparingLong(RouteStat::maxMs);
            default -> throw new IllegalArgumentException("sortはcount/p50/p95/maxのいずれかです: " + sort);
        };
        boolean descending = isDescending(direction);
        int max = resolveLimit(limit);

        Map<String, List<Long>> grouped = new LinkedHashMap<>();
        Map<String, String[]> keys = new LinkedHashMap<>();
        for (RouteDurationRow row : repository.findRouteDurations(start, end)) {
            String path = normalizePath(row.path());
            String key = row.method() + " " + path;
            grouped.computeIfAbsent(key, k -> new ArrayList<>()).add(row.durationMs());
            keys.putIfAbsent(key, new String[] {row.method(), path});
        }

        List<RouteStat> stats = new ArrayList<>();
        grouped.forEach((key, durations) -> {
            durations.sort(Comparator.naturalOrder());
            String[] mp = keys.get(key);
            stats.add(new RouteStat(
                    mp[0], mp[1], durations.size(),
                    nearestRank(durations, 50), nearestRank(durations, 95), durations.get(durations.size() - 1)));
        });

        Comparator<RouteStat> ordered = descending ? primary.reversed() : primary;
        ordered = ordered
                .thenComparing(Comparator.comparingLong(RouteStat::count).reversed())
                .thenComparing(RouteStat::method)
                .thenComparing(RouteStat::path);
        return stats.stream().sorted(ordered).limit(max).toList();
    }

    @Transactional(readOnly = true)
    public List<OperationStat> operationStats(
            LocalDateTime start, LocalDateTime end, String sort, String direction, Integer limit) {
        validatePeriod(start, end);
        String sortKey = sort == null ? "totalDuration" : sort;
        Comparator<OperationStat> primary = switch (sortKey) {
            case "totalDuration" -> Comparator.comparingLong(OperationStat::totalDurationMs);
            case "callCount" -> Comparator.comparingLong(OperationStat::callCount);
            case "startedAt" -> Comparator.comparing(OperationStat::startedAt);
            default -> throw new IllegalArgumentException(
                    "sortはtotalDuration/callCount/startedAtのいずれかです: " + sort);
        };
        boolean descending = isDescending(direction);
        int max = resolveLimit(limit);

        Comparator<OperationStat> ordered = descending ? primary.reversed() : primary;
        ordered = ordered
                .thenComparing(Comparator.comparing(OperationStat::startedAt).reversed())
                .thenComparing(OperationStat::operationId);
        return repository.aggregateOperations(start, end).stream().sorted(ordered).limit(max).toList();
    }

    /** 数値だけのセグメントとUUIDのセグメントを{@code {id}}に置き換え、クエリ文字列を除く。 */
    static String normalizePath(String path) {
        if (path == null) {
            return "";
        }
        int query = path.indexOf('?');
        String bare = query >= 0 ? path.substring(0, query) : path;
        String[] segments = bare.split("/", -1);
        for (int i = 0; i < segments.length; i++) {
            if (NUMERIC_SEGMENT.matcher(segments[i]).matches() || UUID_SEGMENT.matcher(segments[i]).matches()) {
                segments[i] = "{id}";
            }
        }
        return String.join("/", segments);
    }

    /** nearest-rank法: 昇順に並べた⌈p% × 件数⌉番目。整数演算で浮動小数点誤差を避ける。 */
    static long nearestRank(List<Long> sortedAscending, int percentile) {
        int n = sortedAscending.size();
        int rank = (percentile * n + 99) / 100;
        return sortedAscending.get(Math.max(rank, 1) - 1);
    }

    private static void validatePeriod(LocalDateTime start, LocalDateTime end) {
        if (start == null || end == null) {
            throw new IllegalArgumentException("startDateとendDateは必須です");
        }
        if (start.isAfter(end)) {
            throw new IllegalArgumentException("startDateはendDate以前にしてください");
        }
    }

    private static boolean isDescending(String direction) {
        if (direction == null || direction.equals("desc")) {
            return true;
        }
        if (direction.equals("asc")) {
            return false;
        }
        throw new IllegalArgumentException("directionはasc/descのいずれかです: " + direction);
    }

    private static int resolveLimit(Integer limit) {
        if (limit == null) {
            return DEFAULT_LIMIT;
        }
        if (limit < 1 || limit > MAX_LIMIT) {
            throw new IllegalArgumentException("limitは1以上" + MAX_LIMIT + "以下にしてください");
        }
        return limit;
    }
}
