package com.letsblog.content.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** 組み込みタグの目印(`<!-- lbs:embed {JSON} -->`、issue #1563)をテストで取り出す。 */
final class EmbedMarkerTestSupport {

    private static final Pattern OPEN = Pattern.compile("<!-- lbs:embed (.*?) -->");
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private EmbedMarkerTestSupport() {
    }

    /** 最初の目印コメントのJSONを解釈して返す。目印がなければ null。 */
    static JsonNode firstMarker(String html) {
        Matcher matcher = OPEN.matcher(html);
        if (!matcher.find()) {
            return null;
        }
        try {
            return MAPPER.readTree(matcher.group(1));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("目印のJSONが壊れています: " + matcher.group(1), e);
        }
    }

    /** 開始コメントの生の文字列(JSONの文字エスケープを確かめるため)。 */
    static String firstMarkerRaw(String html) {
        Matcher matcher = OPEN.matcher(html);
        return matcher.find() ? matcher.group(1) : null;
    }

    static int closeCount(String html) {
        return html.split("<!-- /lbs:embed -->", -1).length - 1;
    }
}
