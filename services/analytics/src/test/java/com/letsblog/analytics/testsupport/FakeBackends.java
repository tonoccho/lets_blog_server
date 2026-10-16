package com.letsblog.analytics.testsupport;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * analytics-service が HTTP で呼ぶ他サービス(identity-service・project-service)のサーバー側フェイクの応答(issue #1827)。
 * {@link FakeHttpServer}に既定のルートを載せ、操作者・プロジェクトメンバー・本番サイトの有無は{@link #actor}・
 * {@link #member}・{@link #project}で登録する。
 */
public final class FakeBackends {

    private final Map<String, String> actors = new ConcurrentHashMap<>();
    private final Set<String> members = ConcurrentHashMap.newKeySet();
    private final Map<Long, Boolean> productionSites = new ConcurrentHashMap<>();

    public FakeBackends(FakeHttpServer http) {
        // identity-service: 操作者の解決。未登録のトークンは401(操作者なし)。
        http.on("GET", "/api/identity/me", request -> {
            String profile = actors.get(request.header("Authorization"));
            return profile == null ? FakeHttpServer.Response.json(401, "{}") : FakeHttpServer.Response.ok(profile);
        });
        // identity-service: プロジェクトメンバー判定。
        http.on("GET", "/api/internal/identity/projects/(\\d+)/members/(\\d+)", request -> {
            String[] parts = request.path().split("/");
            boolean member = members.contains(parts[5] + ":" + parts[7]);
            return FakeHttpServer.Response.ok(String.valueOf(member));
        });
        // project-service: 本番サイトの有無。未登録のプロジェクトは404。
        http.on("GET", "/api/internal/project/projects/(\\d+)/eligibility", request -> {
            Boolean site = productionSites.get(Long.parseLong(request.path().split("/")[5]));
            return site == null
                    ? FakeHttpServer.Response.json(404, "{}")
                    : FakeHttpServer.Response.ok("{\"hasProductionSite\":" + site + "}");
        });
        // project-service: GA 連携の完了通知。
        http.on("POST", "/api/internal/project/projects/(\\d+)/sns/pv/sync", FakeHttpServer.Response.empty(204));
    }

    /** identity-service が、このBearerを「userId=id・role」の操作者として解決する。 */
    public FakeBackends actor(String bearerToken, long id, String role) {
        actors.put("Bearer " + bearerToken, "{\"id\":" + id + ",\"role\":\"" + role + "\"}");
        return this;
    }

    /** identity-service が、userId をプロジェクトのメンバーとして答える。 */
    public FakeBackends member(long projectId, long userId) {
        members.add(projectId + ":" + userId);
        return this;
    }

    /** project-service が、プロジェクトの本番サイトの有無を答える。 */
    public FakeBackends project(long projectId, boolean hasProductionSite) {
        productionSites.put(projectId, hasProductionSite);
        return this;
    }

    /** 登録した操作者・メンバー・プロジェクトを捨てる。 */
    public void reset() {
        actors.clear();
        members.clear();
        productionSites.clear();
    }
}
