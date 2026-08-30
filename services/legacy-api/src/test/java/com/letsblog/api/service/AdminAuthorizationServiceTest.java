package com.letsblog.api.service;

import com.letsblog.api.domain.ProjectUser;
import com.letsblog.api.repository.ProjectUserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AdminAuthorizationServiceTest {

    @Mock
    private CurrentActorService currentActorService;

    @Mock
    private ProjectUserRepository projectUserRepository;

    private AdminAuthorizationService service;

    @BeforeEach
    void setUp() {
        service = new AdminAuthorizationService(currentActorService, projectUserRepository);
    }

    @Test
    void requireProjectMemberOrAdmin_adminなら無条件で許可() {
        lenient().when(currentActorService.isAdmin()).thenReturn(true);

        service.requireProjectMemberOrAdmin(1L);
    }

    @Test
    void requireProjectMemberOrAdmin_プロジェクトメンバーなら許可() {
        when(currentActorService.isAdmin()).thenReturn(false);
        when(currentActorService.getCurrentActorId()).thenReturn(10L);
        when(projectUserRepository.findByProjectIdAndUserId(1L, 10L))
                .thenReturn(Optional.of(new ProjectUser()));

        service.requireProjectMemberOrAdmin(1L);
    }

    @Test
    void requireProjectMemberOrAdmin_メンバーでなければ例外() {
        when(currentActorService.isAdmin()).thenReturn(false);
        when(currentActorService.getCurrentActorId()).thenReturn(10L);
        when(projectUserRepository.findByProjectIdAndUserId(1L, 10L)).thenReturn(Optional.empty());

        assertThrows(ForbiddenException.class, () -> service.requireProjectMemberOrAdmin(1L));
    }

    @Test
    void requireProjectMemberOrAdmin_X_Actor_Idなしなら例外() {
        when(currentActorService.isAdmin()).thenReturn(false);
        when(currentActorService.getCurrentActorId()).thenReturn(null);

        assertThrows(ForbiddenException.class, () -> service.requireProjectMemberOrAdmin(1L));
    }
}
