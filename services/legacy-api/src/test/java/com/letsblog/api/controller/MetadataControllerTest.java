package com.letsblog.api.controller;

import com.letsblog.api.domain.Role;
import com.letsblog.api.dto.PostStatusOptionResponse;
import com.letsblog.api.dto.RoleOptionResponse;
import com.letsblog.api.service.RoleService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class MetadataControllerTest {

    @Mock
    private RoleService roleService;

    private MetadataController controller() {
        return new MetadataController(roleService);
    }

    @Test
    void postStatuses_全ステータスをvalueとlabelで返す() {
        MetadataController controller = controller();

        List<PostStatusOptionResponse> response = controller.postStatuses();

        assertEquals(4, response.size());
        assertEquals(new PostStatusOptionResponse("publish", "公開"), response.get(0));
        assertEquals(new PostStatusOptionResponse("draft", "下書き"), response.get(1));
        assertEquals(new PostStatusOptionResponse("pending", "レビュー待ち"), response.get(2));
        assertEquals(new PostStatusOptionResponse("private", "非公開"), response.get(3));
    }

    @Test
    void roles_権限を含まない表示名一覧を返す() {
        MetadataController controller = controller();
        Role role = new Role("EDITOR", "編集者");
        when(roleService.getAllRoles()).thenReturn(List.of(role));

        List<RoleOptionResponse> response = controller.roles();

        assertEquals(List.of(new RoleOptionResponse("EDITOR", "編集者")), response);
    }
}
