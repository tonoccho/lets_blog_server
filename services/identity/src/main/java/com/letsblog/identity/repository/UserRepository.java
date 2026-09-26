package com.letsblog.identity.repository;

import com.letsblog.identity.domain.User;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

import java.util.List;
import java.util.Optional;

public interface UserRepository extends JpaRepository<User, Long> {
    Optional<User> findByEmail(String email);
    boolean existsByEmail(String email);
    Optional<User> findByKeycloakSub(String keycloakSub);

    /** 移行対象(#562の一括移行スクリプト)。まだKeycloakへ登録されていないユーザー。 */
    List<User> findByKeycloakSubIsNull();

    /** 孤児検出(#562)対象。既にKeycloakへ登録済みのユーザー。 */
    List<User> findByKeycloakSubIsNotNull();

    /**
     * 有効な(enabled=true)adminの全行を悲観ロック({@code SELECT ... FOR UPDATE})付きで取得する(#1162)。
     *
     * <p>最後のadminの削除・無効化を拒否するための「数える」処理を、他のトランザクションによる
     * 同種の変更と直列化する。ロックは呼び出し側のトランザクションが終わるまで保持されるため、
     * 数える→変更する、が原子的になる(検査時-使用時の穴を塞ぐ)。
     *
     * <p>{@code ORDER BY id}で全トランザクションが同じ順序でロックを取り、デッドロックを避ける。
     * 必ず{@code @Transactional}の内側から呼ぶこと(外側で呼ぶとロックが即時に解放される)。
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select u from User u where u.role = 'admin' and u.enabled = true order by u.id")
    List<User> lockEnabledAdmins();
}
