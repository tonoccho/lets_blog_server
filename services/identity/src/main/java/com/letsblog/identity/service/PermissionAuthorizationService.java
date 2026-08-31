package com.letsblog.identity.service;

import com.letsblog.identity.domain.Permission;
import com.letsblog.identity.domain.User;
import com.letsblog.identity.repository.UserRepository;
import org.springframework.stereotype.Service;

/**
 * 細粒度権限(Permission)のチェックを行う。
 * 「誰がリクエストしているか」はCurrentActorServiceが解決するKeycloak JWTのsubクレーム起点の
 * actorIdのみを信頼し、実際に持つ権限はDB上のUser.rolesをサーバー側で都度検索して判定する。
 *
 * <p><b>{@code users.role = "admin"} は全Permissionを含意する</b>(issue #815)。
 * identity-serviceにはadminを決める軸が2つある。
 *
 * <ul>
 *   <li><b>粗い軸</b>: {@code users.role} カラム。{@code "admin"} なら特権利用者。
 *       {@link AdminAuthorizationService#requireAdmin()} 系が見るのはこちら</li>
 *   <li><b>細かい軸</b>: RBAC({@code roles} / {@code role_permissions} / {@code user_roles})。
 *       非adminに個別の権限を配るための仕組み</li>
 * </ul>
 *
 * <p>#798以前は両者が完全に独立しており、{@code users.role = "admin"} でも
 * {@code ROLE_ADMIN} を持たなければ {@code requirePermission} を通れなかった。その結果
 * #798 が特権ロールの付与のみをadmin限定にした際、<b>「特権ロールは付与できるのに、
 * 特権でないロールは付与できない」</b>という直感に反する非対称が生まれた
 * ({@code UserController#authorizeRoleChange})。
 *
 * <p>ここでadminを全権と定めることでその非対称が解消し、2軸の関係も
 * 「adminは全部できる。RBACはadmin以外へ個別に配るためのもの」と一意になる。
 * 判断の経緯は {@code docs/AUTHORIZATION_MATRIX.md} の「admin判定の2つの軸」節を参照。
 */
@Service
public class PermissionAuthorizationService {

    private final CurrentActorService currentActorService;
    private final UserRepository userRepository;

    public PermissionAuthorizationService(CurrentActorService currentActorService, UserRepository userRepository) {
        this.currentActorService = currentActorService;
        this.userRepository = userRepository;
    }

    public void requirePermission(Permission permission) {
        Long actorId = currentActorService.getCurrentActorId();
        if (actorId == null) {
            throw new ForbiddenException("この操作には認証が必要です");
        }

        // users.role = "admin" は全権(issue #815)。RBACロールの保有状況を問わない。
        if (currentActorService.isAdmin()) {
            return;
        }

        User user = userRepository.findById(actorId)
                .orElseThrow(() -> new ForbiddenException("この操作には認証が必要です"));

        if (!user.hasPermission(permission)) {
            throw new ForbiddenException("この操作には権限 '" + permission + "' が必要です");
        }
    }
}
