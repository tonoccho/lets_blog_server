package com.letsblog.api.service;

import com.letsblog.api.aop.AuditLog;
import com.letsblog.api.domain.AuditLogAction;
import com.letsblog.api.domain.Role;
import com.letsblog.api.domain.User;
import com.letsblog.api.dto.RoleAssignmentResult;
import com.letsblog.api.repository.RoleRepository;
import com.letsblog.api.repository.UserRepository;
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

    @AuditLog(action = AuditLogAction.USER_ROLE_ASSIGNED, resourceType = "USER")
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

    @AuditLog(action = AuditLogAction.USER_ROLE_REMOVED, resourceType = "USER")
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
