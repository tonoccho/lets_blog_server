package com.letsblog.media.repository;

import com.letsblog.media.domain.GeneratedImage;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

/**
 * 一覧は{@code createdAt}の降順、同時刻は{@code id}の降順に並べる(issue #1472)。
 * offsetでページを切るとき、順序が一意でないと境界の行が前後のページに重複・欠落するため。
 */
public interface GeneratedImageRepository extends JpaRepository<GeneratedImage, Long> {
    List<GeneratedImage> findAllByOrderByCreatedAtDescIdDesc();
    List<GeneratedImage> findAllByOrderByCreatedAtDescIdDesc(Pageable pageable);
    List<GeneratedImage> findAllByProjectIdOrderByCreatedAtDescIdDesc(Long projectId);
    List<GeneratedImage> findAllByProjectIdOrderByCreatedAtDescIdDesc(Long projectId, Pageable pageable);
}
