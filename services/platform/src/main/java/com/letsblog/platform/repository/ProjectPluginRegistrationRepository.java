package com.letsblog.platform.repository;

import com.letsblog.platform.domain.PluginKind;
import com.letsblog.platform.domain.ProjectPluginRegistration;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface ProjectPluginRegistrationRepository extends JpaRepository<ProjectPluginRegistration, Long> {

    List<ProjectPluginRegistration> findByProjectIdOrderByKindAscIdAsc(Long projectId);

    List<ProjectPluginRegistration> findByProjectIdAndKindOrderByIdAsc(Long projectId, PluginKind kind);

    long countByProjectIdAndKind(Long projectId, PluginKind kind);

    Optional<ProjectPluginRegistration> findFirstByProjectIdAndKindAndInUseTrue(Long projectId, PluginKind kind);

    /** 他のプロジェクトの登録を取り違えないよう、IDはプロジェクトと合わせて引く。 */
    Optional<ProjectPluginRegistration> findByIdAndProjectId(Long id, Long projectId);
}
