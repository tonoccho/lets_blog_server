package com.letsblog.project.service;

import com.letsblog.project.client.FacebookApiClient;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * Facebook の認可のあと、利用者が投稿先のページを選ぶまでの間だけ、管理しているページ(ページのトークンを含む)を
 * メモリに短時間持つ(issue #1580)。DB・ファイルには書かない。選んで本番サイトへ送れたら消し、期限(既定10分)を
 * 過ぎたものは取り出せない。キーは認可の state(認可を始めた本人・プロジェクトを突き合わせる)。
 */
@Component
public class FacebookPageSelectionStore {

    private static final Duration DEFAULT_TTL = Duration.ofMinutes(10);

    private final Map<String, Selection> selections = new ConcurrentHashMap<>();
    private final Clock clock;
    private final Duration ttl;

    @Autowired
    public FacebookPageSelectionStore() {
        this(Clock.systemUTC(), DEFAULT_TTL);
    }

    FacebookPageSelectionStore(Clock clock, Duration ttl) {
        this.clock = clock;
        this.ttl = ttl;
    }

    public Selection save(
            String state, Long projectId, Long siteId, String actorSub, List<FacebookApiClient.Page> pages) {
        Instant now = clock.instant();
        selections.values().removeIf(selection -> selection.expiresAt().isBefore(now));
        Selection selection = new Selection(state, projectId, siteId, actorSub, List.copyOf(pages), now.plus(ttl));
        selections.put(state, selection);
        return selection;
    }

    /** 取り出しても消えない(消すのは選んで送れたとき)。期限切れ・未知の state は空。 */
    public Optional<Selection> find(String state) {
        if (state == null) {
            return Optional.empty();
        }
        Selection selection = selections.get(state);
        if (selection == null || selection.expiresAt().isBefore(clock.instant())) {
            return Optional.empty();
        }
        return Optional.of(selection);
    }

    public void remove(String state) {
        selections.remove(state);
    }

    int size() {
        return selections.size();
    }

    /** 選択の途中経過。ページのトークンを持つので、値は表示しない。 */
    public record Selection(
            String state,
            Long projectId,
            Long siteId,
            String actorSub,
            List<FacebookApiClient.Page> pages,
            Instant expiresAt) {

        @Override
        public String toString() {
            return "Selection[state=" + state + ", projectId=" + projectId + "]";
        }
    }
}
