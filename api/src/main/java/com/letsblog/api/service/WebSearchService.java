package com.letsblog.api.service;

import com.letsblog.api.ai.BraveSearchClient;
import org.springframework.stereotype.Service;

/**
 * 壁打ちチャットの応答生成前に行うWeb検索。検索の失敗(APIキー未設定・タイムアウト・レート制限等)で
 * チャット機能全体を止めないよう、フェイルオープンで結果を返す。
 */
@Service
public class WebSearchService {

    private static final int SEARCH_RESULT_COUNT = 5;

    private final BraveSearchClient braveSearchClient;

    public WebSearchService(BraveSearchClient braveSearchClient) {
        this.braveSearchClient = braveSearchClient;
    }

    public WebSearchOutcome searchSafely(String query) {
        try {
            return WebSearchOutcome.success(braveSearchClient.search(query, SEARCH_RESULT_COUNT));
        } catch (RuntimeException e) {
            return WebSearchOutcome.failure(e.getMessage());
        }
    }
}
