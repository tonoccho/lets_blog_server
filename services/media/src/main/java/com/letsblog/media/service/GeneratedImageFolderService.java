package com.letsblog.media.service;

import com.letsblog.media.domain.GeneratedImageFolder;
import com.letsblog.media.dto.GeneratedImageFolderDeleteImpactResponse;
import com.letsblog.media.repository.GeneratedImageFolderRepository;
import com.letsblog.media.repository.GeneratedImageRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 生成画像フォルダ(入れ子、横断の共通ツリー)の作成・親の変更・階層の解決(issue #1493)。
 * 認可(admin限定か、認証済みなら誰でも閲覧か)は呼び出し側のコントローラが行う。
 */
@Service
public class GeneratedImageFolderService {

    private final GeneratedImageFolderRepository folderRepository;

    private final GeneratedImageRepository imageRepository;

    public GeneratedImageFolderService(GeneratedImageFolderRepository folderRepository,
                                       GeneratedImageRepository imageRepository) {
        this.folderRepository = folderRepository;
        this.imageRepository = imageRepository;
    }

    public List<GeneratedImageFolder> list() {
        return folderRepository.findAllByOrderByIdAsc();
    }

    /** 親がnullなら最上位。親を指定するなら存在しなければならない(循環は新規作成では起こり得ない)。 */
    @Transactional
    public GeneratedImageFolder create(String name, Long parentId) {
        if (parentId != null) {
            requireExists(parentId);
        }
        GeneratedImageFolder folder = new GeneratedImageFolder();
        folder.setName(name.trim());
        folder.setParentId(parentId);
        return folderRepository.save(folder);
    }

    /**
     * 親を付け替える(nullで最上位へ)。新しい親が自分自身または自分の子孫なら循環になるため拒否し、
     * 何も変更しない。自己参照FKは循環を防げないので、再帰CTEで子孫を引いて確かめる。
     */
    @Transactional
    public GeneratedImageFolder changeParent(Long id, Long parentId) {
        GeneratedImageFolder folder = folderRepository.findById(id)
                .orElseThrow(() -> new GeneratedImageFolderNotFoundException("id: " + id));
        if (parentId != null) {
            requireExists(parentId);
            if (descendantIdsIncludingSelf(id).contains(parentId)) {
                throw new FolderHierarchyCycleException(
                        "自分自身または自分の子孫フォルダを親にすると階層が循環します");
            }
        }
        folder.setParentId(parentId);
        return folderRepository.save(folder);
    }

    /** 改名する。同じ親の下の重複名は作成時と同じく検査しない(issue #1494)。 */
    @Transactional
    public GeneratedImageFolder rename(Long id, String name) {
        GeneratedImageFolder folder = folderRepository.findById(id)
                .orElseThrow(() -> new GeneratedImageFolderNotFoundException("id: " + id));
        folder.setName(name.trim());
        return folderRepository.save(folder);
    }

    /** 削除の影響範囲。子孫フォルダ数(自分を含まない)と、自分と子孫に属する画像の枚数。 */
    @Transactional(readOnly = true)
    public GeneratedImageFolderDeleteImpactResponse deleteImpact(Long id) {
        requireExists(id);
        Set<Long> ids = descendantIdsIncludingSelf(id);
        return new GeneratedImageFolderDeleteImpactResponse(ids.size() - 1, imageRepository.countByFolderIdIn(ids));
    }

    /**
     * フォルダと全子孫を削除し、所属画像はすべて未分類へ戻す(画像は消さない)。親子・画像所属のFKは
     * RESTRICTなので、画像の未分類化→葉から順に子孫の削除→自分の削除を1トランザクションで行う
     * (片方だけ成功して、存在しないフォルダを指す画像が残ることを防ぐ)。
     */
    @Transactional
    public void delete(Long id) {
        requireExists(id);
        List<Long> deepestFirst = folderRepository.findSelfAndDescendantIdsDeepestFirst(id);
        imageRepository.clearFolder(deepestFirst);
        deepestFirst.forEach(folderRepository::deleteRowById);
    }

    /** 指定フォルダ自身と全子孫のid。絞り込み条件「このフォルダの中」を解決するのに使う。 */
    public Set<Long> descendantIdsIncludingSelf(Long id) {
        return new HashSet<>(folderRepository.findSelfAndDescendantIds(id));
    }

    public void requireExists(Long id) {
        if (!folderRepository.existsById(id)) {
            throw new GeneratedImageFolderNotFoundException("id: " + id);
        }
    }
}
