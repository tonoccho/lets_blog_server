package com.letsblog.media.repository;

import com.letsblog.media.domain.GeneratedImageSequence;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

import java.util.Optional;

public interface GeneratedImageSequenceRepository extends JpaRepository<GeneratedImageSequence, String> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select s from GeneratedImageSequence s where s.projectKey = :projectKey")
    Optional<GeneratedImageSequence> findByProjectKeyForUpdate(String projectKey);
}
