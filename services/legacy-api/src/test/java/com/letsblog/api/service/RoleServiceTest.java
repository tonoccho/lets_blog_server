package com.letsblog.api.service;

import com.letsblog.api.domain.Role;
import com.letsblog.api.repository.RoleRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class RoleServiceTest {

    @Mock
    private RoleRepository roleRepository;

    private RoleService service;

    @BeforeEach
    void setUp() {
        service = new RoleService(roleRepository);
    }

    @Test
    void getAllRoles_全ロールを返す() {
        List<Role> roles = List.of(new Role("ROLE_ADMIN", "管理者"), new Role("ROLE_VIEWER", "閲覧者"));
        when(roleRepository.findAll()).thenReturn(roles);

        assertEquals(2, service.getAllRoles().size());
    }
}
