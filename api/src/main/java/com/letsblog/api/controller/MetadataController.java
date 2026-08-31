package com.letsblog.api.controller;

import com.letsblog.api.domain.PostStatus;
import com.letsblog.api.dto.PostStatusOptionResponse;
import com.letsblog.api.dto.RoleOptionResponse;
import com.letsblog.api.service.RoleService;
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
 */
@Tag(name = "Metadata", description = "クライアントUI統一のためのメタデータAPI")
@RestController
@RequestMapping("/api/metadata")
public class MetadataController {

    private final RoleService roleService;

    public MetadataController(RoleService roleService) {
        this.roleService = roleService;
    }

    @Operation(summary = "投稿ステータスの選択肢を取得", description = "WordPress投稿ステータスの正準リストを返します")
    @ApiResponse(responseCode = "200", description = "投稿ステータス一覧を返す")
    @GetMapping("/post-statuses")
    public List<PostStatusOptionResponse> postStatuses() {
        return Arrays.stream(PostStatus.values()).map(PostStatusOptionResponse::from).toList();
    }

    @Operation(summary = "ロールの表示名一覧を取得", description = "権限一覧を含まない、ロール名と表示名のみの一覧を返します")
    @ApiResponse(responseCode = "200", description = "ロール一覧を返す")
    @GetMapping("/roles")
    public List<RoleOptionResponse> roles() {
        return roleService.getAllRoles().stream().map(RoleOptionResponse::from).toList();
    }
}
