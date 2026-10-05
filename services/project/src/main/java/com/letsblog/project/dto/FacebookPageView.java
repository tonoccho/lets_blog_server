package com.letsblog.project.dto;

/** 投稿先に選べる Facebook ページ(issue #1580)。トークンは含まない。 */
public record FacebookPageView(String id, String name) {
}
