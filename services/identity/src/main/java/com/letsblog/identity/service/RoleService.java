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
     * MySQLのスキーマは{@code utf8mb4_unicode_ci}(大文字小文字を区別せず、末尾空白も無視する)
     * のため、{@code findByRoleName("role_admin")}や{@code "ROLE_ADMIN "}が
     * {@code ROLE_ADMIN}の行に一致する。コントローラ側で
     * {@code "ROLE_ADMIN".equals(roleName)}のような名前一致でガードすると、
     * 大小を変えただけの入力でガードだけをすり抜け、{@link #assignRoleToUser}側では
     * 同じ行に解決される、という迂回が成立する。解決結果の権限で判定すれば照合規則に依存しない。
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
