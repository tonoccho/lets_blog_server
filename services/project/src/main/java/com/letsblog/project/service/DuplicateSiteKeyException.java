package com.letsblog.project.service;

/**
 * siteKeyが既に登録されている(issue #1479)。同期経路が投げる{@link IllegalArgumentException}と
 * 同じ扱いにしつつ、ジョブの失敗理由で「重複」を他の入力不備と区別できるように型を分けた。
 */
public class DuplicateSiteKeyException extends IllegalArgumentException {
    public DuplicateSiteKeyException(String message) {
        super(message);
    }
}
