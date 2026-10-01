package com.letsblog.publishing.service;

/**
 * 提出対象のheadブランチがGitHubに無い(issue #1339)。メッセージは利用者にそのまま見せる。
 * {@code GlobalExceptionHandler}が404へ写す。
 */
public class BranchNotFoundException extends RuntimeException {

    public BranchNotFoundException(String message) {
        super(message);
    }
}
