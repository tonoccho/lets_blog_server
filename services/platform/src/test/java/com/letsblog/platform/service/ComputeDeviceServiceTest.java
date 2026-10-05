package com.letsblog.platform.service;

import com.letsblog.platform.dto.ComputeDevice;
import com.letsblog.platform.dto.ComputeDeviceStatusResponse;
import com.letsblog.platform.dto.ComputeDeviceStatusResponse.ApplyState;
import com.letsblog.platform.dto.ComputeDeviceStatusResponse.CurrentDevice;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * ComfyUIの演算デバイス(GPU/CPU)切り替え(issue #1399)。Docker Engine APIと疎通確認は
 * フェイクに差し替え、時計も進めて、成功判定・上限時間・失敗時の復帰・排他を決定的に検証する。
 */
class ComputeDeviceServiceTest {

    private static final String GPU = "lbs-comfyui";
    private static final String CPU = "lbs-comfyui-cpu";
    private static final String HEALTH_URL = "http://comfyui.test:8188/system_stats";
    private static final Duration TIMEOUT = Duration.ofSeconds(30);
    private static final Duration POLL = Duration.ofSeconds(2);

    /** 呼び出し順を記録するフェイクのDocker Engine API。 */
    static class FakeDocker implements DockerEngineClient {
        final Map<String, String> states = new LinkedHashMap<>();
        final List<String> calls = new ArrayList<>();
        final Set<String> rejectStart = new HashSet<>();
        final Set<String> rejectStop = new HashSet<>();
        final Set<String> neverRunning = new HashSet<>();
        boolean unreachable;
        /** コンテナ名 -> HostConfig.Runtime。既定は空(Dockerの既定ランタイム)。 */
        final Map<String, String> runtimes = new HashMap<>();
        /** ヘルスチェックが healthy にならないコンテナ(running でも starting のまま)。 */
        final Set<String> unhealthy = new HashSet<>();
        final Set<String> inspectFails = new HashSet<>();

        FakeDocker with(String name, String state) {
            states.put(name, state);
            return this;
        }

        @Override
        public List<ContainerRef> listContainers() {
            if (unreachable) {
                throw new DockerEngineException("proxy unreachable");
            }
            List<ContainerRef> refs = new ArrayList<>();
            states.forEach((name, state) -> refs.add(new ContainerRef("id-" + name, name, state)));
            return refs;
        }

        @Override
        public ContainerInspection inspectContainer(String id) {
            String name = id.substring(3);
            if (inspectFails.contains(name)) {
                throw new DockerEngineException("inspect " + name + " に失敗しました");
            }
            boolean healthy = "running".equals(states.get(name)) && !unhealthy.contains(name);
            return new ContainerInspection(runtimes.getOrDefault(name, ""), healthy ? "healthy" : "starting");
        }

        @Override
        public void startContainer(String id) {
            String name = id.substring(3);
            calls.add("start:" + name);
            if (rejectStart.contains(name)) {
                throw new DockerEngineException("start " + name + " は 403 で拒否されました");
            }
            if (!neverRunning.contains(name)) {
                states.put(name, "running");
            }
        }

        @Override
        public void stopContainer(String id) {
            String name = id.substring(3);
            calls.add("stop:" + name);
            if (rejectStop.contains(name)) {
                throw new DockerEngineException("stop " + name + " は 403 で拒否されました");
            }
            states.put(name, "exited");
        }
    }

    /** sleepした分だけ進む時計。実時間を待たずに上限時間を超えさせる。 */
    static class MutableClock extends Clock {
        private Instant now = Instant.parse("2026-10-02T00:00:00Z");

        @Override
        public ZoneOffset getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(java.time.ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }

        void advance(Duration d) {
            now = now.plus(d);
        }
    }

    private FakeDocker docker;
    private MutableClock clock;
    private boolean healthy;
    private final List<String> probedUrls = new ArrayList<>();
    private final List<Runnable> queued = new ArrayList<>();

    @BeforeEach
    void setUp() {
        docker = new FakeDocker();
        clock = new MutableClock();
        healthy = true;
        probedUrls.clear();
        queued.clear();
    }

    private ComputeDeviceService service(java.util.concurrent.Executor executor) {
        ComputeDeviceHealthProbe probe = url -> {
            probedUrls.add(url);
            return healthy;
        };
        return new ComputeDeviceService(docker, probe,
                List.of(new ComputeTarget("comfyui", GPU, CPU, HEALTH_URL)),
                executor, clock, d -> clock.advance(d), TIMEOUT, POLL);
    }

    private ComputeDeviceService direct() {
        return service(Runnable::run);
    }

    private ComputeDeviceService manual() {
        return service(queued::add);
    }

    private static ComputeDeviceException rejected(Runnable r) {
        return assertThrows(ComputeDeviceException.class, r::run);
    }

    // ---- 状態の判定(要件3・4) ----

    @Test
    void 両構成があればどちらも選べ_現在の構成は動いているコンテナから判定する() {
        docker.with(GPU, "running").with(CPU, "exited");

        ComputeDeviceStatusResponse status = direct().getStatus("comfyui");

        assertEquals("comfyui", status.target());
        assertEquals(CurrentDevice.GPU, status.currentDevice());
        assertTrue(status.gpuSelectable());
        assertTrue(status.cpuSelectable());
        assertFalse(status.cpuFixed());
        assertNull(status.gpuUnavailableReason());
        assertNull(status.cpuUnavailableReason());
        assertEquals(ApplyState.IDLE, status.apply().state());
    }

    @Test
    void CPU構成だけが動いていればCPUと判定する() {
        docker.with(GPU, "exited").with(CPU, "running");

        assertEquals(CurrentDevice.CPU, direct().getStatus("comfyui").currentDevice());
    }

    @Test
    void どちらも動いていなければNONE_両方動いていればBOTH() {
        docker.with(GPU, "exited").with(CPU, "created");
        assertEquals(CurrentDevice.NONE, direct().getStatus("comfyui").currentDevice());

        docker.with(GPU, "running").with(CPU, "running");
        assertEquals(CurrentDevice.BOTH, direct().getStatus("comfyui").currentDevice());
    }

    @Test
    void GPU構成のコンテナが無ければCPU固定でGPUは選べず理由を返す() {
        docker.with(CPU, "running");

        ComputeDeviceStatusResponse status = direct().getStatus("comfyui");

        assertTrue(status.cpuFixed());
        assertFalse(status.gpuSelectable());
        assertTrue(status.cpuSelectable());
        assertEquals(CurrentDevice.CPU, status.currentDevice());
        assertTrue(status.gpuUnavailableReason().contains("GPU 構成の ComfyUI"), status.gpuUnavailableReason());
    }

    @Test
    void GPU構成だけがあればCPUを選べない理由に作成手順を示す() {
        docker.with(GPU, "running");

        ComputeDeviceStatusResponse status = direct().getStatus("comfyui");

        assertFalse(status.cpuFixed());
        assertTrue(status.gpuSelectable());
        assertFalse(status.cpuSelectable());
        assertTrue(status.cpuUnavailableReason()
                .contains("docker compose --profile cpu create comfyui-cpu"), status.cpuUnavailableReason());
    }

    @Test
    void どちらのコンテナも無ければ何も選べない() {
        ComputeDeviceStatusResponse status = direct().getStatus("comfyui");

        assertTrue(status.cpuFixed());
        assertFalse(status.gpuSelectable());
        assertFalse(status.cpuSelectable());
        assertEquals(CurrentDevice.NONE, status.currentDevice());
        assertNotNull(status.cpuUnavailableReason());
    }

    @Test
    void 未知の対象は404() {
        ComputeDeviceException e = rejected(() -> direct().getStatus("ollama"));
        assertEquals(HttpStatus.NOT_FOUND, e.status());
        assertEquals(HttpStatus.NOT_FOUND, rejected(() -> direct().apply("ollama", ComputeDevice.CPU)).status());
    }

    @Test
    void Docker_Engine_APIへ到達できなければ503() {
        docker.unreachable = true;

        assertEquals(HttpStatus.SERVICE_UNAVAILABLE, rejected(() -> direct().getStatus("comfyui")).status());
        assertEquals(HttpStatus.SERVICE_UNAVAILABLE,
                rejected(() -> direct().apply("comfyui", ComputeDevice.CPU)).status());
    }

    // ---- 適用の流れ(要件5) ----

    @Test
    void 適用は受け付けたらすぐ返り_裏で停止_起動_成功判定の順に進む() {
        docker.with(GPU, "running").with(CPU, "exited");
        ComputeDeviceService service = manual();

        ComputeDeviceStatusResponse accepted = service.apply("comfyui", ComputeDevice.CPU);

        assertEquals(ApplyState.APPLYING, accepted.apply().state());
        assertEquals(ComputeDevice.CPU, accepted.apply().requestedDevice());
        assertTrue(docker.calls.isEmpty(), "受け付けただけでは何も操作しない");
        assertEquals(ApplyState.APPLYING, service.getStatus("comfyui").apply().state());

        queued.get(0).run();

        assertEquals(List.of("stop:" + GPU, "start:" + CPU), docker.calls);
        ComputeDeviceStatusResponse done = service.getStatus("comfyui");
        assertEquals(ApplyState.SUCCEEDED, done.apply().state());
        assertEquals(CurrentDevice.CPU, done.currentDevice());
        assertNotNull(done.apply().finishedAt());
        assertEquals(List.of(HEALTH_URL), probedUrls);
    }

    @Test
    void CPUからGPUへも切り替えられる() {
        docker.with(GPU, "exited").with(CPU, "running");
        ComputeDeviceService service = direct();

        service.apply("comfyui", ComputeDevice.GPU);

        assertEquals(List.of("stop:" + CPU, "start:" + GPU), docker.calls);
        assertEquals(CurrentDevice.GPU, service.getStatus("comfyui").currentDevice());
    }

    @Test
    void 何も動いていない状態からは停止せず起動だけする() {
        docker.with(GPU, "exited").with(CPU, "exited");

        direct().apply("comfyui", ComputeDevice.CPU);

        assertEquals(List.of("start:" + CPU), docker.calls);
    }

    @Test
    void 両方動いている状態からも片方に寄せられる() {
        docker.with(GPU, "running").with(CPU, "running");
        ComputeDeviceService service = direct();

        service.apply("comfyui", ComputeDevice.CPU);

        assertEquals(List.of("stop:" + GPU), docker.calls, "既に動いている側は起動し直さない");
        assertEquals(CurrentDevice.CPU, service.getStatus("comfyui").currentDevice());
        assertEquals(ApplyState.SUCCEEDED, service.getStatus("comfyui").apply().state());
    }

    @Test
    void 起動後すぐrunningにならなくても上限内に健全になれば成功する() {
        docker.with(GPU, "running").with(CPU, "exited");
        int[] probes = {0};
        ComputeDeviceHealthProbe probe = url -> ++probes[0] >= 3;
        ComputeDeviceService service = new ComputeDeviceService(docker, probe,
                List.of(new ComputeTarget("comfyui", GPU, CPU, HEALTH_URL)),
                Runnable::run, clock, d -> clock.advance(d), TIMEOUT, POLL);

        service.apply("comfyui", ComputeDevice.CPU);

        assertEquals(ApplyState.SUCCEEDED, service.getStatus("comfyui").apply().state());
        assertEquals(3, probes[0]);
    }

    // ---- 拒否(要件3・5) ----

    @Test
    void GPU構成が無いホストではGPUへの適用を拒否し何も操作しない() {
        docker.with(CPU, "running");

        ComputeDeviceException e = rejected(() -> direct().apply("comfyui", ComputeDevice.GPU));

        assertEquals(HttpStatus.CONFLICT, e.status());
        assertTrue(docker.calls.isEmpty());
    }

    @Test
    void CPU構成が無いホストではCPUへの適用を拒否し何も操作しない() {
        docker.with(GPU, "running");

        ComputeDeviceException e = rejected(() -> direct().apply("comfyui", ComputeDevice.CPU));

        assertEquals(HttpStatus.CONFLICT, e.status());
        assertTrue(e.getMessage().contains("docker compose --profile cpu create comfyui-cpu"));
        assertTrue(docker.calls.isEmpty());
    }

    @Test
    void 既にその構成だけで動いていれば拒否する() {
        docker.with(GPU, "running").with(CPU, "exited");

        ComputeDeviceException e = rejected(() -> direct().apply("comfyui", ComputeDevice.GPU));

        assertEquals(HttpStatus.CONFLICT, e.status());
        assertTrue(docker.calls.isEmpty());
    }

    @Test
    void 適用中に次の適用要求が来たら拒否する() {
        docker.with(GPU, "running").with(CPU, "exited");
        ComputeDeviceService service = manual();
        service.apply("comfyui", ComputeDevice.CPU);

        ComputeDeviceException e = rejected(() -> service.apply("comfyui", ComputeDevice.CPU));

        assertEquals(HttpStatus.CONFLICT, e.status());
        assertTrue(e.getMessage().contains("適用中"));
        assertEquals(1, queued.size());
    }

    @Test
    void 失敗した後は再び適用できる() {
        docker.with(GPU, "running").with(CPU, "exited");
        docker.rejectStart.add(CPU);
        ComputeDeviceService service = direct();
        service.apply("comfyui", ComputeDevice.CPU);
        assertEquals(ApplyState.FAILED, service.getStatus("comfyui").apply().state());

        docker.rejectStart.clear();
        service.apply("comfyui", ComputeDevice.CPU);

        assertEquals(ApplyState.SUCCEEDED, service.getStatus("comfyui").apply().state());
    }

    // ---- 失敗時の復帰(要件6) ----

    @Test
    void startが拒否されたら選んだ構成を止めて元の構成を起動し直し_段階を理由に示す() {
        docker.with(GPU, "running").with(CPU, "exited");
        docker.rejectStart.add(CPU);
        ComputeDeviceService service = direct();

        service.apply("comfyui", ComputeDevice.CPU);

        ComputeDeviceStatusResponse status = service.getStatus("comfyui");
        assertEquals(ApplyState.FAILED, status.apply().state());
        assertEquals(List.of("stop:" + GPU, "start:" + CPU, "stop:" + CPU, "start:" + GPU), docker.calls);
        assertEquals("running", docker.states.get(GPU));
        assertEquals("exited", docker.states.get(CPU));
        assertEquals(CurrentDevice.GPU, status.currentDevice());
        String message = status.apply().message();
        assertTrue(message.contains("CPU構成の起動"), message);
        assertTrue(message.contains("403"), message);
        assertTrue(message.contains("元の構成(GPU)に戻しました"), message);
    }

    @Test
    void 上限時間内にrunningにならなければ失敗して元に戻す() {
        docker.with(GPU, "running").with(CPU, "exited");
        docker.neverRunning.add(CPU);
        ComputeDeviceService service = direct();

        service.apply("comfyui", ComputeDevice.CPU);

        ComputeDeviceStatusResponse status = service.getStatus("comfyui");
        assertEquals(ApplyState.FAILED, status.apply().state());
        assertTrue(status.apply().message().contains("30秒"), status.apply().message());
        assertTrue(status.apply().message().contains("running"), status.apply().message());
        assertEquals("running", docker.states.get(GPU));
        assertEquals("exited", docker.states.get(CPU));
        assertTrue(probedUrls.isEmpty(), "runningでないうちは疎通確認しない");
    }

    @Test
    void runningでもsystem_statsが200を返さなければ上限で失敗して元に戻す() {
        docker.with(GPU, "running").with(CPU, "exited");
        healthy = false;
        ComputeDeviceService service = direct();

        service.apply("comfyui", ComputeDevice.CPU);

        ComputeDeviceStatusResponse status = service.getStatus("comfyui");
        assertEquals(ApplyState.FAILED, status.apply().state());
        assertTrue(status.apply().message().contains("system_stats"), status.apply().message());
        assertEquals(CurrentDevice.GPU, status.currentDevice());
        assertFalse(probedUrls.isEmpty());
    }

    @Test
    void 現在の構成の停止に失敗したら失敗にして元の構成を起動し直す() {
        docker.with(GPU, "running").with(CPU, "exited");
        docker.rejectStop.add(GPU);
        ComputeDeviceService service = direct();

        service.apply("comfyui", ComputeDevice.CPU);

        ComputeDeviceStatusResponse status = service.getStatus("comfyui");
        assertEquals(ApplyState.FAILED, status.apply().state());
        assertTrue(status.apply().message().contains("GPU構成の停止"), status.apply().message());
        assertEquals("running", docker.states.get(GPU));
        assertFalse(docker.calls.contains("start:" + CPU), "停止に失敗したら選んだ構成は起動しない");
    }

    @Test
    void 元の構成の再起動にも失敗したら手動復旧の手順を示す() {
        docker.with(GPU, "running").with(CPU, "exited");
        docker.rejectStart.add(CPU);
        docker.rejectStart.add(GPU);
        ComputeDeviceService service = direct();

        service.apply("comfyui", ComputeDevice.CPU);

        ComputeDeviceStatusResponse status = service.getStatus("comfyui");
        assertEquals(ApplyState.FAILED, status.apply().state());
        String message = status.apply().message();
        assertTrue(message.contains("元の構成(GPU)の再起動にも失敗"), message);
        assertTrue(message.contains("docker start lbs-comfyui"), message);
    }

    @Test
    void 元の構成の再起動後に健全にならなくても手動復旧を示す() {
        docker.with(GPU, "running").with(CPU, "exited");
        docker.rejectStart.add(CPU);
        // 元の構成(GPU)はstartが通るが、running にならない。
        docker.neverRunning.add(GPU);
        ComputeDeviceService service = direct();

        service.apply("comfyui", ComputeDevice.CPU);

        String message = service.getStatus("comfyui").apply().message();
        assertTrue(message.contains("元の構成(GPU)の再起動にも失敗"), message);
        assertTrue(message.contains("docker start lbs-comfyui"), message);
    }

    @Test
    void 元が何も動いていなければ失敗後も何も起動し直さない() {
        docker.with(GPU, "exited").with(CPU, "exited");
        docker.rejectStart.add(CPU);
        ComputeDeviceService service = direct();

        service.apply("comfyui", ComputeDevice.CPU);

        assertEquals(List.of("start:" + CPU, "stop:" + CPU), docker.calls);
        String message = service.getStatus("comfyui").apply().message();
        assertTrue(message.contains("元々どちらも停止していた"), message);
    }

    @Test
    void 元がCPUでGPUへの切り替えに失敗したらCPUへ戻す() {
        docker.with(GPU, "exited").with(CPU, "running");
        docker.rejectStart.add(GPU);
        ComputeDeviceService service = direct();

        service.apply("comfyui", ComputeDevice.GPU);

        assertEquals("running", docker.states.get(CPU));
        assertTrue(service.getStatus("comfyui").apply().message().contains("元の構成(CPU)に戻しました"));
    }

    @Test
    void 疎通確認が例外を投げても失敗として扱い進行状態は失敗になる() {
        docker.with(GPU, "running").with(CPU, "exited");
        ComputeDeviceHealthProbe probe = url -> {
            throw new IllegalStateException("boom");
        };
        ComputeDeviceService service = new ComputeDeviceService(docker, probe,
                List.of(new ComputeTarget("comfyui", GPU, CPU, HEALTH_URL)),
                Runnable::run, clock, d -> clock.advance(d), TIMEOUT, POLL);

        service.apply("comfyui", ComputeDevice.CPU);

        ComputeDeviceStatusResponse status = service.getStatus("comfyui");
        assertEquals(ApplyState.FAILED, status.apply().state());
        assertTrue(status.apply().message().contains("boom"), status.apply().message());
    }

    @Test
    void 待機中に割り込まれたら失敗として扱う() {
        docker.with(GPU, "running").with(CPU, "exited");
        docker.neverRunning.add(CPU);
        ComputeDeviceService service = new ComputeDeviceService(docker, url -> true,
                List.of(new ComputeTarget("comfyui", GPU, CPU, HEALTH_URL)),
                Runnable::run, clock, d -> {
                    throw new InterruptedException("stop");
                }, TIMEOUT, POLL);

        service.apply("comfyui", ComputeDevice.CPU);

        assertEquals(ApplyState.FAILED, service.getStatus("comfyui").apply().state());
        assertTrue(Thread.interrupted(), "割り込みフラグを立て直す");
    }

    @Test
    void 実行器が受け付けなければ適用中のまま残さず拒否する() {
        docker.with(GPU, "running").with(CPU, "exited");
        ComputeDeviceService service = service(r -> {
            throw new java.util.concurrent.RejectedExecutionException("full");
        });

        ComputeDeviceException e = rejected(() -> service.apply("comfyui", ComputeDevice.CPU));

        assertEquals(HttpStatus.SERVICE_UNAVAILABLE, e.status());
        assertEquals(ApplyState.IDLE, service.getStatus("comfyui").apply().state());
    }
    // ---- 片方のコンテナしか無いホスト・両方動いている状態での復帰 ----

    @Test
    void CPU構成が無くてもGPU構成が停止中なら起動だけで適用できる() {
        docker.with(GPU, "exited");

        direct().apply("comfyui", ComputeDevice.GPU);

        assertEquals(List.of("start:" + GPU), docker.calls);
    }

    @Test
    void GPU構成が無いホストでCPUの起動が拒否されても何も起動し直さず選んだ構成を止める() {
        docker.with(CPU, "exited");
        docker.rejectStart.add(CPU);
        ComputeDeviceService service = direct();

        service.apply("comfyui", ComputeDevice.CPU);

        assertEquals(List.of("start:" + CPU, "stop:" + CPU), docker.calls);
        assertTrue(service.getStatus("comfyui").apply().message().contains("元々どちらも停止していた"));
    }

    @Test
    void CPU構成が無いホストでGPUの起動が拒否されても何も起動し直さない() {
        docker.with(GPU, "exited");
        docker.rejectStart.add(GPU);
        ComputeDeviceService service = direct();

        service.apply("comfyui", ComputeDevice.GPU);

        assertEquals(List.of("start:" + GPU, "stop:" + GPU), docker.calls);
    }

    @Test
    void 両方動いている状態で停止に失敗しても選んだ構成は止めず_元の両方を保つ() {
        docker.with(GPU, "running").with(CPU, "running");
        docker.rejectStop.add(GPU);
        ComputeDeviceService service = direct();

        service.apply("comfyui", ComputeDevice.CPU);

        assertEquals(List.of("stop:" + GPU, "start:" + GPU, "start:" + CPU), docker.calls);
        String message = service.getStatus("comfyui").apply().message();
        assertTrue(message.contains("元の構成(GPU と CPU)に戻しました"), message);
        assertEquals(CurrentDevice.BOTH, service.getStatus("comfyui").currentDevice());
    }

    @Test
    void 選んだ構成の停止にも失敗したことを理由に添える() {
        docker.with(GPU, "running").with(CPU, "exited");
        docker.rejectStart.add(CPU);
        docker.rejectStop.add(CPU);
        ComputeDeviceService service = direct();

        service.apply("comfyui", ComputeDevice.CPU);

        String message = service.getStatus("comfyui").apply().message();
        assertTrue(message.contains("選んだ構成(CPU)の停止にも失敗しました"), message);
        assertTrue(message.contains("元の構成(GPU)に戻しました"), message);
    }

    @Test
    void 元がCPUでGPUへの切り替えに失敗しCPUの再起動にも失敗したらCPUの手動復旧手順を示す() {
        docker.with(GPU, "exited").with(CPU, "running");
        docker.rejectStart.add(GPU);
        docker.rejectStart.add(CPU);
        ComputeDeviceService service = direct();

        service.apply("comfyui", ComputeDevice.GPU);

        String message = service.getStatus("comfyui").apply().message();
        assertTrue(message.contains("docker start lbs-comfyui-cpu"), message);
        assertFalse(message.contains("docker start lbs-comfyui "), message);
    }

    // ---- Ollama(issue #1585)。ComfyUIとは独立した2つ目の対象 ----

    private static final String OLLAMA_GPU = "lbs-ollama";
    private static final String OLLAMA_CPU = "lbs-ollama-cpu";

    private static ComputeTarget ollamaTarget() {
        return new ComputeTarget("ollama", "Ollama", OLLAMA_GPU, OLLAMA_CPU, null, true, "ollama-cpu");
    }

    private ComputeDeviceService both(java.util.concurrent.Executor executor) {
        ComputeDeviceHealthProbe probe = url -> {
            probedUrls.add(url);
            return healthy;
        };
        return new ComputeDeviceService(docker, probe,
                List.of(new ComputeTarget("comfyui", GPU, CPU, HEALTH_URL), ollamaTarget()),
                executor, clock, d -> clock.advance(d), TIMEOUT, POLL);
    }

    private void ollamaOnNvidiaHost(String gpuState, String cpuState) {
        docker.with(OLLAMA_GPU, gpuState).with(OLLAMA_CPU, cpuState);
        docker.runtimes.put(OLLAMA_GPU, "nvidia");
    }

    @Test
    void Ollama_runtimeがnvidiaで両構成があればどちらも選べる() {
        ollamaOnNvidiaHost("running", "exited");

        ComputeDeviceStatusResponse status = both(Runnable::run).getStatus("ollama");

        assertEquals("ollama", status.target());
        assertEquals(CurrentDevice.GPU, status.currentDevice());
        assertTrue(status.gpuSelectable());
        assertTrue(status.cpuSelectable());
        assertFalse(status.cpuFixed());
        assertNull(status.gpuUnavailableReason());
        assertNull(status.cpuUnavailableReason());
    }

    @Test
    void Ollama_runtimeがnvidiaでなければCPU固定でGPUは選べず理由を返す() {
        docker.with(OLLAMA_GPU, "running");
        docker.runtimes.put(OLLAMA_GPU, "");

        ComputeDeviceStatusResponse status = both(Runnable::run).getStatus("ollama");

        assertTrue(status.cpuFixed());
        assertFalse(status.gpuSelectable());
        assertEquals(CurrentDevice.CPU, status.currentDevice());
        assertTrue(status.gpuUnavailableReason().contains("nvidia"), status.gpuUnavailableReason());
    }

    @Test
    void Ollama_runtimeがnvidiaでないホストでGPUへの適用は拒否され_どのコンテナも操作されない() {
        docker.with(OLLAMA_GPU, "running").with(OLLAMA_CPU, "exited");
        docker.runtimes.put(OLLAMA_GPU, "runc");

        ComputeDeviceException e = rejected(() -> both(Runnable::run).apply("ollama", ComputeDevice.GPU));

        assertEquals(HttpStatus.CONFLICT, e.status());
        assertTrue(docker.calls.isEmpty());
    }

    @Test
    void Ollama_lbs_ollamaが無くCPU構成だけでもCPU固定と判定する() {
        docker.with(OLLAMA_CPU, "running");

        ComputeDeviceStatusResponse status = both(Runnable::run).getStatus("ollama");

        assertTrue(status.cpuFixed());
        assertEquals(CurrentDevice.CPU, status.currentDevice());
        assertFalse(status.gpuSelectable());
    }

    @Test
    void Ollama_CPU構成が無ければCPUを選べない理由に作成手順を示す() {
        ollamaOnNvidiaHost("running", "exited");
        docker.states.remove(OLLAMA_CPU);

        ComputeDeviceStatusResponse status = both(Runnable::run).getStatus("ollama");

        assertFalse(status.cpuSelectable());
        assertTrue(status.cpuUnavailableReason()
                .contains("docker compose --profile ollama-cpu create ollama-cpu"), status.cpuUnavailableReason());
        assertTrue(rejected(() -> both(Runnable::run).apply("ollama", ComputeDevice.CPU)).getMessage()
                .contains("docker compose --profile ollama-cpu create ollama-cpu"));
    }

    @Test
    void Ollama_GPUからCPUへ切り替えると選んだ構成だけがrunningかつhealthyになる() {
        ollamaOnNvidiaHost("running", "exited");
        ComputeDeviceService service = both(Runnable::run);

        service.apply("ollama", ComputeDevice.CPU);

        assertEquals(List.of("stop:" + OLLAMA_GPU, "start:" + OLLAMA_CPU), docker.calls);
        ComputeDeviceStatusResponse done = service.getStatus("ollama");
        assertEquals(ApplyState.SUCCEEDED, done.apply().state());
        assertEquals(CurrentDevice.CPU, done.currentDevice());
        assertTrue(probedUrls.isEmpty(), "Ollamaの成功判定はURLではなくコンテナのヘルスチェックで行う");
    }

    @Test
    void Ollama_CPUからGPUへも切り替えられる() {
        ollamaOnNvidiaHost("exited", "running");
        ComputeDeviceService service = both(Runnable::run);

        service.apply("ollama", ComputeDevice.GPU);

        assertEquals(List.of("stop:" + OLLAMA_CPU, "start:" + OLLAMA_GPU), docker.calls);
        assertEquals(CurrentDevice.GPU, service.getStatus("ollama").currentDevice());
    }

    @Test
    void Ollama_healthyにならなければ上限後に失敗し_元の構成が_runningに戻る() {
        ollamaOnNvidiaHost("running", "exited");
        docker.unhealthy.add(OLLAMA_CPU);
        ComputeDeviceService service = both(Runnable::run);

        service.apply("ollama", ComputeDevice.CPU);

        ComputeDeviceStatusResponse status = service.getStatus("ollama");
        assertEquals(ApplyState.FAILED, status.apply().state());
        assertTrue(status.apply().message().contains("healthy"), status.apply().message());
        assertTrue(status.apply().message().contains("30秒以内"), status.apply().message());
        assertTrue(status.apply().message().contains("元の構成(GPU)に戻しました"), status.apply().message());
        assertEquals("running", docker.states.get(OLLAMA_GPU));
        assertEquals("exited", docker.states.get(OLLAMA_CPU));
    }

    @Test
    void Ollama_起動確認中のinspect失敗は健全でないものとして待ち続け_回復すれば成功する() {
        ollamaOnNvidiaHost("running", "exited");
        docker.inspectFails.add(OLLAMA_CPU);
        ComputeDeviceService service = new ComputeDeviceService(docker, url -> true,
                List.of(ollamaTarget()), Runnable::run, clock,
                d -> {
                    clock.advance(d);
                    docker.inspectFails.clear();
                }, TIMEOUT, POLL);

        service.apply("ollama", ComputeDevice.CPU);

        assertEquals(ApplyState.SUCCEEDED, service.getStatus("ollama").apply().state());
    }

    @Test
    void Ollama_状態取得中のinspect失敗は503() {
        ollamaOnNvidiaHost("running", "exited");
        docker.inspectFails.add(OLLAMA_GPU);

        assertEquals(HttpStatus.SERVICE_UNAVAILABLE,
                rejected(() -> both(Runnable::run).getStatus("ollama")).status());
    }

    @Test
    void ComfyUIを切り替えてもOllamaのコンテナは操作されず_逆も同じ() {
        docker.with(GPU, "running").with(CPU, "exited");
        ollamaOnNvidiaHost("running", "exited");
        ComputeDeviceService service = both(Runnable::run);

        service.apply("comfyui", ComputeDevice.CPU);

        assertEquals(List.of("stop:" + GPU, "start:" + CPU), docker.calls);
        assertEquals("running", docker.states.get(OLLAMA_GPU));
        assertEquals(ApplyState.IDLE, service.getStatus("ollama").apply().state());

        docker.calls.clear();
        service.apply("ollama", ComputeDevice.CPU);

        assertEquals(List.of("stop:" + OLLAMA_GPU, "start:" + OLLAMA_CPU), docker.calls);
        assertEquals("running", docker.states.get(CPU));
    }

    @Test
    void 片方の対象が適用中でももう一方の適用は拒否されない() {
        docker.with(GPU, "running").with(CPU, "exited");
        ollamaOnNvidiaHost("running", "exited");
        ComputeDeviceService service = both(queued::add);

        service.apply("comfyui", ComputeDevice.CPU);
        ComputeDeviceStatusResponse accepted = service.apply("ollama", ComputeDevice.CPU);

        assertEquals(ApplyState.APPLYING, accepted.apply().state());
        assertEquals(2, queued.size());
        assertEquals(HttpStatus.CONFLICT,
                rejected(() -> service.apply("ollama", ComputeDevice.GPU)).status());
    }
}
