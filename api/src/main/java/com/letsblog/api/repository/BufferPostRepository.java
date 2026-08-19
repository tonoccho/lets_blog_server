package com.letsblog.api.repository;

import com.letsblog.api.domain.BufferPost;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface BufferPostRepository extends JpaRepository<BufferPost, Long> {

    /** issue #390: プロジェクトダッシュボードのソーシャル統計ウィジェット向けに、送信済みのBuffer投稿を集める。 */
    List<BufferPost> findBySiteIdAndStatus(Long siteId, String status);
}
