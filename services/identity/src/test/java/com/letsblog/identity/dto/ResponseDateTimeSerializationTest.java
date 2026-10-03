package com.letsblog.identity.dto;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.letsblog.identity.domain.User;
import java.time.LocalDateTime;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * issue #1537: openapi/identity.json が format: date-time(RFC 3339、オフセット必須)と宣言する
 * 公開レスポンスの日時が、UTC の Z 終端で出力されること。
 *
 * <p>API の文字列形式は画面から観測できないため、Gherkin ではなくサービスレベルの
 * シリアライズテストで表現する。DB / エンティティは UTC の壁時計 LocalDateTime のまま、
 * DTO への変換時に UTC を付けるだけ。
 */
class ResponseDateTimeSerializationTest {

    private static final LocalDateTime A = LocalDateTime.of(2026, 9, 8, 20, 3, 35);
    private static final LocalDateTime B = LocalDateTime.of(2026, 9, 9, 1, 2, 3);

    private final JsonMapper mapper = JsonMapper.builder().build();

    private User user(LocalDateTime created, LocalDateTime updated) {
        User user = new User();
        user.setId(1L);
        user.setEmail("a@example.com");
        user.setRole("user");
        user.setCreatedAt(created);
        user.setUpdatedAt(updated);
        return user;
    }

    @Test
    void userProfileResponse_DBの壁時計と同じ実時刻のZ終端で返る() {
        JsonNode json = mapper.valueToTree(UserProfileResponse.from(user(A, B)));

        assertEquals("2026-09-08T20:03:35Z", json.get("createdAt").asString());
        assertEquals("2026-09-09T01:02:03Z", json.get("updatedAt").asString());
    }

    @Test
    void userResponse_DBの壁時計と同じ実時刻のZ終端で返る() {
        JsonNode json = mapper.valueToTree(UserResponse.from(user(A, B)));

        assertEquals("2026-09-08T20:03:35Z", json.get("createdAt").asString());
        assertEquals("2026-09-09T01:02:03Z", json.get("updatedAt").asString());
    }

    @Test
    void userProfileResponse_日時がnullならnullのまま() {
        JsonNode json = mapper.valueToTree(UserProfileResponse.from(user(null, null)));

        assertTrue(json.get("createdAt").isNull());
        assertTrue(json.get("updatedAt").isNull());
    }

    @Test
    void userResponse_日時がnullならnullのまま() {
        JsonNode json = mapper.valueToTree(UserResponse.from(user(null, null)));

        assertTrue(json.get("createdAt").isNull());
        assertTrue(json.get("updatedAt").isNull());
    }
}
