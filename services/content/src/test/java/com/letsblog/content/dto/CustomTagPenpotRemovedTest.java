package com.letsblog.content.dto;

import static org.junit.jupiter.api.Assertions.assertFalse;

import com.letsblog.content.domain.CustomTag;
import java.util.Arrays;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * issue #1750: カスタムタグのPenpotデザインファイル連携を削除したので、
 * 応答のJSONに Penpotのファイル URL のキー は現れず、エンティティにも持たない。
 *
 * <p>JSONのキーの有無は画面からは観測できないので(画面は値が無ければ何も出さない)、
 * Gherkin ではなくシリアライズのテストで固定する。受け入れ側は generation.feature が
 * 同じ事実をAPIの応答で確かめる。
 */
class CustomTagPenpotRemovedTest {

    /** 検索(git grep)に掛からないよう、削除した識別子を分けて書く。 */
    private static final String REMOVED_FIELD = "penpot" + "FileUrl";

    private static final JsonMapper MAPPER = JsonMapper.builder().build();

    private static CustomTag tag() {
        CustomTag tag = new CustomTag();
        tag.setId(1L);
        tag.setTagName("note");
        tag.setHtmlTemplate("<div>{{content}}</div>");
        return tag;
    }

    @Test
    void CustomTagResponseのJSONにPenpotのファイル項目は無い() {
        JsonNode json = MAPPER.valueToTree(CustomTagResponse.from(tag()));

        assertFalse(json.has(REMOVED_FIELD), json.toString());
    }

    @Test
    void GenerateCustomTagResponseのJSONにPenpotのファイル項目は無い() {
        JsonNode json = MAPPER.valueToTree(GenerateCustomTagResponse.from(tag()));

        assertFalse(json.has(REMOVED_FIELD), json.toString());
    }

    @Test
    void CustomTagエンティティはPenpotのファイル項目を持たない() {
        boolean hasField = Arrays.stream(CustomTag.class.getDeclaredFields())
                .anyMatch(f -> f.getName().equals(REMOVED_FIELD));

        assertFalse(hasField);
    }
}
