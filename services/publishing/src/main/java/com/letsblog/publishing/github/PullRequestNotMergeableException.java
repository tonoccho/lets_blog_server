package com.letsblog.publishing.github;

/**
 * Pull Requestをマージできない(コンフリクト・draft・保護規則による拒否・マージ可否が未確定・マージ済み)こと
 * (issue #1343)。メッセージは利用者にそのまま見せる前提で、原因が判別できる文面にする。
 * 強制マージは行わないので、呼び出し側は原因を解消してから再度呼ぶ。{@code GlobalExceptionHandler}が409へ写す。
 */
public class PullRequestNotMergeableException extends RuntimeException {

    public PullRequestNotMergeableException(String message) {
        super(message);
    }
}
