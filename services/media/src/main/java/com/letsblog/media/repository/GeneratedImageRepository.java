package com.letsblog.media.repository;

import com.letsblog.media.domain.GeneratedImage;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
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

    /** 指定フォルダ群のいずれかに属する画像の枚数(issue #1494、削除の影響範囲)。 */
    long countByFolderIdIn(Collection<Long> folderIds);

    /** 指定フォルダ群に属する画像をすべて未分類(folder_id = NULL)に戻す。画像自体は消さない(issue #1494)。 */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("UPDATE GeneratedImage i SET i.folderId = NULL WHERE i.folderId IN :folderIds")
    int clearFolder(@Param("folderIds") Collection<Long> folderIds);
}
