package com.letsblog.publishing.controller;

import com.letsblog.publishing.dto.PostPublishResponse;
import com.letsblog.publishing.service.PostDeleteService;
import com.letsblog.publishing.service.PostPublishService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * publishing-serviceのPostController(legacy-apiから移設。issue #707)の回帰テスト。
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
