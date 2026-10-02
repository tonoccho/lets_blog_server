package com.letsblog.logwriter.service;

import com.letsblog.logwriter.dto.OperationStat;
import com.letsblog.logwriter.dto.RouteDurationRow;
import com.letsblog.logwriter.dto.RouteStat;
import com.letsblog.logwriter.repository.OperationLogRepository;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.IntStream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.when;

/** issue #1471: 操作ログの集計(ルート別・操作別)。 */
@ExtendWith(MockitoExtension.class)
class OperationLogStatsServiceTest {

    private static final LocalDateTime START = LocalDateTime.of(2026, 10, 1, 0, 0);
    private static final LocalDateTime END = LocalDateTime.of(2026, 10, 2, 0, 0);
    private static final String UUID_A = "123e4567-e89b-12d3-a456-426614174000";

    @Mock
    private OperationLogRepository repository;

    private OperationLogStatsService service;

    @BeforeEach
    void setUp() {
        service = new OperationLogStatsService(repository);
    }

    private static RouteDurationRow row(String method, String path, long ms) {
        return new RouteDurationRow(method, path, ms);
    }

    private static List<RouteDurationRow> rows(String method, String path, long... durations) {
        List<RouteDurationRow> list = new ArrayList<>();
        for (long d : durations) {
            list.add(row(method, path, d));
        }
        return list;
    }

    private static OperationStat op(String id, long total, long calls, int minute, Long userId) {
        return new OperationStat(id, total, calls, START.plusMinutes(minute), userId);
    }

    // ---- normalizePath ----

    @Test
    void normalizePath_数値セグメントとクエリを正規化する() {
        assertEquals("/api/projects/{id}/posts", OperationLogStatsService.normalizePath("/api/projects/12/posts?page=2"));
        assertEquals("/api/projects/{id}/posts", OperationLogStatsService.normalizePath("/api/projects/34/posts"));
    }

    @Test
    void normalizePath_UUIDセグメントを大文字小文字を問わず置換する() {
        assertEquals("/api/ai/jobs/{id}", OperationLogStatsService.normalizePath("/api/ai/jobs/" + UUID_A));
        assertEquals("/api/ai/jobs/{id}", OperationLogStatsService.normalizePath("/api/ai/jobs/" + UUID_A.toUpperCase()));
    }

    @Test
    void normalizePath_数字を含むだけのセグメントや通常のパスは変えない() {
        assertEquals("/api/v2/sites/abc123/12ab", OperationLogStatsService.normalizePath("/api/v2/sites/abc123/12ab"));
        assertEquals("/", OperationLogStatsService.normalizePath("/"));
        assertEquals("/api/sites", OperationLogStatsService.normalizePath("/api/sites?x=1"));
        assertEquals("", OperationLogStatsService.normalizePath(null));
    }

    @Test
    void normalizePath_複数の数値セグメントを全て置換する() {
        assertEquals("/api/projects/{id}/posts/{id}", OperationLogStatsService.normalizePath("/api/projects/1/posts/22"));
    }

    // ---- nearestRank ----

    @Test
    void nearestRank_昇順のceil_p_n_番目を返す() {
        List<Long> sorted = List.of(10L, 20L, 30L, 40L);
        assertEquals(20L, OperationLogStatsService.nearestRank(sorted, 50));
        assertEquals(40L, OperationLogStatsService.nearestRank(sorted, 95));
    }

    @Test
    void nearestRank_20件のp95は19番目で浮動小数点誤差に左右されない() {
        List<Long> sorted = IntStream.rangeClosed(1, 20).mapToObj(Long::valueOf).toList();
        assertEquals(19L, OperationLogStatsService.nearestRank(sorted, 95));
        assertEquals(10L, OperationLogStatsService.nearestRank(sorted, 50));
    }

    @Test
    void nearestRank_1件ならその値() {
        assertEquals(7L, OperationLogStatsService.nearestRank(List.of(7L), 50));
        assertEquals(7L, OperationLogStatsService.nearestRank(List.of(7L), 95));
    }

    // ---- routeStats ----

    @Test
    void routeStats_正規化したルートごとに件数とp50とp95と最大を返す() {
        List<RouteDurationRow> all = new ArrayList<>();
        all.addAll(rows("GET", "/api/projects/12/posts?page=2", 40, 10));
        all.addAll(rows("GET", "/api/projects/34/posts", 30, 20));
        when(repository.findRouteDurations(START, END)).thenReturn(all);

        List<RouteStat> result = service.routeStats(START, END, null, null, null);

        assertEquals(1, result.size());
        RouteStat stat = result.get(0);
        assertEquals("GET", stat.method());
        assertEquals("/api/projects/{id}/posts", stat.path());
        assertEquals(4, stat.count());
        assertEquals(20L, stat.p50Ms());
        assertEquals(40L, stat.p95Ms());
        assertEquals(40L, stat.maxMs());
    }

    @Test
    void routeStats_methodが違えば別の行になる() {
        List<RouteDurationRow> all = new ArrayList<>();
        all.addAll(rows("GET", "/api/sites", 10));
        all.addAll(rows("POST", "/api/sites", 20));
        when(repository.findRouteDurations(START, END)).thenReturn(all);

        assertEquals(2, service.routeStats(START, END, null, null, null).size());
    }

    private void stubThreeRoutes() {
        List<RouteDurationRow> all = new ArrayList<>();
        all.addAll(rows("GET", "/a", 100));          // p95=100 count=1
        all.addAll(rows("GET", "/b", 100, 100));     // p95=100 count=2
        all.addAll(rows("GET", "/c", 300));          // p95=300 count=1
        all.addAll(rows("GET", "/d", 5, 5, 5));      // p95=5   count=3
        when(repository.findRouteDurations(START, END)).thenReturn(all);
    }

    @Test
    void routeStats_既定はp95降順で同値は件数降順その次にmethodとパス昇順() {
        stubThreeRoutes();

        List<String> paths = service.routeStats(START, END, null, null, null).stream().map(RouteStat::path).toList();

        assertEquals(List.of("/c", "/b", "/a", "/d"), paths);
    }

    @Test
    void routeStats_同値で件数も同じならmethodとパスの昇順() {
        List<RouteDurationRow> all = new ArrayList<>();
        all.addAll(rows("POST", "/x", 10));
        all.addAll(rows("GET", "/z", 10));
        all.addAll(rows("GET", "/y", 10));
        when(repository.findRouteDurations(START, END)).thenReturn(all);

        List<String> keys = service.routeStats(START, END, "p95", "desc", null).stream()
                .map(s -> s.method() + " " + s.path()).toList();

        assertEquals(List.of("GET /y", "GET /z", "POST /x"), keys);
    }

    @Test
    void routeStats_件数で昇順に並べ替えられる() {
        stubThreeRoutes();

        List<String> paths = service.routeStats(START, END, "count", "asc", null).stream().map(RouteStat::path).toList();

        assertEquals(List.of("/a", "/c", "/b", "/d"), paths);
    }

    @Test
    void routeStats_p50と最大でも並べ替えられる() {
        List<RouteDurationRow> all = new ArrayList<>();
        all.addAll(rows("GET", "/low-p50-high-max", 1, 1, 1, 900));  // p50=1 max=900
        all.addAll(rows("GET", "/high-p50-low-max", 50, 50, 50, 60)); // p50=50 max=60
        when(repository.findRouteDurations(START, END)).thenReturn(all);

        assertEquals("/high-p50-low-max", service.routeStats(START, END, "p50", "desc", null).get(0).path());
        assertEquals("/low-p50-high-max", service.routeStats(START, END, "max", "desc", null).get(0).path());
        assertEquals("/low-p50-high-max", service.routeStats(START, END, "p50", "asc", null).get(0).path());
    }

    @Test
    void routeStats_上限で切る前に指定した列で並べる() {
        stubThreeRoutes();

        List<RouteStat> result = service.routeStats(START, END, "count", "desc", 1);

        assertEquals(1, result.size());
        assertEquals("/d", result.get(0).path());
    }

    @Test
    void routeStats_既定の上限は100行() {
        List<RouteDurationRow> all = new ArrayList<>();
        for (int i = 0; i < 150; i++) {
            all.add(row("GET", "/r" + i, i));
        }
        when(repository.findRouteDurations(START, END)).thenReturn(all);

        assertEquals(100, service.routeStats(START, END, null, null, null).size());
    }

    @Test
    void routeStats_不正な指定は拒否する() {
        assertThrows(IllegalArgumentException.class, () -> service.routeStats(START, END, "bogus", null, null));
        assertThrows(IllegalArgumentException.class, () -> service.routeStats(START, END, null, "sideways", null));
        assertThrows(IllegalArgumentException.class, () -> service.routeStats(START, END, null, null, 0));
        assertThrows(IllegalArgumentException.class, () -> service.routeStats(START, END, null, null, 1001));
        assertThrows(IllegalArgumentException.class, () -> service.routeStats(END, START, null, null, null));
        assertThrows(IllegalArgumentException.class, () -> service.routeStats(null, END, null, null, null));
        assertThrows(IllegalArgumentException.class, () -> service.routeStats(START, null, null, null, null));
    }

    // ---- operationStats ----

    private void stubOperations() {
        when(repository.aggregateOperations(START, END)).thenReturn(List.of(
                op("op-b", 500, 2, 5, 1L),
                op("op-a", 500, 3, 5, 2L),
                op("op-c", 500, 1, 9, 3L),
                op("op-d", 900, 4, 1, 1L),
                op("op-e", 100, 9, 2, null)));
    }

    @Test
    void operationStats_既定は合計降順で同値は開始時刻の新しい順その次にoperationId昇順() {
        stubOperations();

        List<String> ids = service.operationStats(START, END, null, null, null).stream()
                .map(OperationStat::operationId).toList();

        assertEquals(List.of("op-d", "op-c", "op-a", "op-b", "op-e"), ids);
    }

    @Test
    void operationStats_呼び出し数と開始時刻でも並べ替えられる() {
        stubOperations();

        assertEquals("op-e", service.operationStats(START, END, "callCount", "desc", null).get(0).operationId());
        assertEquals("op-d", service.operationStats(START, END, "startedAt", "asc", null).get(0).operationId());
        assertEquals("op-c", service.operationStats(START, END, "startedAt", "desc", null).get(0).operationId());
        assertEquals("op-e", service.operationStats(START, END, "totalDuration", "asc", null).get(0).operationId());
    }

    @Test
    void operationStats_上限で切る前に並べる() {
        stubOperations();

        List<OperationStat> result = service.operationStats(START, END, null, null, 2);

        assertEquals(List.of("op-d", "op-c"), result.stream().map(OperationStat::operationId).toList());
    }

    @Test
    void operationStats_既定の上限は100行() {
        List<OperationStat> all = new ArrayList<>();
        for (int i = 0; i < 130; i++) {
            all.add(op("op-" + i, i, 1, 0, 1L));
        }
        when(repository.aggregateOperations(START, END)).thenReturn(all);

        assertEquals(100, service.operationStats(START, END, null, null, null).size());
    }

    @Test
    void operationStats_不正な指定は拒否する() {
        assertThrows(IllegalArgumentException.class, () -> service.operationStats(START, END, "bogus", null, null));
        assertThrows(IllegalArgumentException.class, () -> service.operationStats(START, END, null, "up", null));
        assertThrows(IllegalArgumentException.class, () -> service.operationStats(START, END, null, null, -1));
        assertThrows(IllegalArgumentException.class, () -> service.operationStats(END, START, null, null, null));
        assertThrows(IllegalArgumentException.class, () -> service.operationStats(null, null, null, null, null));
    }
}
