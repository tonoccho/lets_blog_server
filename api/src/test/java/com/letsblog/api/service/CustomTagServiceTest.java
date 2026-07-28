package com.letsblog.api.service;

import com.letsblog.api.domain.CustomTag;
import com.letsblog.api.dto.CustomTagRequest;
import com.letsblog.api.dto.CustomTagResponse;
import com.letsblog.api.repository.CustomTagRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class CustomTagServiceTest {

    @Mock
    private CustomTagRepository customTagRepository;

    @Mock
    private AdminAuthorizationService adminAuthorizationService;

    private CustomTagService service;

    @BeforeEach
    void setUp() {
        service = new CustomTagService(customTagRepository, adminAuthorizationService);
    }

    @Test
    void create_admin権限がなければForbidden() {
        doThrow(new ForbiddenException("この操作にはadmin権限が必要です"))
                .when(adminAuthorizationService).requireAdmin();

        CustomTagRequest request = new CustomTagRequest("alert", "<div>{{content}}</div>", null, null);

        assertThrows(ForbiddenException.class, () -> service.create(request));
        verify(customTagRepository, never()).save(any());
    }

    @Test
    void create_タグ名が重複していれば例外() {
        CustomTagRequest request = new CustomTagRequest("alert", "<div>{{content}}</div>", null, null);
        when(customTagRepository.existsByTagName("alert")).thenReturn(true);

        assertThrows(IllegalArgumentException.class, () -> service.create(request));
    }

    @Test
    void create_正常にタグを作成する() {
        CustomTagRequest request = new CustomTagRequest("alert", "<div>{{content}}</div>", "注意書き", null);
        when(customTagRepository.existsByTagName("alert")).thenReturn(false);
        when(customTagRepository.save(any(CustomTag.class))).thenAnswer(invocation -> {
            CustomTag tag = invocation.getArgument(0);
            tag.setId(1L);
            return tag;
        });

        CustomTagResponse response = service.create(request);

        assertEquals("alert", response.tagName());
        assertEquals("<div>{{content}}</div>", response.htmlTemplate());
    }

    @Test
    void update_存在しなければ例外() {
        when(customTagRepository.findById(99L)).thenReturn(Optional.empty());
        CustomTagRequest request = new CustomTagRequest("alert", "<div>{{content}}</div>", null, null);

        assertThrows(CustomTagNotFoundException.class, () -> service.update(99L, request));
    }

    @Test
    void delete_存在しなければ例外() {
        when(customTagRepository.existsById(99L)).thenReturn(false);

        assertThrows(CustomTagNotFoundException.class, () -> service.delete(99L));
    }

    @Test
    void list_全件を返す() {
        CustomTag tag = new CustomTag();
        tag.setId(1L);
        tag.setTagName("alert");
        tag.setHtmlTemplate("<div>{{content}}</div>");
        when(customTagRepository.findAll()).thenReturn(List.of(tag));

        assertEquals(1, service.list().size());
    }
}
