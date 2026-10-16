package com.letsblog.publishing.testsupport;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.letsblog.publishing.testsupport.FakeHttpServer.Response;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * provision-agent(HTTP境界)の一覧取得({@code POST /categories}・{@code /tags}・{@code /plugins}・{@code /themes})の
 * フェイク(issue #1815)。リクエスト本文のslug(managedサイトのwpSlug)ごとに、登録した一覧を返す。
 * 登録の無いslugは空の一覧(正常に0件)。{@link #fail}で、そのslugの取得をHTTPエラーにできる。
 */
public final class AgentListings {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** カテゴリ・タグ1件。 */
    public record Term(String name, String slug, String parentSlug, String description) {
    }

    /** プラグイン・テーマ1件。 */
    public record Extension(String name, String status) {
    }

    private final Map<String, List<Term>> categories = new ConcurrentHashMap<>();
    private final Map<String, List<Term>> tags = new ConcurrentHashMap<>();
    private final Map<String, List<Extension>> plugins = new ConcurrentHashMap<>();
    private final Map<String, List<Extension>> themes = new ConcurrentHashMap<>();
    private final Map<String, Response> failures = new ConcurrentHashMap<>();

    public AgentListings(FakeHttpServer agent) {
        agent.on("POST", "/categories", request -> terms(request, "categories", categories));
        agent.on("POST", "/tags", request -> terms(request, "tags", tags));
        agent.on("POST", "/plugins", request -> extensions(request, "plugins", plugins));
        agent.on("POST", "/themes", request -> extensions(request, "themes", themes));
    }

    public AgentListings categories(String wpSlug, Term... items) {
        categories.put(wpSlug, List.of(items));
        return this;
    }

    public AgentListings tags(String wpSlug, Term... items) {
        tags.put(wpSlug, List.of(items));
        return this;
    }

    public AgentListings plugins(String wpSlug, Extension... items) {
        plugins.put(wpSlug, List.of(items));
        return this;
    }

    public AgentListings themes(String wpSlug, Extension... items) {
        themes.put(wpSlug, List.of(items));
        return this;
    }

    /** このslugへの一覧取得を、指定のステータスと本文で失敗させる(全種別)。 */
    public AgentListings fail(String wpSlug, int status, String body) {
        failures.put(wpSlug, Response.json(status, body));
        return this;
    }

    private Response failureOrNull(String slug) {
        return failures.get(slug);
    }

    private static String slugOf(FakeHttpServer.Request request) {
        try {
            return MAPPER.readTree(request.body()).path("slug").asText();
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    private Response terms(FakeHttpServer.Request request, String key, Map<String, List<Term>> source) {
        String slug = slugOf(request);
        if (failureOrNull(slug) != null) {
            return failureOrNull(slug);
        }
        ObjectNode body = MAPPER.createObjectNode();
        ArrayNode array = body.putArray(key);
        for (Term term : source.getOrDefault(slug, List.of())) {
            ObjectNode node = array.addObject().put("name", term.name()).put("slug", term.slug());
            if (term.parentSlug() == null) {
                node.putNull("parentSlug");
            } else {
                node.put("parentSlug", term.parentSlug());
            }
            if (term.description() == null) {
                node.putNull("description");
            } else {
                node.put("description", term.description());
            }
        }
        return Response.ok(body.toString());
    }

    private Response extensions(FakeHttpServer.Request request, String key, Map<String, List<Extension>> source) {
        String slug = slugOf(request);
        if (failureOrNull(slug) != null) {
            return failureOrNull(slug);
        }
        ObjectNode body = MAPPER.createObjectNode();
        ArrayNode array = body.putArray(key);
        for (Extension extension : source.getOrDefault(slug, List.of())) {
            array.addObject().put("name", extension.name()).put("status", extension.status());
        }
        return Response.ok(body.toString());
    }

    /** 受けた一覧取得要求のslugを、受けた順に返す。 */
    public static List<String> requestedSlugs(FakeHttpServer agent, String path) {
        List<String> slugs = new ArrayList<>();
        for (FakeHttpServer.Request request : agent.requests("POST", path)) {
            slugs.add(slugOf(request));
        }
        return slugs;
    }
}
