package com.letsblog.media.integration;

import com.letsblog.media.ai.GeneratedImageSequenceService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * issue #1642: 同一プロジェクトへの並行する初回採番が、デッドロックせず重複なく進むことを実DBで検証する。
 * 呼び出し側のリトライに頼らず、{@link GeneratedImageSequenceService#nextSequence} 単体で成功すること。
 */
@SpringBootTest
@ActiveProfiles("test")
@DisplayName("media-service: 生成画像連番の並行初回採番(issue #1642)")
class GeneratedImageSequenceConcurrencyIntegrationTest {

    private static final int ROUNDS = 15;
    private static final int THREADS = 8;

    @Autowired
    private GeneratedImageSequenceService service;

    @Autowired
    private JdbcTemplate jdbc;

    @AfterEach
    void cleanUp() {
        jdbc.update("DELETE FROM generated_image_sequences WHERE project_key LIKE 'it1642-%'");
    }

    @Test
    @DisplayName("同一プロジェクトへの並行する初回採番がすべて成功し、番号が重複しない")
    void concurrentFirstAllocation_succeedsWithoutDuplicates() throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(THREADS);
        try {
            for (int round = 0; round < ROUNDS; round++) {
                String key = "it1642-" + round;
                CountDownLatch start = new CountDownLatch(1);
                List<Future<Integer>> futures = new ArrayList<>();
                for (int i = 0; i < THREADS; i++) {
                    futures.add(pool.submit(() -> {
                        start.await();
                        return service.nextSequence(key);
                    }));
                }
                start.countDown();
                List<Integer> results = new ArrayList<>();
                for (Future<Integer> f : futures) {
                    results.add(f.get());
                }
                assertThat(results).doesNotHaveDuplicates();
                assertThat(results).containsExactlyInAnyOrder(1, 2, 3, 4, 5, 6, 7, 8);
            }
        } finally {
            pool.shutdownNow();
        }
    }
}
