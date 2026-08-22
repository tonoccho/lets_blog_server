package com.letsblog.api.dto;

/**
 * 校正で検出した1件の指摘(issue #523)。typeは"typo"(誤字脱字) / "readability"(読みやすさ) /
 * "unnecessary"(冗長な表現)のいずれか。originalTextは本文中の該当箇所を特定するための、
 * 元テキストに実在する引用そのもの(エディタ側での位置特定に使う)。suggestionは直接の
 * 置き換え候補が無い指摘(readabilityなど)ではnullになりうる。
 */
public record ProofreadIssue(String type, String originalText, String message, String suggestion) {
}
