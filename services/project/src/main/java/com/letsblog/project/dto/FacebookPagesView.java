package com.letsblog.project.dto;

import java.util.List;

/** 認可のあとに投稿先として選べる Facebook ページの一覧(issue #1580)。トークンは含まない。 */
public record FacebookPagesView(Long projectId, List<FacebookPageView> pages) {
}
