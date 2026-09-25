package com.letsblog.identity.service;

import com.letsblog.identity.domain.Permission;
import com.letsblog.identity.domain.Role;
import com.letsblog.identity.domain.User;
import com.letsblog.identity.dto.RoleAssignmentResult;
import com.letsblog.identity.repository.RoleRepository;
import com.letsblog.identity.repository.UserRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
@Slf4j
public class RoleService {

    private final RoleRepository roleRepository;
    private final UserRepository userRepository;

    public RoleService(RoleRepository roleRepository, UserRepository userRepository) {
        this.roleRepository = roleRepository;
        this.userRepository = userRepository;
    }

    @Transactional(readOnly = true)
    public List<Role> getAllRoles() {
        return roleRepository.findAll();
    }

    /**
     * このロールの付与・剥奪にadmin権限を要するか(issue #798)。
     *
     * <p>「ロールを配れる権限」を与えるロールを特権ロールとみなす。{@code ROLE_MANAGE}または
     * {@code USER_ROLE_MANAGE}を持つロールを自分に付与できると、そこから任意のロールを配れる
     * ようになり、{@code ROLE_MANAGE}しか持たない操作者が権限を際限なく広げられてしまう。
     * 既定シード({@code V8__add_rbac_tables.sql})で該当するのは{@code ROLE_ADMIN}のみ。
     *
     * <p><b>ロール名の文字列比較ではなく、DBから解決した実体で判定している理由</b>:
     * MySQLの照合順序は大文字小文字を区別しない({@code lets_blog}はサーバー既定の
     * {@code utf8mb4_0900_ai_ci}、テスト用{@code lbs_identity_test}は
     * {@code infra/mysql/init/02-create-test-schemas.sh}が指定する{@code utf8mb4_unicode_ci}。
     * どちらもアクセントと大小の差を無視する。{@code utf8mb4_unicode_ci}は名前に{@code _ai}を
     * 含まないが、{@code _ci}があり{@code _as}が無いためアクセント非依存になる)。そのため
     * {@code findByRoleName("role_admin")}は{@code ROLE_ADMIN}の行に一致する。
     * コントローラ側で{@code "ROLE_ADMIN".equals(roleName)}のような名前一致でガードすると、
     * 大小を変えただけの入力でガードだけをすり抜け、{@link #assignRoleToUser}側では
     * 同じ行に解決される、という迂回が成立する。
     *
     * <p>ここでの安全性は「正規化を網羅したから」ではなく<b>構造から</b>来ている。
     * 判定と割り当てが同じ{@code findByRoleName(roleName)}を同じ入力文字列で呼ぶため、
     * どんな入力に対しても両者の解決結果は必ず一致する。ある入力が{@code ROLE_ADMIN}の行に
     * 解決するなら判定も必ずtrueになり、解決しないなら割り当ても{@code RoleNotFoundException}に
     * なる。全角・Unicode正規化・末尾空白といった照合順序の差異は、この構造の下では
     * 分岐点になりえない。したがって照合順序が将来変わっても、この判定は追随不要である。
     *
     * <p><b>特権の定義を変える場合の注意</b>: 新たに{@code requirePermission(X)}を追加するときは、
     * Xを特権の定義に含めるべきか判断すること。現状{@code requirePermission}が実際に
     * 要求しているのは{@code ROLE_MANAGE}だけなので、それ以外のPermissionを持つロールを
     * 特権とみなさなくても実害が無いが、enforceされるPermissionが増えると前提が変わる。
     *
     * <p>ロールが存在しない場合はfalseを返す。その場合は後続の
     * {@link #assignRoleToUser}/{@link #removeRoleFromUser}が
     * {@code RoleNotFoundException}を投げる(存在しないロール名から、それが特権ロールか
     * どうかを推測できないようにするため、ここでは判定を変えない)。
     */
    @Transactional(readOnly = true)
    public boolean isPrivilegedRole(String roleName) {
        return roleRepository.findByRoleName(roleName)
                .map(role -> role.hasPermission(Permission.ROLE_MANAGE)
                        || role.hasPermission(Permission.USER_ROLE_MANAGE))
                .orElse(false);
    }

    @Transactional
    public RoleAssignmentResult assignRoleToUser(Long userId, String roleName) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new UserNotFoundException("ユーザーが見つかりません"));
        Role role = roleRepository.findByRoleName(roleName)
                .orElseThrow(() -> new RoleNotFoundException("ロール '" + roleName + "' が見つかりません"));

        user.getRoles().add(role);
        userRepository.save(user);

        log.info("Role {} assigned to user {}", roleName, userId);
        return new RoleAssignmentResult(userId, roleName);
    }

    @Transactional
    public RoleAssignmentResult removeRoleFromUser(Long userId, String roleName) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new UserNotFoundException("ユーザーが見つかりません"));
        Role role = roleRepository.findByRoleName(roleName)
                .orElseThrow(() -> new RoleNotFoundException("ロール '" + roleName + "' が見つかりません"));

        user.getRoles().remove(role);
        userRepository.save(user);

        log.info("Role {} removed from user {}", roleName, userId);
        return new RoleAssignmentResult(userId, roleName);
    }
}
