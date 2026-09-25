package com.letsblog.ai.service;

import com.letsblog.ai.domain.GenerationJob;
import com.letsblog.ai.repository.GenerationJobRepository;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * StaleGenerationJobSweepServiceの回帰テスト(issue #1083要件4)。
 *
 * <p>media-service側(ModelInstallJobRunner/GenerationJobClient)の認証・再試行を修正しても、
 * ai-serviceへの終端通知そのものが最終的に届かないケース(ネットワーク分断が長引く等)は
 * 残りうる。generation_jobsの所有権はai-service(#574)にあるため、running状態のまま
 * 一定時間更新が無いジョブを検出し解消するタイムアウト機構は、media-serviceではなく
 * ai-service側に置く。
 */
@ExtendWith(MockitoExtension.class)
class StaleGenerationJobSweepServiceTest {

    @Mock
    private GenerationJobRepository generationJobRepository;

    private StaleGenerationJobSweepService service() {
        return new StaleGenerationJobSweepService(generationJobRepository, 15L);
    }

    @Test
    void 長時間runningのままのジョブをfailedとして解消する() {
        GenerationJob stale = new GenerationJob();
        stale.setId(42L);
        stale.setType("comfyui_checkpoint_download");
        stale.setStatus("running");
        stale.setUpdatedAt(LocalDateTime.now().minusMinutes(30));

        when(generationJobRepository.findByStatusAndUpdatedAtBefore(eq("running"), any(LocalDateTime.class)))
                .thenReturn(List.of(stale));
        when(generationJobRepository.save(any(GenerationJob.class))).thenAnswer(invocation -> invocation.getArgument(0));

        service().sweepStaleRunningJobs();

        ArgumentCaptor<GenerationJob> saved = ArgumentCaptor.forClass(GenerationJob.class);
        verify(generationJobRepository).save(saved.capture());
        assertThat(saved.getValue().getId()).isEqualTo(42L);
        assertThat(saved.getValue().getStatus()).isEqualTo("failed");
        assertThat(saved.getValue().getResultPayload()).contains("stale_running_timeout");
    }

    @Test
    void 該当ジョブが無ければ何も保存しない() {
        when(generationJobRepository.findByStatusAndUpdatedAtBefore(eq("running"), any(LocalDateTime.class)))
                .thenReturn(List.of());

        service().sweepStaleRunningJobs();

        verify(generationJobRepository, never()).save(any());
    }

    @Test
    void 複数の停滞ジョブをまとめて解消する() {
        GenerationJob first = new GenerationJob();
        first.setId(1L);
        first.setStatus("running");
        GenerationJob second = new GenerationJob();
        second.setId(2L);
        second.setStatus("running");

        when(generationJobRepository.findByStatusAndUpdatedAtBefore(eq("running"), any(LocalDateTime.class)))
                .thenReturn(List.of(first, second));
        when(generationJobRepository.save(any(GenerationJob.class))).thenAnswer(invocation -> invocation.getArgument(0));

        service().sweepStaleRunningJobs();

        verify(generationJobRepository).save(first);
        verify(generationJobRepository).save(second);
        assertThat(first.getStatus()).isEqualTo("failed");
        assertThat(second.getStatus()).isEqualTo("failed");
    }
}
