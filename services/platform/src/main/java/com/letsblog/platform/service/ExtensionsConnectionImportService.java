package com.letsblog.platform.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.net.ConnectException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * extensions のポータルの {@code GET /api/connections} から Ollama / ComfyUI / Penpot の接続先の候補を作り、
 * 管理者が確認した値だけをシステム設定へ保存する(issue #1783)。取得は設定を書き換えない。
 * タイムアウトは3秒、再試行なし、リダイレクトは追わず、TLS検証は既定のまま(自己署名は追加CAで信頼させる)。
 */
@Service
public class ExtensionsConnectionImportService {

    static final String PORTAL_URL_KEY = "extensions_portal_url";
    private static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(3);
    private static final ObjectMapper JSON = new ObjectMapper();

    public enum Route { SAME_PC, OTHER_HOST }

    public record Candidate(String key, String label, String currentValue, String candidateValue) {
    }

    public record FetchResult(List<Candidate> candidates, List<String> ignoredIds) {
    }

    public record Rejection(String key, String reason) {
    }

    public record ApplyResult(List<String> saved, List<Rejection> rejected) {
    }

    private record Mapping(String id, String key) {
    }

    private static final List<Mapping> MAPPINGS = List.of(
            new Mapping("ollama", AppSettingService.LLM_OLLAMA_BASE_URL),
            new Mapping("comfyui", AppSettingService.COMFYUI_BASE_URL),
            new Mapping("penpot", AppSettingService.PENPOT_BASE_URL));

    private final AppSettingService appSettingService;
    private final AdminAuthorizationService adminAuthorizationService;
    private final ExtensionsPortalUrlPolicy portalUrlPolicy;
    private final Duration timeout;
    private final HttpClient client;

    @Autowired
    public ExtensionsConnectionImportService(
            AppSettingService appSettingService,
            AdminAuthorizationService adminAuthorizationService,
            ExtensionsPortalUrlPolicy portalUrlPolicy) {
        this(appSettingService, adminAuthorizationService, portalUrlPolicy, DEFAULT_TIMEOUT);
    }

    ExtensionsConnectionImportService(
            AppSettingService appSettingService,
            AdminAuthorizationService adminAuthorizationService,
            ExtensionsPortalUrlPolicy portalUrlPolicy,
            Duration timeout) {
        this.appSettingService = appSettingService;
        this.adminAuthorizationService = adminAuthorizationService;
        this.portalUrlPolicy = portalUrlPolicy;
        this.timeout = timeout;
        this.client = HttpClient.newBuilder()
                .connectTimeout(timeout)
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
    }

    public FetchResult fetch(String portalUrl, Route route) {
        adminAuthorizationService.requireAdmin();
        portalUrlPolicy.check(portalUrl);
        JsonNode array = readConnections(portalUrl.trim());
        Map<String, JsonNode> byId = new LinkedHashMap<>();
        for (JsonNode element : array) {
            JsonNode id = element.isObject() ? element.get("id") : null;
            if (id == null || !id.isTextual() || id.asText().isBlank()) {
                throw new IllegalArgumentException("ポータルの応答の要素に「id」がありません");
            }
            byId.putIfAbsent(id.asText(), element);
        }
        Map<String, AppSettingService.SettingStatus> current = new LinkedHashMap<>();
        appSettingService.getAllSettings().forEach(s -> current.put(s.key(), s));
        List<Candidate> candidates = new ArrayList<>();
        for (Mapping mapping : MAPPINGS) {
            JsonNode element = byId.get(mapping.id());
            if (element != null) {
                AppSettingService.SettingStatus status = current.get(mapping.key());
                candidates.add(new Candidate(mapping.key(), status.label(), status.value(),
                        candidateValue(mapping.id(), element, route)));
            }
        }
        List<String> ignored = byId.keySet().stream()
                .filter(id -> MAPPINGS.stream().noneMatch(m -> m.id().equals(id)))
                .toList();
        return new FetchResult(candidates, ignored);
    }

    private static String candidateValue(String id, JsonNode element, Route route) {
        return switch (id) {
            case "ollama" -> route == Route.SAME_PC
                    ? url(id, element, "openaiBaseUrl")
                    : trimSlash(url(id, element, "publicUrl")) + "/v1";
            case "comfyui" -> route == Route.SAME_PC ? url(id, element, "internalUrl") : url(id, element, "publicUrl");
            default -> url(id, element, "publicUrl");
        };
    }

    private static String url(String id, JsonNode element, String field) {
        JsonNode node = element.get(field);
        if (node == null || !node.isTextual() || node.asText().isBlank()) {
            throw new IllegalArgumentException("ポータルの応答の「" + id + "」に「" + field + "」がありません");
        }
        String value = node.asText().trim();
        if (!value.startsWith("http://") && !value.startsWith("https://")) {
            throw new IllegalArgumentException(
                    "ポータルの応答の「" + id + "」の「" + field + "」はhttp://またはhttps://から始まるURLではありません");
        }
        return value;
    }

    private static String trimSlash(String value) {
        return value.endsWith("/") ? value.substring(0, value.length() - 1) : value;
    }

    private JsonNode readConnections(String portalUrl) {
        HttpRequest request = HttpRequest.newBuilder(URI.create(trimSlash(portalUrl) + "/api/connections"))
                .timeout(timeout)
                .header("Accept", "application/json")
                .GET()
                .build();
        HttpResponse<String> response;
        try {
            response = client.send(request, HttpResponse.BodyHandlers.ofString());
        } catch (HttpTimeoutException e) {
            throw new IllegalArgumentException("ポータルへの接続がタイムアウトしました", e);
        } catch (ConnectException e) {
            throw new IllegalArgumentException("ポータルに接続できません", e);
        } catch (IOException e) {
            throw new IllegalArgumentException("ポータルに接続できません(" + e.getClass().getSimpleName() + ")", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalArgumentException("ポータルへの接続が中断されました", e);
        }
        if (response.statusCode() / 100 != 2) {
            throw new IllegalArgumentException("ポータルがエラーを返しました(HTTP " + response.statusCode() + ")");
        }
        JsonNode root;
        try {
            root = JSON.readTree(response.body());
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("ポータルの応答がJSONではありません", e);
        }
        if (root == null || !root.isArray()) {
            throw new IllegalArgumentException("ポータルの応答が配列ではありません");
        }
        return root;
    }

    public ApplyResult apply(String portalUrl, Map<String, String> values) {
        adminAuthorizationService.requireAdmin();
        // 報告の順序は「ポータルのURL、続いて指定された順」。reasons には保存できなかった理由が入る。
        Map<String, String> pending = new LinkedHashMap<>();
        Map<String, String> reasons = new LinkedHashMap<>();
        List<String> order = new ArrayList<>();
        if (portalUrl != null && !portalUrl.isBlank()) {
            pending.put(PORTAL_URL_KEY, portalUrl.trim());
            order.add(PORTAL_URL_KEY);
        }
        if (values != null) {
            for (Map.Entry<String, String> entry : values.entrySet()) {
                String key = entry.getKey();
                order.add(key);
                if (MAPPINGS.stream().noneMatch(m -> m.key().equals(key))) {
                    reasons.put(key, key + " は取り込み対象外の設定項目です");
                } else if (entry.getValue() == null || entry.getValue().isBlank()) {
                    reasons.put(key, key + " の値が空です");
                } else {
                    pending.put(key, entry.getValue());
                }
            }
        }
        // 保存の実行順はキーの降順で固定する(入力の順序に依らず、同じ入力は同じ順に保存する)。
        // 1項目ずつ保存するので、拒否された項目があっても他の項目は保存される。
        TreeMap<String, String> execution = new TreeMap<>(Comparator.reverseOrder());
        execution.putAll(pending);
        execution.forEach((key, value) -> {
            try {
                appSettingService.updateSettings(Map.of(key, value));
            } catch (IllegalArgumentException e) {
                reasons.put(key, e.getMessage());
            }
        });
        List<String> saved = order.stream().filter(pending::containsKey).filter(k -> !reasons.containsKey(k)).toList();
        List<Rejection> rejected = order.stream().filter(reasons::containsKey)
                .map(k -> new Rejection(k, reasons.get(k))).toList();
        return new ApplyResult(saved, rejected);
    }
}
