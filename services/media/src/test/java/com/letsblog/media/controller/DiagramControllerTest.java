package com.letsblog.media.controller;

import com.letsblog.media.domain.Diagram;
import com.letsblog.media.dto.CreateDiagramRequest;
import com.letsblog.media.dto.DiagramDetailResponse;
import com.letsblog.media.dto.DiagramSummaryResponse;
import com.letsblog.media.dto.UpdateDiagramRequest;
import com.letsblog.media.repository.DiagramRepository;
import com.letsblog.media.service.DiagramNotFoundException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.ResponseEntity;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** DiagramControllerの回帰テスト(issue #476)。 */
@ExtendWith(MockitoExtension.class)
class DiagramControllerTest {

    @Mock
    private DiagramRepository diagramRepository;

    private DiagramController controller;

    @BeforeEach
    void setUp() {
        controller = new DiagramController(diagramRepository);
    }

    private Diagram buildDiagram(Long id, Long projectId, String name) {
        Diagram diagram = new Diagram();
        diagram.setId(id);
        diagram.setProjectId(projectId);
        diagram.setName(name);
        diagram.setXml("<mxGraphModel/>");
        diagram.setSvg("<svg></svg>");
        diagram.setCreatedAt(LocalDateTime.now());
        diagram.setUpdatedAt(LocalDateTime.now());
        return diagram;
    }

    @Test
    void list_projectId未指定時は全件を返す() {
        when(diagramRepository.findAllByOrderByCreatedAtDesc()).thenReturn(List.of(
                buildDiagram(1L, 10L, "フロー図"),
                buildDiagram(2L, 20L, "シーケンス図")));

        List<DiagramSummaryResponse> result = controller.list(null);

        assertEquals(2, result.size());
    }

    @Test
    void list_projectId指定時は絞り込む() {
        when(diagramRepository.findAllByProjectIdOrderByCreatedAtDesc(10L)).thenReturn(List.of(
                buildDiagram(1L, 10L, "フロー図")));

        List<DiagramSummaryResponse> result = controller.list(10L);

        assertEquals(1, result.size());
        assertEquals(1L, result.get(0).id());
        assertEquals("フロー図", result.get(0).name());
    }

    @Test
    void get_存在しないIDはDiagramNotFoundExceptionを投げる() {
        when(diagramRepository.findById(99L)).thenReturn(Optional.empty());

        assertThrows(DiagramNotFoundException.class, () -> controller.get(99L));
    }

    @Test
    void get_詳細にxmlとsvgを含む() {
        when(diagramRepository.findById(1L)).thenReturn(Optional.of(buildDiagram(1L, 10L, "フロー図")));

        DiagramDetailResponse result = controller.get(1L);

        assertEquals("<mxGraphModel/>", result.xml());
        assertEquals("<svg></svg>", result.svg());
    }

    @Test
    void create_新規作成してIDが採番される() {
        when(diagramRepository.save(any(Diagram.class))).thenAnswer(inv -> {
            Diagram saved = inv.getArgument(0);
            saved.setId(1L);
            saved.setCreatedAt(LocalDateTime.now());
            saved.setUpdatedAt(LocalDateTime.now());
            return saved;
        });

        DiagramDetailResponse result = controller.create(
                new CreateDiagramRequest(10L, "新しい図", "<mxGraphModel/>", "<svg></svg>"));

        assertEquals(1L, result.id());
        assertEquals("新しい図", result.name());
        assertEquals(10L, result.projectId());
    }

    @Test
    void create_名前未指定時はデフォルト名を補完する() {
        when(diagramRepository.save(any(Diagram.class))).thenAnswer(inv -> inv.getArgument(0));

        DiagramDetailResponse result = controller.create(
                new CreateDiagramRequest(10L, "", "<mxGraphModel/>", "<svg></svg>"));

        assertEquals("無題のダイアグラム", result.name());
    }

    @Test
    void update_上書き保存でxml_svgが更新される() {
        Diagram diagram = buildDiagram(1L, 10L, "旧タイトル");
        when(diagramRepository.findById(1L)).thenReturn(Optional.of(diagram));
        when(diagramRepository.save(any(Diagram.class))).thenAnswer(inv -> inv.getArgument(0));

        DiagramDetailResponse result = controller.update(
                1L, new UpdateDiagramRequest("新タイトル", "<mxGraphModel updated=\"1\"/>", "<svg>updated</svg>"));

        assertEquals("新タイトル", result.name());
        assertEquals("<mxGraphModel updated=\"1\"/>", result.xml());
        assertEquals("<svg>updated</svg>", result.svg());
    }

    @Test
    void update_存在しないIDはDiagramNotFoundExceptionを投げる() {
        when(diagramRepository.findById(99L)).thenReturn(Optional.empty());

        assertThrows(DiagramNotFoundException.class,
                () -> controller.update(99L, new UpdateDiagramRequest("名前", "<x/>", "<svg/>")));
    }

    @Test
    void getSvg_content_typeがimage_svg_xmlでUTF8指定() {
        when(diagramRepository.findById(1L)).thenReturn(Optional.of(buildDiagram(1L, 10L, "フロー図")));

        ResponseEntity<String> result = controller.getSvg(1L);

        assertEquals("image/svg+xml;charset=UTF-8", result.getHeaders().getContentType().toString());
        assertEquals("<svg></svg>", result.getBody());
    }

    @Test
    void delete_該当レコードを削除する() {
        Diagram diagram = buildDiagram(1L, 10L, "フロー図");
        when(diagramRepository.findById(1L)).thenReturn(Optional.of(diagram));

        ResponseEntity<Void> result = controller.delete(1L);

        assertEquals(204, result.getStatusCode().value());
        verify(diagramRepository, times(1)).delete(diagram);
    }

    @Test
    void delete_存在しないIDは削除せずDiagramNotFoundExceptionを投げる() {
        when(diagramRepository.findById(99L)).thenReturn(Optional.empty());

        assertThrows(DiagramNotFoundException.class, () -> controller.delete(99L));
        verify(diagramRepository, never()).delete(any(Diagram.class));
    }
}
