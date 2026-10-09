package com.letsblog.identity.integration;

import com.letsblog.identity.keycloak.KeycloakAdminClient;
import com.letsblog.identity.repository.UserRepository;
import com.letsblog.identity.service.UserService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * issue #1718: {@code POST /api/auth/setup}が同時に届いても、作られる初回管理者は高々1人。
 *
 * <p>実MySQLに対して行う理由: 守る性質は「確認(users空か)から保存までの原子性」であり、
 * 行ロックの有無はDBでしか観測できない({@code LastAdminGuardIntegrationTest}と同じ方式)。
 * HTTP越しに同時に叩く決定的な再現は共有の受け入れ環境(他の利用者が既にいる)では作れないため、
 * サービス層を直接並行に呼ぶ。Keycloak呼び出しには遅延を入れ、確認と保存の間の窓を広げる。
 */
@SpringBootTest
@ActiveProfiles("test")
@DisplayName("identity-service: 初回管理者セットアップの同時実行(issue #1718)")
class SetupInitialAdminRaceIntegrationTest {

    private static final int RACE_ROUNDS = 5;
    private static final int CONCURRENCY = 4;

    @Autowired
    private UserService userService;

    @Autowired
    private UserRepository userRepository;

    @MockitoBean
    private KeycloakAdminClient keycloakAdminClient;

    private final AtomicInteger subSeq = new AtomicInteger();

    @BeforeEach
    void setUp() {
        userRepository.deleteAll();
        reset(keycloakAdminClient);
        when(keycloakAdminClient.createUser(anyString(), any(), any(), anyBoolean())).thenAnswer(inv -> {
            Thread.sleep(150);
            return "sub-1718-" + subSeq.incrementAndGet();
        });
    }

    @Test
    @DisplayName("メールの異なるセットアップが同時に届いても、成功は1件・usersは1件で、負けた側はKeycloakに何も作らない")
    void 同時セットアップでも管理者は一人だけ() throws Exception {
        for (int round = 0; round < RACE_ROUNDS; round++) {
            userRepository.deleteAll();
            reset(keycloakAdminClient);
            when(keycloakAdminClient.createUser(anyString(), any(), any(), anyBoolean())).thenAnswer(inv -> {
                Thread.sleep(150);
                return "sub-1718-" + subSeq.incrementAndGet();
            });

            int succeeded = race(round);

            assertThat(succeeded).as("round %d: 成功したセットアップの数", round).isEqualTo(1);
            assertThat(userRepository.count()).as("round %d: users", round).isEqualTo(1);
            // 直列化がKeycloak呼び出しより前にあるので、負けた側はKeycloakを一切呼ばない。
            verify(keycloakAdminClient, times(1)).createUser(anyString(), any(), any(), anyBoolean());
            verify(keycloakAdminClient, never()).deleteUser(anyString());
        }
    }

    private int race(int round) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(CONCURRENCY);
        try {
            CountDownLatch ready = new CountDownLatch(CONCURRENCY);
            CountDownLatch go = new CountDownLatch(1);
            List<Future<Boolean>> futures = new ArrayList<>();
            for (int i = 0; i < CONCURRENCY; i++) {
                String email = "admin-1718-" + round + "-" + i + "@example.test";
                Callable<Boolean> task = () -> {
                    ready.countDown();
                    go.await();
                    try {
                        userService.setupInitialAdmin(email, "password123");
                        return true;
                    } catch (IllegalArgumentException refused) {
                        return false;
                    }
                };
                futures.add(pool.submit(task));
            }
            ready.await();
            go.countDown();
            int succeeded = 0;
            for (Future<Boolean> future : futures) {
                if (future.get()) {
                    succeeded++;
                }
            }
            return succeeded;
        } finally {
            pool.shutdownNow();
        }
    }
}
