package com.letsblog.publishing.service;

import com.letsblog.publishing.client.ProjectServiceClient;
import com.letsblog.publishing.domain.Project;
import org.springframework.stereotype.Service;

/**
 * プロジェクトの基本情報の参照。Project本体の所有権はproject-serviceにある(issue #577 stage2)。
 * legacy-apiの{@code com.letsblog.api.service.ProjectService}のうち、一括管理・環境間比較機能
 * ({@link BulkManagementService}等)一式が使う{@code getProjectEntity}のみをpublishing-serviceへ
 * 移設したもの(issue #708、Epic #551 C6-2)。css-selector-prefix・画像生成デフォルト設定等、
 * legacy-api固有の依存を伴う他のメソッドは移設しない(legacy-apiに残る)。
 */
@Service
public class ProjectService {

    private final ProjectServiceClient projectServiceClient;

    public ProjectService(ProjectServiceClient projectServiceClient) {
        this.projectServiceClient = projectServiceClient;
    }

    /** {@link ProjectNotFoundException}を投げる、project-service経由のプロジェクト存在確認+取得。 */
    public Project getProjectEntity(Long projectId) {
        return toProject(projectServiceClient.getProject(projectId));
    }

    private Project toProject(ProjectServiceClient.ProjectBridge bridge) {
        Project project = new Project();
        project.setId(bridge.id());
        project.setLocalSiteId(bridge.localSiteId());
        project.setTestSiteId(bridge.testSiteId());
        project.setProductionSiteId(bridge.productionSiteId());
        project.setMasterEnvironment(bridge.masterEnvironment());
        return project;
    }
}
