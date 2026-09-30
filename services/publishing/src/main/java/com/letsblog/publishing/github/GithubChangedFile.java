package com.letsblog.publishing.github;

/** PRの変更ファイル1件。{@code status}は{@code added}/{@code modified}/{@code removed}等(issue #1338)。 */
public record GithubChangedFile(String path, String status) {

    /** 削除されたファイルはPRのheadには存在しない。 */
    public boolean removed() {
        return "removed".equals(status);
    }
}
