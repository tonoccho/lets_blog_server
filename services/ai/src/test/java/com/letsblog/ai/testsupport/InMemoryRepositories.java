package com.letsblog.ai.testsupport;

import com.letsblog.ai.domain.ArticlePlanSession;
import com.letsblog.ai.domain.GenerationJob;
import com.letsblog.ai.domain.ProjectAiSettings;
import com.letsblog.ai.domain.ProjectReviewStepSetting;
import com.letsblog.ai.domain.ReviewStepKey;
import com.letsblog.ai.repository.ArticlePlanSessionRepository;
import com.letsblog.ai.repository.GenerationJobRepository;
import com.letsblog.ai.repository.ProjectAiSettingsRepository;
import com.letsblog.ai.repository.ProjectReviewStepSettingRepository;
import org.mockito.Mockito;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.when;

/**
 * DB(Spring Dataのリポジトリ)の代わりのメモリ上の行(issue #1823)。リポジトリは「外部との通信」なので
 * Mockitoで差し替えてよいが、保存した行を覚え、{@code findById}などがそれを返すようにして、
 * 呼び出し側の{@code *Service}は実物のまま通す。
 */
public final class InMemoryRepositories {

    private InMemoryRepositories() {
    }

    /** 行と、保存の呼び出し履歴。 */
    public static final class Store<T> {
        public final Map<Long, T> rows = new ConcurrentHashMap<>();
        public final List<T> saved = new java.util.concurrent.CopyOnWriteArrayList<>();
        /** 保存のたびに、その時点の状態を文字列にして残す(エンティティはミュータブルなので、後から推移を確かめられるように)。 */
        public final List<String> log = new java.util.concurrent.CopyOnWriteArrayList<>();
        final AtomicLong ids = new AtomicLong(0);
    }

    public static ProjectAiSettingsRepository projectAiSettings(Store<ProjectAiSettings> store) {
        ProjectAiSettingsRepository repository = Mockito.mock(ProjectAiSettingsRepository.class);
        when(repository.findByProjectId(any())).thenAnswer(inv -> store.rows.values().stream()
                .filter(row -> row.getProjectId().equals(inv.getArgument(0))).findFirst());
        when(repository.save(any(ProjectAiSettings.class))).thenAnswer(inv -> {
            ProjectAiSettings row = inv.getArgument(0);
            if (row.getId() == null) {
                row.setId(store.ids.incrementAndGet());
            }
            store.rows.put(row.getId(), row);
            store.saved.add(row);
            return row;
        });
        doAnswer(inv -> {
            store.rows.values().removeIf(row -> row.getProjectId().equals(inv.getArgument(0)));
            return null;
        }).when(repository).deleteByProjectId(any());
        return repository;
    }

    public static GenerationJobRepository generationJobs(Store<GenerationJob> store) {
        GenerationJobRepository repository = Mockito.mock(GenerationJobRepository.class);
        when(repository.save(any(GenerationJob.class))).thenAnswer(inv -> {
            GenerationJob row = inv.getArgument(0);
            if (row.getId() == null) {
                row.setId(store.ids.incrementAndGet());
            }
            if (row.getCreatedAt() == null) {
                row.setCreatedAt(LocalDateTime.now());
            }
            if (row.getUpdatedAt() == null) {
                row.setUpdatedAt(LocalDateTime.now());
            }
            store.rows.put(row.getId(), row);
            store.saved.add(row);
            store.log.add(row.getType() + ":" + row.getStatus());
            return row;
        });
        when(repository.findById(anyLong())).thenAnswer(inv -> Optional.ofNullable(store.rows.get(inv.<Long>getArgument(0))));
        when(repository.findByOwnerUserId(anyLong())).thenAnswer(inv -> store.rows.values().stream()
                .filter(row -> inv.<Long>getArgument(0).equals(row.getOwnerUserId())).toList());
        when(repository.findByStatusAndUpdatedAtBefore(anyString(), any())).thenAnswer(inv -> store.rows.values().stream()
                .filter(row -> row.getStatus().equals(inv.getArgument(0)))
                .filter(row -> row.getUpdatedAt().isBefore(inv.getArgument(1))).toList());
        when(repository.findByTypeAndStatus(anyString(), anyString())).thenAnswer(inv -> store.rows.values().stream()
                .filter(row -> row.getType().equals(inv.getArgument(0)) && row.getStatus().equals(inv.getArgument(1)))
                .toList());
        return repository;
    }

    public static ArticlePlanSessionRepository articlePlanSessions(Store<ArticlePlanSession> store) {
        ArticlePlanSessionRepository repository = Mockito.mock(ArticlePlanSessionRepository.class);
        when(repository.save(any(ArticlePlanSession.class))).thenAnswer(inv -> {
            ArticlePlanSession row = inv.getArgument(0);
            if (row.getId() == null) {
                row.setId(store.ids.incrementAndGet());
            }
            if (row.getCreatedAt() == null) {
                row.setCreatedAt(LocalDateTime.now());
            }
            if (row.getUpdatedAt() == null) {
                row.setUpdatedAt(LocalDateTime.now());
            }
            store.rows.put(row.getId(), row);
            store.saved.add(row);
            return row;
        });
        when(repository.findById(anyLong())).thenAnswer(inv -> Optional.ofNullable(store.rows.get(inv.<Long>getArgument(0))));
        when(repository.findByProjectIdOrderByUpdatedAtDescIdDesc(anyLong())).thenAnswer(inv -> {
            List<ArticlePlanSession> result = new ArrayList<>(store.rows.values().stream()
                    .filter(row -> row.getProjectId().equals(inv.getArgument(0))).toList());
            result.sort(Comparator.comparing(ArticlePlanSession::getUpdatedAt).thenComparing(ArticlePlanSession::getId).reversed());
            return result;
        });
        when(repository.findFirstByProjectIdAndGithubIssueNumberOrderByUpdatedAtDescIdDesc(anyLong(), any()))
                .thenAnswer(inv -> store.rows.values().stream()
                        .filter(row -> row.getProjectId().equals(inv.getArgument(0))
                                && java.util.Objects.equals(row.getGithubIssueNumber(), inv.getArgument(1)))
                        .max(Comparator.comparing(ArticlePlanSession::getUpdatedAt).thenComparing(ArticlePlanSession::getId)));
        return repository;
    }

    public static ProjectReviewStepSettingRepository reviewStepSettings(Store<ProjectReviewStepSetting> store) {
        ProjectReviewStepSettingRepository repository = Mockito.mock(ProjectReviewStepSettingRepository.class);
        when(repository.save(any(ProjectReviewStepSetting.class))).thenAnswer(inv -> {
            ProjectReviewStepSetting row = inv.getArgument(0);
            if (row.getId() == null) {
                row.setId(store.ids.incrementAndGet());
            }
            store.rows.put(row.getId(), row);
            store.saved.add(row);
            return row;
        });
        when(repository.findByProjectIdAndStepKey(anyLong(), any(ReviewStepKey.class))).thenAnswer(inv -> store.rows.values().stream()
                .filter(row -> row.getProjectId().equals(inv.getArgument(0)) && row.getStepKey() == inv.getArgument(1))
                .findFirst());
        when(repository.findByProjectId(anyLong())).thenAnswer(inv -> store.rows.values().stream()
                .filter(row -> row.getProjectId().equals(inv.getArgument(0))).toList());
        return repository;
    }
}
