package com.letsblog.api.repository;

import com.letsblog.api.domain.BufferPost;
import org.springframework.data.jpa.repository.JpaRepository;

public interface BufferPostRepository extends JpaRepository<BufferPost, Long> {
}
