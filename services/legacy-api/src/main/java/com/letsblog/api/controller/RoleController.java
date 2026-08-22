package com.letsblog.api.controller;

import com.letsblog.api.domain.Permission;
import com.letsblog.api.dto.RoleResponse;
import com.letsblog.api.service.PermissionAuthorizationService;
import com.letsblog.api.service.RoleService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/roles")
public class RoleController {

    private final RoleService roleService;
    private final PermissionAuthorizationService permissionAuthorizationService;

    public RoleController(RoleService roleService, PermissionAuthorizationService permissionAuthorizationService) {
        this.roleService = roleService;
        this.permissionAuthorizationService = permissionAuthorizationService;
    }

    @GetMapping
    public List<RoleResponse> list() {
        permissionAuthorizationService.requirePermission(Permission.ROLE_MANAGE);
        return roleService.getAllRoles().stream().map(RoleResponse::from).toList();
    }
}
