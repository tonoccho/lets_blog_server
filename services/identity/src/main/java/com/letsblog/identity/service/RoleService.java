package com.letsblog.identity.service;

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
