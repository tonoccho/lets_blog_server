package com.letsblog.publishing.testsupport;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.letsblog.publishing.testsupport.FakeHttpServer.Response;

import java.util.Base64;
import java.util.regex.Pattern;

/**
 * GitHub REST API(HTTP境界)の応答を{@link FakeHttpServer}へ登録する部品(issue #1815)。
 * {@code GithubPullRequestClient}自体はモックせず、GitHubの向こう側の応答だけを作る。
 */
public final class GithubStubs {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private GithubStubs() {
    }

    /** {@code GET /repos/{o}/{r}/pulls/{n}}。{@code mergeable}がnullなら「未計算」(JSON null)。 */
    public static void pullRequest(FakeHttpServer github, String owner, String repo, int number, String headSha,
            String headRef, Boolean mergeable, boolean merged) {
        ObjectNode body = MAPPER.createObjectNode().put("number", number).put("merged", merged);
        ObjectNode head = body.putObject("head");
        if (headSha != null) {
            head.put("sha", headSha);
        }
        head.put("ref", headRef);
        if (mergeable == null) {
            body.putNull("mergeable");
        } else {
            body.put("mergeable", mergeable);
        }
        github.json("GET", "/repos/" + owner + "/" + repo + "/pulls/" + number, body.toString());
    }

    /**
     * {@code GET /repos/{o}/{r}/pulls/{n}/files}。{@code pathAndStatus}は「パス, 状態, パス, 状態, ...」の並び。
     * 100件ずつ{@code page}クエリで返す(クライアントのページ送りをそのまま通す)。
     */
    public static void pullRequestFiles(FakeHttpServer github, String owner, String repo, int number,
            String... pathAndStatus) {
        github.on("GET", "/repos/" + owner + "/" + repo + "/pulls/" + number + "/files", request -> {
            int page = 1;
            if (request.query() != null) {
                java.util.regex.Matcher m = Pattern.compile("(?:^|&)page=(\\d+)").matcher(request.query());
                if (m.find()) {
                    page = Integer.parseInt(m.group(1));
                }
            }
            ArrayNode array = MAPPER.createArrayNode();
            int total = pathAndStatus.length / 2;
            for (int i = (page - 1) * 100; i < Math.min(total, page * 100); i++) {
                array.addObject().put("filename", pathAndStatus[i * 2]).put("status", pathAndStatus[i * 2 + 1]);
            }
            return Response.ok(array.toString());
        });
    }

    /** {@code GET /repos/{o}/{r}/contents/{path}?ref={ref}}(1MB以下のファイル。base64で内容を返す)。 */
    public static void file(FakeHttpServer github, String owner, String repo, String path, String ref,
            byte[] content) {
        github.on("GET", "/repos/" + owner + "/" + repo + "/contents/" + Pattern.quote(path), request -> {
            if (request.query() == null || !request.query().contains("ref=" + ref)) {
                return null;
            }
            ObjectNode body = MAPPER.createObjectNode().put("encoding", "base64").put("size", content.length)
                    .put("content", Base64.getMimeEncoder().encodeToString(content));
            return Response.ok(body.toString());
        });
    }

    /**
     * 1MBを超えるファイル: contents APIは{@code content}を返さず{@code sha}だけ返し、
     * {@code GET /git/blobs/{sha}}(raw)で中身を読む。
     */
    public static void largeFile(FakeHttpServer github, String owner, String repo, String path, String ref,
            String blobSha, byte[] content) {
        github.on("GET", "/repos/" + owner + "/" + repo + "/contents/" + Pattern.quote(path), request -> {
            if (request.query() == null || !request.query().contains("ref=" + ref)) {
                return null;
            }
            ObjectNode body = MAPPER.createObjectNode().put("sha", blobSha).put("size", content.length)
                    .put("encoding", "none").put("content", "");
            return Response.ok(body.toString());
        });
        github.on("GET", "/repos/" + owner + "/" + repo + "/git/blobs/" + blobSha,
                Response.bytes(200, "application/vnd.github.raw", content));
    }
    private static String repo(String owner, String repo) {
        return "/repos/" + owner + "/" + repo;
    }

    /** {@code GET /repos/{o}/{r}/branches/{branch}}: 在れば200、無ければ404。 */
    public static void branch(FakeHttpServer github, String owner, String repo, String branch, boolean exists) {
        String path = repo(owner, repo) + "/branches/" + branch;
        if (exists) {
            github.json("GET", Pattern.quote(path), "{\"name\":\"" + branch + "\"}");
        } else {
            github.status("GET", Pattern.quote(path), 404, "{\"message\":\"Branch not found\"}");
        }
    }

    /** {@code GET /repos/{o}/{r}/pulls}。{@code rows}は「番号, タイトル, headブランチ, ...」の並び。 */
    public static void openPullRequests(FakeHttpServer github, String owner, String repo, Object... rows) {
        ArrayNode array = MAPPER.createArrayNode();
        for (int i = 0; i < rows.length; i += 3) {
            ObjectNode node = array.addObject().put("number", (Integer) rows[i]).put("title", (String) rows[i + 1])
                    .put("created_at", "2026-09-01T00:00:00Z")
                    .put("html_url", "https://github.com/" + owner + "/" + repo + "/pull/" + rows[i]);
            node.putObject("head").put("ref", (String) rows[i + 2]);
        }
        github.json("GET", Pattern.quote(repo(owner, repo) + "/pulls"), array.toString());
    }

    /** {@code GET /repos/{o}/{r}}(default_branch)。 */
    public static void defaultBranch(FakeHttpServer github, String owner, String repo, String defaultBranch) {
        github.json("GET", Pattern.quote(repo(owner, repo)), "{\"default_branch\":\"" + defaultBranch + "\"}");
    }

    /** {@code POST /repos/{o}/{r}/pulls}: 作成されたPRとして番号とheadを返す。 */
    public static void createPullRequest(FakeHttpServer github, String owner, String repo, int number,
            String headRef) {
        ObjectNode body = MAPPER.createObjectNode().put("number", number).put("title", "t")
                .put("created_at", "2026-09-01T00:00:00Z")
                .put("html_url", "https://github.com/" + owner + "/" + repo + "/pull/" + number);
        body.putObject("head").put("ref", headRef);
        github.on("POST", Pattern.quote(repo(owner, repo) + "/pulls"), Response.json(201, body.toString()));
    }

    /** {@code POST /repos/{o}/{r}/issues/{n}/comments}: 作成されたコメントとしてidを返す。 */
    public static void createIssueComment(FakeHttpServer github, String owner, String repo, int issueNumber,
            long commentId) {
        github.on("POST", Pattern.quote(repo(owner, repo) + "/issues/" + issueNumber + "/comments"),
                Response.json(201, "{\"id\":" + commentId + ",\"body\":\"x\"}"));
    }

    /** {@code GET /repos/{o}/{r}/issues/comments/{id}}。 */
    public static void issueComment(FakeHttpServer github, String owner, String repo, long commentId,
            String body) {
        github.json("GET", Pattern.quote(repo(owner, repo) + "/issues/comments/" + commentId),
                MAPPER.createObjectNode().put("id", commentId).put("body", body).toString());
    }

    /** {@code PUT /repos/{o}/{r}/pulls/{n}/merge}: マージ成功。 */
    public static void mergeOk(FakeHttpServer github, String owner, String repo, int number) {
        github.json("PUT", Pattern.quote(repo(owner, repo) + "/pulls/" + number + "/merge"),
                "{\"merged\":true,\"message\":\"Pull Request successfully merged\"}");
    }

    /** {@code DELETE /repos/{o}/{r}/git/refs/heads/{branch}}。 */
    public static void deleteBranchOk(FakeHttpServer github, String owner, String repo, String branch) {
        github.on("DELETE", Pattern.quote(repo(owner, repo) + "/git/refs/heads/" + branch), Response.empty(204));
    }
    /**
     * 記事1本を含むPull Requestを置く(詳細・変更ファイル一覧・article.mdとassets/の中身)。
     * {@code assets}は「assets/からの相対パス → バイト列」。
     */
    public static void articlePullRequest(FakeHttpServer github, String owner, String repo, int number,
            String headSha, String headRef, Boolean mergeable, boolean merged, String dirSlug, String articleMd,
            java.util.Map<String, byte[]> assets) {
        pullRequest(github, owner, repo, number, headSha, headRef, mergeable, merged);
        String dir = "articles/" + dirSlug + "/";
        java.util.List<String> files = new java.util.ArrayList<>();
        files.add(dir + "article.md");
        files.add("added");
        assets.keySet().forEach(name -> {
            files.add(dir + "assets/" + name);
            files.add("added");
        });
        pullRequestFiles(github, owner, repo, number, files.toArray(new String[0]));
        file(github, owner, repo, dir + "article.md", headSha,
                articleMd.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        assets.forEach((name, bytes) -> file(github, owner, repo, dir + "assets/" + name, headSha, bytes));
    }
}
