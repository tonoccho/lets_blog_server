package com.letsblog.content.controller;

import com.letsblog.content.client.LegacyApiBridgeClient;
import com.letsblog.content.domain.PostStatus;
import com.letsblog.content.dto.PostStatusOptionResponse;
import com.letsblog.content.dto.RoleOptionResponse;
import com.letsblog.content.service.CurrentActorService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Arrays;
import java.util.List;

/**
 * クライアント(Web/VS Code拡張)がUIの選択肢・表示名をサーバー側の正準値と一致させるための
 * メタデータAPI(issue #472)。特定の{@code Permission}は要求せず、認証済みactorであれば参照できる。
 * legacy-apiのMetadataControllerと同じ実装(issue #576でcontent-serviceへ移管)。rolesテーブルは
 * legacy-apiに残るドメインのため、{@link LegacyApiBridgeClient}経由の内部ブリッジで取得する。
 */
@Tag(name = "Metadata", description = "クライアントUI統一のためのメタデータAPI")
@RestController
@RequestMapping("/api/metadata")
public class MetadataController {

    private final LegacyApiBridgeClient legacyApiBridgeClient;
    private final CurrentActorService currentActorService;

    public MetadataController(LegacyApiBridgeClient legacyApiBridgeClient, CurrentActorService currentActorService) {
        this.legacyApiBridgeClient = legacyApiBridgeClient;
        this.currentActorService = currentActorService;
    }

    @Operation(summary = "投稿ステータスの選択肢を取得", description = "WordPress投稿ステータスの正準リストを返します")
    @ApiResponse(responseCode = "200", description = "投稿ステータス一覧を返す")
    /**
     * 認可不要: WordPress投稿ステータスのenumを列挙して返すだけで、保存済みデータには一切触れない
     * (issue #830)。クライアントUIの選択肢をサーバー側の正準値と揃えるためのもの。
     */
    @GetMapping("/post-statuses")
    public List<PostStatusOptionResponse> postStatuses() {
        return Arrays.stream(PostStatus.values()).map(PostStatusOptionResponse::from).toList();
    }

    @Operation(summary = "ロールの表示名一覧を取得", description = "権限一覧を含まない、ロール名と表示名のみの一覧を返します")
    @ApiResponse(responseCode = "200", description = "ロール一覧を返す")
    /**
     * 認可不要: ロール名と表示名だけを返し、各ロールが持つ権限一覧は含まない(issue #830)。
     * クライアントUIの表示名を揃えるためのもので、権限構成が漏れるわけではない。
     */
    @GetMapping("/roles")
    public List<RoleOptionResponse> roles() {
        return legacyApiBridgeClient.listRoles(currentActorService.getAuthorizationHeader());
    }
}
