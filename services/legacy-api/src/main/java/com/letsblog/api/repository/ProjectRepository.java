package com.letsblog.api.repository;

import com.letsblog.api.domain.Project;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * プロジェクトのGitHubトークン(project.githubTokenEncrypted)の読み書き専用(issue #577スコープ外の
 * {@link com.letsblog.api.service.ProjectApiKeyService}が使う。issue #577 stage3で、プロジェクトの
 * 基本情報の読み取りは{@code ProjectServiceClient}経由のproject-service参照へ切り替えたため、
 * それ以外の用途では使わない)。
 */
public interface ProjectRepository extends JpaRepository<Project, Long> {
}
