package com.letsblog.api.controller;

import com.letsblog.api.dto.PostPublishResponse;
import com.letsblog.api.service.PostDeleteService;
import com.letsblog.api.service.PostPublishService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * legacy-apiのPostControllerのうち残っている公開(publish)・削除(delete)のみを検証する(issue #576)。
 * 参照系(list/lookupBySlug)はcontent-serviceへ移設したため、その振る舞いはcontent-service側の
 * PostControllerTestで検証する。
 */
@ExtendWith(MockitoExtension.class)
class PostControllerTest {

    @Mock
    private PostPublishService postPublishService;

    @Mock
    private PostDeleteService postDeleteService;

    private PostController controller() {
        return new PostController(postPublishService, postDeleteService);
    }

    @Test
    void publish_PostPublishServiceへ委譲する() {
        PostController controller = controller();
        PostPublishResponse response = new PostPublishResponse("42", "https://example.com/p/42", "publish");
        when(postPublishService.publish(any())).thenReturn(response);

        PostPublishResponse result = controller.publish(
                "main", "title", "slug", "draft", null, null, null, "markdown", null, null, null, null);

        assertEquals(response, result);
    }

    @Test
    void delete_PostDeleteServiceへ委譲する() {
        PostController controller = controller();

        controller.delete("main", "99");

        verify(postDeleteService).delete("main", "99");
    }
}
