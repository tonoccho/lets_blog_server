package com.letsblog.api.controller;

import com.letsblog.api.dto.AddProjectUserRequest;
import com.letsblog.api.dto.BulkOperationLogResponse;
import com.letsblog.api.dto.BulkOperationRequest;
import com.letsblog.api.dto.ProjectCreateRequest;
import com.letsblog.api.dto.ProjectEnvironmentBindRequest;
import com.letsblog.api.dto.ProjectResponse;
import com.letsblog.api.dto.ProjectUpdateRequest;
import com.letsblog.api.dto.ProjectUserResponse;
import com.letsblog.api.dto.ReplayBulkOperationRequest;
import com.letsblog.api.dto.SyncEnvironmentRequest;
import com.letsblog.api.dto.UpdateProjectUserRequest;
import com.letsblog.api.domain.BulkOperationLog;
import com.letsblog.api.domain.BulkOperationType;
import com.letsblog.api.service.AdminAuthorizationService;
import com.letsblog.api.service.BulkManagementService;
import com.letsblog.api.service.CurrentActorService;
import com.letsblog.api.service.ProjectEnvironmentSyncService;
import com.letsblog.api.service.ProjectService;
import com.letsblog.api.service.ProjectUserSyncService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.List;

@RestController
@RequestMapping("/api/projects")
public class ProjectController {

    private final ProjectService projectService;
    private final ProjectUserSyncService projectUserSyncService;
    private final ProjectEnvironmentSyncService projectEnvironmentSyncService;
    private final BulkManagementService bulkManagementService;
    private final AdminAuthorizationService adminAuthorizationService;
    private final CurrentActorService currentActorService;

    public ProjectController(
            ProjectService projectService,
            ProjectUserSyncService projectUserSyncService,
            ProjectEnvironmentSyncService projectEnvironmentSyncService,
            BulkManagementService bulkManagementService,
            AdminAuthorizationService adminAuthorizationService,
            CurrentActorService currentActorService) {
        this.projectService = projectService;
        this.projectUserSyncService = projectUserSyncService;
        this.projectEnvironmentSyncService = projectEnvironmentSyncService;
        this.bulkManagementService = bulkManagementService;
        this.adminAuthorizationService = adminAuthorizationService;
        this.currentActorService = currentActorService;
    }

    @PostMapping
    public ResponseEntity<ProjectResponse> create(@Valid @RequestBody ProjectCreateRequest request) {
        adminAuthorizationService.requireAdmin();
        ProjectResponse response = projectService.createProject(request.name(), request.slug());
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    @GetMapping
    public List<ProjectResponse> list() {
        return projectService.listProjects();
    }

    @GetMapping("/{id}")
    public ProjectResponse get(@PathVariable Long id) {
        return projectService.getProject(id);
    }

    @PutMapping("/{id}")
    public ProjectResponse update(@PathVariable Long id, @Valid @RequestBody ProjectUpdateRequest request) {
        adminAuthorizationService.requireAdmin();
        return projectService.updateProject(id, request.name());
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable Long id) {
        adminAuthorizationService.requireAdmin();
        projectService.deleteProject(id);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/{id}/environments")
    public ProjectResponse bindEnvironment(
            @PathVariable Long id, @Valid @RequestBody ProjectEnvironmentBindRequest request) {
        adminAuthorizationService.requireAdmin();
        return projectService.bindEnvironment(id, request.environment(), request.siteId());
    }

    @DeleteMapping("/{id}/environments/{environment}")
    public ProjectResponse unbindEnvironment(@PathVariable Long id, @PathVariable String environment) {
        adminAuthorizationService.requireAdmin();
        return projectService.unbindEnvironment(id, environment);
    }

    @PostMapping("/{id}/environments/sync")
    public ResponseEntity<Void> syncEnvironment(
            @PathVariable Long id, @Valid @RequestBody SyncEnvironmentRequest request) {
        adminAuthorizationService.requireAdmin();
        projectEnvironmentSyncService.sync(id, request.from(), request.to(), request.targets());
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/{id}/bulk-management")
    public List<BulkOperationLogResponse> runBulkOperation(
            @PathVariable Long id, @Valid @RequestBody BulkOperationRequest request) {
        adminAuthorizationService.requireAdmin();
        Long actorId = currentActorService.getCurrentActorId();
        List<BulkOperationLog> logs =
                bulkManagementService.execute(id, request.operationType(), request.value(), actorId);
        return logs.stream().map(BulkOperationLogResponse::from).toList();
    }

    @PostMapping(value = "/{id}/bulk-management/upload", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public List<BulkOperationLogResponse> runBulkOperationUpload(
            @PathVariable Long id,
            @RequestParam BulkOperationType operationType,
            @RequestPart("file") MultipartFile file) throws IOException {
        adminAuthorizationService.requireAdmin();
        Long actorId = currentActorService.getCurrentActorId();
        List<BulkOperationLog> logs = bulkManagementService.executeFromUpload(id, operationType, file, actorId);
        return logs.stream().map(BulkOperationLogResponse::from).toList();
    }

    @PostMapping("/{id}/bulk-management/replay")
    public List<BulkOperationLogResponse> replayBulkOperations(
            @PathVariable Long id, @Valid @RequestBody ReplayBulkOperationRequest request) {
        adminAuthorizationService.requireAdmin();
        Long actorId = currentActorService.getCurrentActorId();
        List<BulkOperationLog> logs = bulkManagementService.replay(id, request.environment(), actorId);
        return logs.stream().map(BulkOperationLogResponse::from).toList();
    }

    @GetMapping("/{id}/bulk-management/logs")
    public List<BulkOperationLogResponse> listBulkOperationLogs(@PathVariable Long id) {
        adminAuthorizationService.requireAdmin();
        return bulkManagementService.listLogs(id).stream().map(BulkOperationLogResponse::from).toList();
    }

    @GetMapping("/{id}/users")
    public List<ProjectUserResponse> listUsers(@PathVariable Long id) {
        adminAuthorizationService.requireAdmin();
        return projectUserSyncService.getProjectUsers(id);
    }

    @PostMapping("/{id}/users")
    public ResponseEntity<Void> addUser(@PathVariable Long id, @Valid @RequestBody AddProjectUserRequest request) {
        adminAuthorizationService.requireAdmin();
        projectUserSyncService.addUserToProject(id, request.userId(), request.wpRole());
        return ResponseEntity.noContent().build();
    }

    @PutMapping("/{id}/users/{userId}")
    public ResponseEntity<Void> updateUserRole(
            @PathVariable Long id, @PathVariable Long userId, @Valid @RequestBody UpdateProjectUserRequest request) {
        adminAuthorizationService.requireAdmin();
        projectUserSyncService.updateUserProjectRole(id, userId, request.wpRole());
        return ResponseEntity.noContent().build();
    }

    @DeleteMapping("/{id}/users/{userId}")
    public ResponseEntity<Void> removeUser(@PathVariable Long id, @PathVariable Long userId) {
        adminAuthorizationService.requireAdmin();
        projectUserSyncService.removeUserFromProject(id, userId);
        return ResponseEntity.noContent().build();
    }
}
