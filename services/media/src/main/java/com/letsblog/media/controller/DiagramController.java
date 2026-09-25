package com.letsblog.media.controller;

import com.letsblog.media.domain.Diagram;
import com.letsblog.media.dto.CreateDiagramRequest;
import com.letsblog.media.dto.DiagramDetailResponse;
import com.letsblog.media.dto.DiagramSummaryResponse;
import com.letsblog.media.dto.UpdateDiagramRequest;
import com.letsblog.media.repository.DiagramRepository;
import com.letsblog.media.service.AdminAuthorizationService;
import com.letsblog.media.service.DiagramNotFoundException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * draw.ioで作成したダイアグラムの一覧・詳細・バイナリ取得・作成・更新・削除(Web管理画面のギャラリー表示用)。
 */
@RestController
public class DiagramController {

    private final DiagramRepository diagramRepository;
    private final AdminAuthorizationService adminAuthorizationService;

    public DiagramController(
            DiagramRepository diagramRepository, AdminAuthorizationService adminAuthorizationService) {
        this.diagramRepository = diagramRepository;
        this.adminAuthorizationService = adminAuthorizationService;
    }

    @PostMapping("/api/diagrams")
    public DiagramDetailResponse create(@RequestBody CreateDiagramRequest request) {
        // 指定されたプロジェクトにダイアグラムを作れるのはそのメンバー(またはadmin)だけ(issue #830)。
        adminAuthorizationService.requireProjectMemberOrAdminForResource(request.projectId());
        Diagram diagram = new Diagram();
        diagram.setProjectId(request.projectId());
        diagram.setName(blankToDefault(request.name()));
        diagram.setXml(request.xml());
        diagram.setSvg(request.svg());
        return toDetailResponse(diagramRepository.save(diagram));
    }

    @GetMapping("/api/diagrams")
    public List<DiagramSummaryResponse> list(@RequestParam(required = false) Long projectId) {
        // projectId 指定時はそのプロジェクトのメンバーに限定する(issue #830)。
        // 未指定は「全プロジェクトのダイアグラムを返す」なので admin に限定する。本来は
        // 「操作者が所属するプロジェクトの分だけ」返すべきだが、所属プロジェクトの一覧を
        // 引く手段が media-service に無い(内部ブリッジは isProjectMember だけ)。
        // #583 で project_users が project-service へ移った後に絞り込みへ置き換える。
        if (projectId != null) {
            adminAuthorizationService.requireProjectMemberOrAdmin(projectId);
        } else {
            adminAuthorizationService.requireAdmin();
        }
        List<Diagram> diagrams = projectId != null
                ? diagramRepository.findAllByProjectIdOrderByCreatedAtDesc(projectId)
                : diagramRepository.findAllByOrderByCreatedAtDesc();
        return diagrams.stream().map(this::toSummaryResponse).toList();
    }

    @GetMapping("/api/diagrams/{id}")
    public DiagramDetailResponse get(@PathVariable Long id) {
        return toDetailResponse(findAuthorized(id));
    }

    @GetMapping("/api/diagrams/{id}/svg")
    public ResponseEntity<String> getSvg(@PathVariable Long id) {
        Diagram diagram = findAuthorized(id);
        return ResponseEntity.ok()
                .contentType(MediaType.valueOf("image/svg+xml;charset=UTF-8"))
                .header(HttpHeaders.CONTENT_DISPOSITION, "inline; filename=" + id + ".svg")
                .body(diagram.getSvg());
    }

    @PutMapping("/api/diagrams/{id}")
    public DiagramDetailResponse update(@PathVariable Long id, @RequestBody UpdateDiagramRequest request) {
        Diagram diagram = findAuthorized(id);
        diagram.setName(blankToDefault(request.name()));
        diagram.setXml(request.xml());
        diagram.setSvg(request.svg());
        return toDetailResponse(diagramRepository.save(diagram));
    }

    @DeleteMapping("/api/diagrams/{id}")
    public ResponseEntity<Void> delete(@PathVariable Long id) {
        diagramRepository.delete(findAuthorized(id));
        return ResponseEntity.noContent().build();
    }

    private DiagramDetailResponse toDetailResponse(Diagram diagram) {
        return new DiagramDetailResponse(
                diagram.getId(),
                diagram.getProjectId(),
                diagram.getName(),
                diagram.getXml(),
                diagram.getSvg(),
                diagram.getCreatedAt(),
                diagram.getUpdatedAt()
        );
    }

    private DiagramSummaryResponse toSummaryResponse(Diagram diagram) {
        return new DiagramSummaryResponse(
                diagram.getId(),
                diagram.getProjectId(),
                diagram.getName(),
                diagram.getCreatedAt(),
                diagram.getUpdatedAt()
        );
    }

    /**
     * IDでダイアグラムを引き、その所属プロジェクトのメンバー(またはadmin)であることを確かめる
     * (issue #830)。所属を調べるには一度読む必要があるため、存在確認と認可をここでまとめる。
     */
    private Diagram findAuthorized(Long id) {
        Diagram diagram = findOrThrow(id);
        adminAuthorizationService.requireProjectMemberOrAdminForResource(diagram.getProjectId());
        return diagram;
    }

    private Diagram findOrThrow(Long id) {
        return diagramRepository.findById(id)
                .orElseThrow(() -> new DiagramNotFoundException("id: " + id));
    }

    private String blankToDefault(String name) {
        if (name == null || name.isBlank()) {
            return "無題のダイアグラム";
        }
        return name;
    }
}
