package com.letsblog.media.repository;

import com.letsblog.media.domain.GeneratedImageSequence;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

import java.util.Optional;

public interface GeneratedImageSequenceRepository extends JpaRepository<GeneratedImageSequence, String> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select s from GeneratedImageSequence s where s.projectKey = :projectKey")
    Optional<GeneratedImageSequence> findByProjectKeyForUpdate(String projectKey);

    /**
     * 行が無ければ作る(あっても何もしない)。存在しない行への SELECT ... FOR UPDATE は
     * ギャップロックになり、並行する初回 INSERT 同士がデッドロックするため、先に行を確保する。
     */
    @Modifying
    @Query(value = "INSERT INTO generated_image_sequences (project_key, last_seq, updated_at) "
            + "VALUES (:projectKey, 0, CURRENT_TIMESTAMP) "
            + "ON DUPLICATE KEY UPDATE project_key = project_key", nativeQuery = true)
    void insertIfAbsent(String projectKey);
}
