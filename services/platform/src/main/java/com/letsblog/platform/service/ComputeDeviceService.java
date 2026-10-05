package com.letsblog.platform.service;

import com.letsblog.platform.dto.ComputeDevice;
import com.letsblog.platform.dto.ComputeDeviceStatusResponse;
import com.letsblog.platform.dto.ComputeDeviceStatusResponse.ApplyState;
import com.letsblog.platform.dto.ComputeDeviceStatusResponse.ApplyStatus;
import com.letsblog.platform.dto.ComputeDeviceStatusResponse.CurrentDevice;
import com.letsblog.platform.service.DockerEngineClient.ContainerRef;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.StringJoiner;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;

/**
 * 管理画面から演算デバイス(GPU / CPU)を切り替える(issue #1399)。
 *
 * <h2>機構</h2>
 * 両構成のコンテナ({@code lbs-comfyui} と {@code lbs-comfyui-cpu})を事前に作っておき、Docker Engine APIの
 * {@code start} / {@code stop} だけで切り替える。コンテナの作成・削除・作り直しはしない(Epic #551 / #701の
 * 方針。docker-socket-proxyが開けているのは start / stop だけ)。待機側コンテナの作成は運用手順であり、
 * このクラスは実行しない。
 *
 * <h2>GPUの有無</h2>
 * proxyの読み取り権限ではホストのGPUを直接調べられないため、GPU構成のコンテナ({@code lbs-comfyui})が
 * 存在するかで判定する。GPU構成のコンテナはGPUホストでしか作らない運用である。無ければCPUに固定する。
 *
 * <h2>適用</h2>
 * 要求を受け付けたら即座に返し、裏で「他方を停止 → 選んだ構成を起動 → 成功判定」を進める。成功は
 * 選んだ構成のコンテナが{@code running}になり、かつ疎通確認URLがHTTP 200を返すこと。上限時間内に
 * 成功しなければ、選んだ構成を止めて元の構成を起動し直す。適用中の次の要求は拒否する。
 * 進行状態はメモリにだけ持つ(適用中にplatform-serviceが再起動した場合の引き継ぎは対象外)。
 */
@Service
public class ComputeDeviceService {

    private static final Logger log = LoggerFactory.getLogger(ComputeDeviceService.class);

    /** 待機中の時間を進める。テストでは時計を進めるだけの実装に差し替える。 */
    @FunctionalInterface
    interface Sleeper {
        void sleep(Duration duration) throws InterruptedException;
    }

    /** 適用の途中で失敗した段階と理由。 */
    private static final class StageFailure extends RuntimeException {
        StageFailure(String stage, String reason) {
            super(stage + "に失敗しました: " + reason);
        }
    }

    private static final ApplyStatus IDLE = new ApplyStatus(ApplyState.IDLE, null, null, null, null);

    private final DockerEngineClient docker;
    private final ComputeDeviceHealthProbe probe;
    private final Map<String, ComputeTarget> targets = new HashMap<>();
    private final Executor executor;
    private final Clock clock;
    private final Sleeper sleeper;
    private final Duration timeout;
    private final Duration pollInterval;

    private final Object lock = new Object();
    private final Map<String, ApplyStatus> applyStates = new HashMap<>();

    @Autowired
    public ComputeDeviceService(
            DockerEngineClient docker,
            ComputeDeviceHealthProbe probe,
            @Value("${app.compute-device.comfyui-health-url}") String comfyUiHealthUrl,
            @Value("${app.compute-device.apply-timeout-seconds:180}") long timeoutSeconds,
            @Value("${app.compute-device.poll-interval-millis:2000}") long pollIntervalMillis) {
        this(docker, probe,
                List.of(new ComputeTarget("comfyui", "lbs-comfyui", "lbs-comfyui-cpu", comfyUiHealthUrl)),
                Executors.newCachedThreadPool(runnable -> {
                    Thread thread = new Thread(runnable, "compute-device-apply");
                    thread.setDaemon(true);
                    return thread;
                }),
                Clock.systemUTC(),
                duration -> Thread.sleep(duration.toMillis()),
                Duration.ofSeconds(timeoutSeconds),
                Duration.ofMillis(pollIntervalMillis));
    }

    /** テスト専用: 実行器・時計・待機を差し替えられる。 */
    ComputeDeviceService(
            DockerEngineClient docker,
            ComputeDeviceHealthProbe probe,
            List<ComputeTarget> targetList,
            Executor executor,
            Clock clock,
            Sleeper sleeper,
            Duration timeout,
            Duration pollInterval) {
        this.docker = docker;
        this.probe = probe;
        targetList.forEach(target -> targets.put(target.id(), target));
        this.executor = executor;
        this.clock = clock;
        this.sleeper = sleeper;
        this.timeout = timeout;
        this.pollInterval = pollInterval;
    }

    // ---- 状態 ----

    public ComputeDeviceStatusResponse getStatus(String targetId) {
        ComputeTarget target = target(targetId);
        return toResponse(target, snapshot(target), applyStatus(target));
    }

    private ComputeDeviceStatusResponse toResponse(ComputeTarget target, Snapshot snapshot, ApplyStatus apply) {
        boolean gpuExists = snapshot.gpu() != null;
        boolean cpuExists = snapshot.cpu() != null;
        return new ComputeDeviceStatusResponse(
                target.id(),
                snapshot.current(),
                !gpuExists,
                gpuExists,
                cpuExists,
                gpuExists ? null : gpuUnavailableReason(target),
                cpuExists ? null : cpuUnavailableReason(target),
                apply);
    }

    private static String gpuUnavailableReason(ComputeTarget target) {
        return "このホストには GPU 構成の ComfyUI(" + target.gpuContainerName() + ")が無いため、CPU 固定です。";
    }

    private static String cpuUnavailableReason(ComputeTarget target) {
        return "CPU 構成のコンテナ(" + target.cpuContainerName() + ")がまだ作成されていません。"
                + "ホストで「docker compose --profile cpu create " + target.id() + "-cpu」を一度だけ実行してください。";
    }

    // ---- 適用 ----

    /**
     * 適用を受け付ける。すぐに返り(戻り値の進行状態は{@code APPLYING})、切り替えは裏で進む。
     *
     * @throws ComputeDeviceException 対象不明(404)、選べない構成・既にその構成・適用中(409)、
     *                                Docker Engine APIへ到達できない・実行器が受け付けない(503)
     */
    public ComputeDeviceStatusResponse apply(String targetId, ComputeDevice device) {
        ComputeTarget target = target(targetId);
        Snapshot snapshot = snapshot(target);
        validate(target, snapshot, device);

        ApplyStatus accepted = new ApplyStatus(ApplyState.APPLYING, device, null, clock.instant(), null);
        ApplyStatus previous;
        synchronized (lock) {
            previous = applyStatus(target);
            if (previous.state() == ApplyState.APPLYING) {
                throw new ComputeDeviceException(HttpStatus.CONFLICT, "別の適用が適用中です。完了を待ってから再度お試しください。");
            }
            applyStates.put(target.id(), accepted);
        }
        try {
            executor.execute(() -> run(target, snapshot, device, accepted));
        } catch (RejectedExecutionException e) {
            synchronized (lock) {
                applyStates.put(target.id(), previous);
            }
            throw new ComputeDeviceException(HttpStatus.SERVICE_UNAVAILABLE, "適用を開始できませんでした。しばらくしてから再度お試しください。");
        }
        return toResponse(target, snapshot, accepted);
    }

    private void validate(ComputeTarget target, Snapshot snapshot, ComputeDevice device) {
        ContainerRef wanted = device == ComputeDevice.GPU ? snapshot.gpu() : snapshot.cpu();
        if (wanted == null) {
            String reason = device == ComputeDevice.GPU ? gpuUnavailableReason(target) : cpuUnavailableReason(target);
            throw new ComputeDeviceException(HttpStatus.CONFLICT, reason);
        }
        if (snapshot.current() == CurrentDevice.valueOf(device.name())) {
            throw new ComputeDeviceException(HttpStatus.CONFLICT, "既に " + device + " 構成だけが動作しています。");
        }
    }

    private void run(ComputeTarget target, Snapshot snapshot, ComputeDevice device, ApplyStatus accepted) {
        ContainerRef wanted = device == ComputeDevice.GPU ? snapshot.gpu() : snapshot.cpu();
        ContainerRef other = device == ComputeDevice.GPU ? snapshot.cpu() : snapshot.gpu();
        ComputeDevice otherDevice = device == ComputeDevice.GPU ? ComputeDevice.CPU : ComputeDevice.GPU;
        try {
            if (other != null && other.running()) {
                stage(otherDevice + "構成の停止", () -> docker.stopContainer(other.id()));
            }
            if (!wanted.running()) {
                stage(device + "構成の起動", () -> docker.startContainer(wanted.id()));
            }
            waitUntilHealthy(target, wanted, device);
            finish(target, accepted, ApplyState.SUCCEEDED, device + "構成で稼働中です。");
        } catch (StageFailure failure) {
            fail(target, snapshot, device, accepted, failure.getMessage());
        } catch (RuntimeException e) {
            log.warn("演算デバイスの適用中に予期しないエラー: {}", e.toString(), e);
            fail(target, snapshot, device, accepted, "適用処理に失敗しました: " + e.getMessage());
        }
    }

    /** Docker Engine APIの失敗を「どの段階か」を添えた{@link StageFailure}にする。 */
    private static void stage(String stage, Runnable action) {
        try {
            action.run();
        } catch (DockerEngineException e) {
            throw new StageFailure(stage, e.getMessage());
        }
    }

    private void waitUntilHealthy(ComputeTarget target, ContainerRef wanted, ComputeDevice device) {
        String stage = device + "構成の起動確認";
        Instant deadline = clock.instant().plus(timeout);
        boolean running = false;
        while (true) {
            running = isRunning(wanted.name());
            if (running && probe.isHealthy(target.healthUrl())) {
                return;
            }
            if (!clock.instant().isBefore(deadline)) {
                break;
            }
            sleep(stage);
        }
        String condition = running
                ? target.healthUrl() + " が HTTP 200 を返す"
                : "コンテナ " + wanted.name() + " が running になる";
        throw new StageFailure(stage, timeout.toSeconds() + "秒以内に " + condition + " 状態になりませんでした");
    }

    private void sleep(String stage) {
        try {
            sleeper.sleep(pollInterval);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new StageFailure(stage, "待機が中断されました");
        }
    }

    private boolean isRunning(String containerName) {
        return docker.listContainers().stream()
                .anyMatch(ref -> ref.name().equals(containerName) && ref.running());
    }

    // ---- 失敗時の復帰 ----

    private void fail(ComputeTarget target, Snapshot original, ComputeDevice device, ApplyStatus accepted, String reason) {
        finish(target, accepted, ApplyState.FAILED, reason + " " + rollback(target, original, device));
    }

    /** 選んだ構成を止め、元の構成を起動し直す。結果を利用者向けの文にして返す。 */
    private String rollback(ComputeTarget target, Snapshot original, ComputeDevice device) {
        Set<ComputeDevice> wasRunning = EnumSet.noneOf(ComputeDevice.class);
        if (original.gpu() != null && original.gpu().running()) {
            wasRunning.add(ComputeDevice.GPU);
        }
        if (original.cpu() != null && original.cpu().running()) {
            wasRunning.add(ComputeDevice.CPU);
        }
        ContainerRef chosen = device == ComputeDevice.GPU ? original.gpu() : original.cpu();
        StringBuilder result = new StringBuilder();
        if (!wasRunning.contains(device)) {
            try {
                docker.stopContainer(chosen.id());
            } catch (DockerEngineException e) {
                result.append("選んだ構成(").append(device).append(")の停止にも失敗しました(")
                        .append(e.getMessage()).append(")。");
            }
        }
        if (wasRunning.isEmpty()) {
            return result.append("元々どちらも停止していたため、選んだ構成を停止しました。").toString();
        }
        StringJoiner names = new StringJoiner(" と ");
        wasRunning.forEach(d -> names.add(d.name()));
        boolean restored = true;
        for (ComputeDevice d : wasRunning) {
            restored &= restore(d == ComputeDevice.GPU ? original.gpu() : original.cpu());
        }
        if (restored) {
            return result.append("元の構成(").append(names).append(")に戻しました。").toString();
        }
        StringJoiner commands = new StringJoiner(" / ");
        wasRunning.forEach(d -> commands.add(
                "docker start " + (d == ComputeDevice.GPU ? target.gpuContainerName() : target.cpuContainerName())));
        return result.append("元の構成(").append(names).append(")の再起動にも失敗しました。"
                + "ホストで手動で復旧してください: ").append(commands).toString();
    }

    private boolean restore(ContainerRef container) {
        try {
            // 既に動いていれば Docker は 304 を返す(RestDockerEngineClient は成功として扱う)。
            docker.startContainer(container.id());
            Instant deadline = clock.instant().plus(timeout);
            while (!isRunning(container.name())) {
                if (!clock.instant().isBefore(deadline)) {
                    return false;
                }
                sleeper.sleep(pollInterval);
            }
            return true;
        } catch (DockerEngineException e) {
            return false;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    // ---- 状態の保持 ----

    private void finish(ComputeTarget target, ApplyStatus accepted, ApplyState state, String message) {
        synchronized (lock) {
            applyStates.put(target.id(),
                    new ApplyStatus(state, accepted.requestedDevice(), message, accepted.startedAt(), clock.instant()));
        }
    }

    private ApplyStatus applyStatus(ComputeTarget target) {
        synchronized (lock) {
            return applyStates.getOrDefault(target.id(), IDLE);
        }
    }

    private ComputeTarget target(String targetId) {
        ComputeTarget target = targets.get(targetId);
        if (target == null) {
            throw new ComputeDeviceException(HttpStatus.NOT_FOUND, "演算デバイスを切り替えられない対象です: " + targetId);
        }
        return target;
    }

    // ---- Docker Engine APIの見え方 ----

    private record Snapshot(ContainerRef gpu, ContainerRef cpu) {

        CurrentDevice current() {
            boolean gpuRunning = gpu != null && gpu.running();
            boolean cpuRunning = cpu != null && cpu.running();
            if (gpuRunning && cpuRunning) {
                return CurrentDevice.BOTH;
            }
            if (gpuRunning) {
                return CurrentDevice.GPU;
            }
            return cpuRunning ? CurrentDevice.CPU : CurrentDevice.NONE;
        }
    }

    private Snapshot snapshot(ComputeTarget target) {
        List<ContainerRef> containers;
        try {
            containers = docker.listContainers();
        } catch (DockerEngineException e) {
            throw new ComputeDeviceException(HttpStatus.SERVICE_UNAVAILABLE,
                    "Docker Engine API(docker-socket-proxy)に到達できません: " + e.getMessage());
        }
        return new Snapshot(find(containers, target.gpuContainerName()), find(containers, target.cpuContainerName()));
    }

    private static ContainerRef find(List<ContainerRef> containers, String name) {
        return containers.stream().filter(ref -> ref.name().equals(name)).findFirst().orElse(null);
    }
}
