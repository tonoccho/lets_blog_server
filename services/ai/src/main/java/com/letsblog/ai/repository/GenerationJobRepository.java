package com.letsblog.ai.repository;

import com.letsblog.ai.domain.GenerationJob;
import java.time.LocalDateTime;
import java.util.List;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface GenerationJobRepository extends JpaRepository<GenerationJob, Long> {
    Page<GenerationJob> findAllByOrderByCreatedAtDesc(Pageable pageable);

    /**
     * 指定した状態のまま、指定時刻より前から更新されていないジョブ(issue #1083要件4、
     * {@link com.letsblog.ai.service.StaleGenerationJobSweepService}参照)。
     */
    List<GenerationJob> findByStatusAndUpdatedAtBefore(String status, LocalDateTime updatedAtBefore);
}
