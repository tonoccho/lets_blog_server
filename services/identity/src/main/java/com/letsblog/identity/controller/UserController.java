package com.letsblog.identity.controller;

import com.letsblog.identity.domain.Permission;
import com.letsblog.identity.dto.MigrateToKeycloakRequest;
import com.letsblog.identity.dto.MigrationSummaryResponse;
import com.letsblog.identity.dto.ReconciliationSummaryResponse;
import com.letsblog.identity.dto.UpdateGithubTokenRequest;
import com.letsblog.identity.dto.UpdateUserPreferencesRequest;
import com.letsblog.identity.dto.UserCreateRequest;
import com.letsblog.identity.dto.UserProfileResponse;
import com.letsblog.identity.dto.UserProfileUpdateRequest;
import com.letsblog.identity.dto.UserResponse;
import com.letsblog.identity.dto.UserUpdateRequest;
import com.letsblog.identity.service.AdminAuthorizationService;
import com.letsblog.identity.service.PermissionAuthorizationService;
import com.letsblog.identity.service.RoleService;
import com.letsblog.identity.service.UserService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@Tag(name = "Users", description = "ユーザー管理API")
@RestController
@RequestMapping("/api/users")
public class UserController {

    private final UserService userService;
    private final RoleService roleService;
    private final PermissionAuthorizationService permissionAuthorizationService;
    private final AdminAuthorizationService adminAuthorizationService;

    public UserController(
            UserService userService,
            RoleService roleService,
            PermissionAuthorizationService permissionAuthorizationService,
            AdminAuthorizationService adminAuthorizationService) {
        this.userService = userService;
        this.roleService = roleService;
        this.permissionAuthorizationService = permissionAuthorizationService;
        this.adminAuthorizationService = adminAuthorizationService;
    }

    @Operation(summary = "全ユーザー一覧を取得", description = "システムに登録されているすべてのユーザーを取得します")
    @ApiResponse(responseCode = "200", description = "ユーザー一覧を返す")
    @ApiResponse(responseCode = "401", description = "認証ヘッダが無効")
    @ApiResponse(responseCode = "403", description = "admin権限がありません")
    @GetMapping
    public List<UserResponse> list() {
        adminAuthorizationService.requireAdmin();
        return userService.list();
    }

    /**
     * {@code UserCreateRequest}が{@code role}を受け取るため、認可が無いと任意のクライアントが
     * {@code role=admin}のアカウントを作れてしまう(権限昇格)。#653で{@code list()}に
     * 認可を入れた際に書き込み系が取り残されていた(issue #796)。
     */
    @Operation(summary = "ユーザーを新規作成", description = "新しいユーザーアカウントを作成します")
    @ApiResponse(responseCode = "201", description = "ユーザーが作成されました")
    @ApiResponse(responseCode = "400", description = "リクエストボディが不正")
    @ApiResponse(responseCode = "401", description = "認証ヘッダが無効")
    @ApiResponse(responseCode = "403", description = "admin権限がありません")
    @PostMapping
    public ResponseEntity<UserResponse> create(@Valid @RequestBody UserCreateRequest request) {
        adminAuthorizationService.requireAdmin();
        return ResponseEntity.status(HttpStatus.CREATED).body(userService.create(request));
    }

    /**
     * {@code requireSelfOrAdmin}ではなく{@code requireAdmin}である理由(issue #796):
     * {@code UserUpdateRequest}が扱うのは{@code role}と{@code password}、すなわち
     * <b>管理者が管理する項目</b>であって本人が自由に変えてよい項目ではない。本人に許すと
     * 自分の{@code role}をadminへ書き換えられ、権限昇格そのものになる。
     * 本人が変更してよいプロフィール項目は{@code PUT /api/users/{id}}
     * ({@link #updateProfile}、{@code requireSelfOrAdmin})、個人設定は
     * {@code PATCH /api/identity/me/preferences}が担当する。
     *
     * <p>あわせて自分自身の降格も禁止する(issue #798)。adminが自分を{@code role="user"}に
     * 書き換えると、admin限定のエンドポイントがすべて閉じて復旧できなくなる。自己削除・自己無効化と
     * 同じロックアウト経路なので同様に塞ぐ。詳細は
     * {@link AdminAuthorizationService#requireNotSelfDemotion}を参照。
     */
    @Operation(summary = "ユーザー情報を更新", description = "指定されたユーザーのrole/passwordを更新します(admin限定。自分自身をadmin以外へ降格することは不可)")
    @ApiResponse(responseCode = "200", description = "ユーザーが更新されました")
    @ApiResponse(responseCode = "401", description = "認証ヘッダが無効")
    @ApiResponse(responseCode = "403", description = "admin権限が無い、または自分自身をadmin以外へ降格しようとした")
    @ApiResponse(responseCode = "404", description = "ユーザーが見つかりません")
    @PatchMapping("/{id}")
    public UserResponse update(
            @Parameter(description = "ユーザーID") @PathVariable Long id,
            @RequestBody UserUpdateRequest request) {
        adminAuthorizationService.requireAdmin();
        adminAuthorizationService.requireNotSelfDemotion(id, request.role());
        return userService.update(id, request);
    }

    /**
     * 無効化({@link #deactivate})がadmin限定なのに、より破壊的な削除に認可が無い非対称を解消する
     * (issue #796)。あわせて自己削除も禁止する。Web側の{@code deleteUserAction}にも同じガードが
     * あるが、gatewayは認可判定を行わない(ADR-0008)ためクライアント側の防御だけでは不十分。
     */
    @Operation(summary = "ユーザーを削除", description = "指定されたユーザーを削除します(admin限定。自分自身は削除不可)")
    @ApiResponse(responseCode = "204", description = "ユーザーが削除されました")
    @ApiResponse(responseCode = "401", description = "認証ヘッダが無効")
    @ApiResponse(responseCode = "403", description = "admin権限が無い、自分自身を削除しようとした、または最後の管理者を削除しようとした")
    @ApiResponse(responseCode = "404", description = "ユーザーが見つかりません")
    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@Parameter(description = "ユーザーID") @PathVariable Long id) {
        adminAuthorizationService.requireAdminAndNotSelf(id, "自分自身のアカウントは削除できません");
        userService.delete(id);
        return ResponseEntity.noContent().build();
    }

    /**
     * 自己無効化を禁止する理由(issue #798)。
     *
     * <p>#796は自己「削除」を禁止したが「無効化」は放置していた。adminが自分自身を無効化すると
     * ログインできなくなり、他にadminがいなければ誰も復旧できない
     * ({@link #reactivate}も{@code requireAdmin()}を要求するため)。取り消せない度合いは
     * 削除より低いものの、締め出しという結果は同じなので、削除と同じガードを掛ける。
     */
    @Operation(summary = "ユーザーを無効化", description = "指定されたユーザーを無効化します(admin限定。自分自身は無効化不可。Keycloak登録済みの場合はKeycloak側も無効化)")
    @ApiResponse(responseCode = "200", description = "ユーザーが無効化されました")
    @ApiResponse(responseCode = "401", description = "認証ヘッダが無効")
    @ApiResponse(responseCode = "403", description = "admin権限が無い、自分自身を無効化しようとした、または最後の管理者を無効化しようとした")
    @ApiResponse(responseCode = "404", description = "ユーザーが見つかりません")
    @ApiResponse(responseCode = "502", description = "Keycloak Admin APIの呼び出しに失敗しました")
    @PostMapping("/{id}/deactivate")
    public UserResponse deactivate(@Parameter(description = "ユーザーID") @PathVariable Long id) {
        adminAuthorizationService.requireAdminAndNotSelf(id, "自分自身のアカウントは無効化できません");
        return userService.deactivate(id);
    }

    @Operation(summary = "ユーザーを再有効化", description = "無効化されたユーザーを再度有効化します")
    @ApiResponse(responseCode = "200", description = "ユーザーが有効化されました")
    @ApiResponse(responseCode = "401", description = "認証ヘッダが無効")
    @ApiResponse(responseCode = "403", description = "admin権限がありません")
    @ApiResponse(responseCode = "404", description = "ユーザーが見つかりません")
    @ApiResponse(responseCode = "502", description = "Keycloak Admin APIの呼び出しに失敗しました")
    @PostMapping("/{id}/reactivate")
    public UserResponse reactivate(@Parameter(description = "ユーザーID") @PathVariable Long id) {
        adminAuthorizationService.requireAdmin();
        return userService.reactivate(id);
    }

    @Operation(
            summary = "既存ユーザーをKeycloakへ一括移行",
            description = "keycloak_sub未設定のユーザーをKeycloakへ登録し、パスワード再設定を要求します。"
                    + "userIdsを指定しない場合は対象全ユーザーが移行されます。")
    @ApiResponse(responseCode = "200", description = "移行結果(成功/失敗の内訳)を返す")
    @ApiResponse(responseCode = "401", description = "認証ヘッダが無効")
    @ApiResponse(responseCode = "403", description = "admin権限がありません")
    @PostMapping("/migrate-to-keycloak")
    public MigrationSummaryResponse migrateToKeycloak(
            @RequestBody(required = false) MigrateToKeycloakRequest request) {
        adminAuthorizationService.requireAdmin();
        List<Long> userIds = request != null ? request.userIds() : null;
        return userService.migrateToKeycloak(userIds);
    }

    @Operation(
            summary = "Keycloakとの整合を確認し孤児ユーザーを無効化",
            description = "keycloak_sub設定済みのユーザーについてKeycloak側の存在を確認し、"
                    + "存在しなくなっていたユーザーを論理無効化します。")
    @ApiResponse(responseCode = "200", description = "無効化されたユーザーIDの一覧を返す")
    @ApiResponse(responseCode = "401", description = "認証ヘッダが無効")
    @ApiResponse(responseCode = "403", description = "admin権限がありません")
    @ApiResponse(responseCode = "502", description = "Keycloak Admin APIの呼び出しに失敗しました")
    @PostMapping("/reconcile-keycloak")
    public ReconciliationSummaryResponse reconcileKeycloak() {
        adminAuthorizationService.requireAdmin();
        return userService.reconcileWithKeycloak();
    }

    @Operation(summary = "ユーザープロフィール取得", description = "指定されたユーザーのプロフィール情報を取得します")
    @ApiResponse(responseCode = "200", description = "プロフィール情報を返す")
    @ApiResponse(responseCode = "401", description = "認証ヘッダが無効")
    @ApiResponse(responseCode = "404", description = "ユーザーが見つかりません")
    @GetMapping("/{id}")
    public UserProfileResponse getProfile(@Parameter(description = "ユーザーID") @PathVariable Long id) {
        adminAuthorizationService.requireSelfOrAdmin(id);
        return userService.findUserWithProfile(id);
    }

    @Operation(summary = "ユーザープロフィール更新", description = "指定されたユーザーのプロフィール情報を更新します")
    @ApiResponse(responseCode = "200", description = "プロフィールが更新されました")
    @ApiResponse(responseCode = "401", description = "認証ヘッダが無効")
    @ApiResponse(responseCode = "404", description = "ユーザーが見つかりません")
    @PutMapping("/{id}")
    public UserProfileResponse updateProfile(
            @Parameter(description = "ユーザーID") @PathVariable Long id,
            @RequestBody UserProfileUpdateRequest request) {
        adminAuthorizationService.requireSelfOrAdmin(id);
        return userService.updateUserProfile(id, request);
    }

    @Operation(summary = "ユーザー設定を更新", description = "指定されたユーザーの各種設定を更新します")
    @ApiResponse(responseCode = "200", description = "設定が更新されました")
    @ApiResponse(responseCode = "401", description = "認証ヘッダが無効")
    @ApiResponse(responseCode = "404", description = "ユーザーが見つかりません")
    @PatchMapping("/{id}/preferences")
    public UserProfileResponse updatePreferences(
            @Parameter(description = "ユーザーID") @PathVariable Long id,
            @Valid @RequestBody UpdateUserPreferencesRequest request) {
        adminAuthorizationService.requireSelfOrAdmin(id);
        return userService.updateUserPreferences(id, request);
    }

    @Operation(summary = "GitHubトークンを更新", description = "指定されたユーザーのGitHubトークンを更新します")
    @ApiResponse(responseCode = "200", description = "トークンが更新されました")
    @ApiResponse(responseCode = "401", description = "認証ヘッダが無効")
    @ApiResponse(responseCode = "404", description = "ユーザーが見つかりません")
    @PutMapping("/{id}/github-token")
    public UserProfileResponse updateGithubToken(
            @Parameter(description = "ユーザーID") @PathVariable Long id,
            @Valid @RequestBody UpdateGithubTokenRequest request) {
        adminAuthorizationService.requireSelfOrAdmin(id);
        return userService.updateGithubToken(id, request);
    }

    /**
     * RBACのロールを割り当てる。割り当て後に{@link UserService#reconcileKeycloakAdminRole(Long)}で
     * Keycloakのrealmロールを{@code users.role}へ冪等に整合させる(issue #955)。
     *
     * <p>RBACの{@code ROLE_ADMIN}はadmin判定の3つ目の軸ではないため、
     * 「{@code ROLE_ADMIN}が付いたからrealmロール{@code admin}を付ける」という導出はしない。
     * realmロールが従うのはあくまで{@code users.role}である
     * (正/従の決定はdocs/AUTHORIZATION_MATRIX.mdを参照)。
     *
     * <p>整合はベストエフォートであり、失敗してもこのエンドポイントは成功を返す。
     * ロール割り当て自体は既に成功しており、この経路は{@code users.role}を変えないため、
     * 失敗しても新たなずれは生まれない(理由は{@code reconcileKeycloakAdminRole}のjavadoc)。
     */
    @Operation(summary = "ロールを割り当て", description = "指定されたユーザーにロールを割り当てます(特権ロールはadmin限定)")
    @ApiResponse(responseCode = "200", description = "ロールが割り当てられました")
    @ApiResponse(responseCode = "401", description = "認証ヘッダが無効")
    @ApiResponse(responseCode = "403", description = "ロール管理権限が無い、または特権ロールをadmin以外が操作しようとした")
    @ApiResponse(responseCode = "404", description = "ユーザーまたはロールが見つかりません")
    @PostMapping("/{userId}/roles/{roleName}")
    public ResponseEntity<Map<String, String>> assignRole(
            @Parameter(description = "ユーザーID") @PathVariable Long userId,
            @Parameter(description = "ロール名") @PathVariable String roleName) {
        authorizeRoleChange(roleName);
        roleService.assignRoleToUser(userId, roleName);
        userService.reconcileKeycloakAdminRole(userId);
        return ResponseEntity.ok(Map.of("message", "ロールを割り当てました。"));
    }

    @Operation(summary = "ロールを削除", description = "指定されたユーザーからロールを削除します(特権ロールはadmin限定)")
    @ApiResponse(responseCode = "200", description = "ロールが削除されました")
    @ApiResponse(responseCode = "401", description = "認証ヘッダが無効")
    @ApiResponse(responseCode = "403", description = "ロール管理権限が無い、または特権ロールをadmin以外が操作しようとした")
    @ApiResponse(responseCode = "404", description = "ユーザーまたはロールが見つかりません")
    @DeleteMapping("/{userId}/roles/{roleName}")
    public ResponseEntity<Map<String, String>> removeRole(
            @Parameter(description = "ユーザーID") @PathVariable Long userId,
            @Parameter(description = "ロール名") @PathVariable String roleName) {
        authorizeRoleChange(roleName);
        roleService.removeRoleFromUser(userId, roleName);
        userService.reconcileKeycloakAdminRole(userId);
        return ResponseEntity.ok(Map.of("message", "ロールを解除しました。"));
    }

    /**
     * ロールの付与・剥奪の認可(issue #798)。
     *
     * <p>従来は付与・剥奪ともに{@code requirePermission(ROLE_MANAGE)}だけだった。
     * {@code ROLE_MANAGE}は既定シードでは{@code ROLE_ADMIN}しか持たないため既定データでは
     * 実害が無かったが、運用で非adminロールに{@code ROLE_MANAGE}を付与すると、そのユーザーは
     * {@code POST /api/users/{自分のid}/roles/ROLE_ADMIN}で自分に特権ロールを付けられた。
     * #796が{@code PATCH /api/users/{id}}について塞いだのと同型の経路が、権限設定次第で復活する。
     *
     * <p>そこで「ロールを配れる権限を与えるロール」(特権ロール。
     * {@link RoleService#isPrivilegedRole}参照)の付与・剥奪だけをadmin限定にする。
     * 選択肢として「自分自身へのロール割り当てだけを禁止する」案もあったが、それでは
     * {@code ROLE_MANAGE}保有者どうしが互いに特権ロールを付け合ったり、自分が管理する別アカウントに
     * 付けたりする経路が残るため採らなかった。
     *
     * <p>剥奪も同じ扱いにしているのは対称性のため。付与がadmin限定なのに剥奪が
     * {@code ROLE_MANAGE}のままだと、{@code ROLE_MANAGE}保有者がadminたちから特権ロールを
     * 剥がして回れてしまう(RBAC軸での妨害)。
     *
     * <p><b>注意</b>: ここでの「admin」は{@code users.role}カラムが{@code "admin"}であることを指し
     * ({@code CurrentActorService#isAdmin})、RBACの{@code ROLE_ADMIN}とは別軸である。
     * したがって{@code ROLE_ADMIN}を自分に付けても{@code requireAdmin()}のエンドポイントは
     * 開かない。この二重構造そのものの整理は本Issueのスコープ外。
     */
    private void authorizeRoleChange(String roleName) {
        if (roleService.isPrivilegedRole(roleName)) {
            adminAuthorizationService.requireAdmin();
            return;
        }
        permissionAuthorizationService.requirePermission(Permission.ROLE_MANAGE);
    }
}
