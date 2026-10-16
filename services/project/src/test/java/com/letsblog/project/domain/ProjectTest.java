package com.letsblog.project.domain;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Projectエンティティの、GitHubリポジトリ・トークンが設定されているかの判定(issue #1818)。実物のエンティティを使う。 */
class ProjectTest {

    @Test
    void isGithubRepositoryConfigured_nullと空白は未設定_値があれば設定済み() {
        Project project = new Project();
        assertFalse(project.isGithubRepositoryConfigured());

        project.setGithubRepository("  ");
        assertFalse(project.isGithubRepositoryConfigured());

        project.setGithubRepository("owner/repo");
        assertTrue(project.isGithubRepositoryConfigured());
    }

    @Test
    void hasGithubToken_nullと空は未設定_中身があれば設定済み() {
        Project project = new Project();
        assertFalse(project.hasGithubToken());

        project.setGithubTokenEncrypted(new byte[0]);
        assertFalse(project.hasGithubToken());

        project.setGithubTokenEncrypted(new byte[] {1});
        assertTrue(project.hasGithubToken());
    }
}
