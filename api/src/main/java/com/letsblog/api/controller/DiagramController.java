package com.letsblog.api.controller;

import com.letsblog.api.domain.Diagram;
import com.letsblog.api.dto.CreateDiagramRequest;
import com.letsblog.api.dto.DiagramDetailResponse;
import com.letsblog.api.dto.DiagramSummaryResponse;
import com.letsblog.api.dto.UpdateDiagramRequest;
import com.letsblog.api.repository.DiagramRepository;
import com.letsblog.api.service.DiagramNotFoundException;
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

    public DiagramController(DiagramRepository diagramRepository) {
        this.diagramRepository = diagramRepository;
    }

    @PostMapping("/api/diagrams")
    public DiagramDetailResponse create(@RequestBody CreateDiagramRequest request) {
        Diagram diagram = new Diagram();
        diagram.setProjectId(request.projectId());
        diagram.setName(blankToDefault(request.name()));
        diagram.setXml(request.xml());
        diagram.setSvg(request.svg());
        return toDetailResponse(diagramRepository.save(diagram));
    }

    @GetMapping("/api/diagrams")
    public List<DiagramSummaryResponse> list(@RequestParam(required = false) Long projectId) {
        List<Diagram> diagrams = projectId != null
                ? diagramRepository.findAllByProjectIdOrderByCreatedAtDesc(projectId)
                : diagramRepository.findAllByOrderByCreatedAtDesc();
        return diagrams.stream().map(this::toSummaryResponse).toList();
    }

    @GetMapping("/api/diagrams/{id}")
    public DiagramDetailResponse get(@PathVariable Long id) {
        return toDetailResponse(findOrThrow(id));
    }

    @GetMapping("/api/diagrams/{id}/svg")
    public ResponseEntity<String> getSvg(@PathVariable Long id) {
        Diagram diagram = findOrThrow(id);
        return ResponseEntity.ok()
                .contentType(MediaType.valueOf("image/svg+xml;charset=UTF-8"))
                .header(HttpHeaders.CONTENT_DISPOSITION, "inline; filename=" + id + ".svg")
                .body(diagram.getSvg());
    }

    @PutMapping("/api/diagrams/{id}")
    public DiagramDetailResponse update(@PathVariable Long id, @RequestBody UpdateDiagramRequest request) {
        Diagram diagram = findOrThrow(id);
        diagram.setName(blankToDefault(request.name()));
        diagram.setXml(request.xml());
        diagram.setSvg(request.svg());
        return toDetailResponse(diagramRepository.save(diagram));
    }

    @DeleteMapping("/api/diagrams/{id}")
    public ResponseEntity<Void> delete(@PathVariable Long id) {
        diagramRepository.delete(findOrThrow(id));
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
