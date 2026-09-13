package com.letsblog.identity.service;

/**
 * issue #1241: 対象ユーザーは存在するが、アバターがまだアップロードされていない場合に送出する。
 */
public class AvatarNotFoundException extends RuntimeException {
    public AvatarNotFoundException(String message) {
        super(message);
    }
}
