package com.letsblog.content.contentcache;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.letsblog.content.domain.ContentCache;
import com.letsblog.content.domain.ContentType;
import com.letsblog.content.dto.ContentCacheResponse;
import com.letsblog.content.dto.UtcDateTimes;
import com.letsblog.content.repository.ContentCacheRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.net.URI;
import java.net.URISyntaxException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * [blogcard]/[amazon] 組み込みタグ向けに、URLのスクレイピング結果をDBにキャッシュして提供する。
 * TTL(app.content-cache-ttl-hours)以内はキャッシュを返し、TTLを過ぎたら再スクレイピングして、
 * 内容(dataJsonのハッシュ)が実際に変化した場合のみキャッシュを更新する。
 */
@Service
@Slf4j
public class ContentCacheService {

    private static final Set<String> AMAZON_HOSTS_CONTAINS = Set.of("amazon.");
    private static final Set<String> AMAZON_HOSTS_EXACT = Set.of("amzn.to", "amzn.asia");

    private final ContentCacheRepository contentCacheRepository;
    private final PlaywrightPageFetcher pageFetcher;
    private final OgpMetadataParser ogpMetadataParser;
    private final AmazonProductParser amazonProductParser;
    private final ObjectMapper objectMapper;
    private final OutboundUrlGuard outboundUrlGuard;
    private final long ttlHours;

    public ContentCacheService(
            ContentCacheRepository contentCacheRepository,
            PlaywrightPageFetcher pageFetcher,
            OgpMetadataParser ogpMetadataParser,
            AmazonProductParser amazonProductParser,
            ObjectMapper objectMapper,
            OutboundUrlGuard outboundUrlGuard,
            @Value("${app.content-cache-ttl-hours}") long ttlHours) {
        this.contentCacheRepository = contentCacheRepository;
        this.pageFetcher = pageFetcher;
        this.ogpMetadataParser = ogpMetadataParser;
        this.amazonProductParser = amazonProductParser;
        this.objectMapper = objectMapper;
        this.outboundUrlGuard = outboundUrlGuard;
        this.ttlHours = ttlHours;
    }

    @Transactional
    public ContentCacheResponse resolve(String rawUrl) {
        URI uri = parseUrl(rawUrl);
        String normalizedUrl = uri.toString();
        // 宛先が外部の公開ページであることを、キャッシュ照会より前に確かめる(issue #902)。
        // キャッシュヒット時でも通すのは、過去に許可されていた宛先が内部アドレスへ
        // 向け直された場合に、古い結果を返し続けないため。
        outboundUrlGuard.requireAllowed(normalizedUrl);
        ContentType type = resolveType(uri);
        String urlHash = sha256Hex(normalizedUrl);

        ContentCache existing = contentCacheRepository.findByUrlHash(urlHash).orElse(null);
        if (existing != null && !isStale(existing)) {
            return toResponse(existing);
        }

        Map<String, String> scraped;
        try {
            String html = pageFetcher.fetchHtml(normalizedUrl);
            scraped = type == ContentType.AMAZON
                    ? amazonProductParser.parse(html, normalizedUrl)
                    : ogpMetadataParser.parse(html, normalizedUrl);
        } catch (ContentScrapingException e) {
            if (existing != null) {
                log.warn("URLの再スクレイピングに失敗したため、既存のキャッシュを返します: url={}, error={}",
                        normalizedUrl, e.getMessage());
                return toResponse(existing);
            }
            throw e;
        }

        return saveResult(existing, normalizedUrl, urlHash, type, scraped);
    }

    private ContentCacheResponse saveResult(
            ContentCache existing, String normalizedUrl, String urlHash, ContentType type, Map<String, String> scraped) {
        String dataJson = writeJson(scraped);
        String contentHash = sha256Hex(dataJson);
        LocalDateTime now = LocalDateTime.now();
        boolean changed = existing == null || !contentHash.equals(existing.getContentHash());

        ContentCache cache = existing != null ? existing : new ContentCache();
        cache.setUrl(normalizedUrl);
        cache.setUrlHash(urlHash);
        cache.setContentType(type);
        cache.setLastCheckedAt(now);
        if (changed) {
            cache.setDataJson(dataJson);
            cache.setContentHash(contentHash);
            cache.setLastUpdatedAt(now);
        }

        ContentCache saved = contentCacheRepository.save(cache);
        log.info("コンテンツキャッシュを更新しました: url={}, type={}, changed={}", normalizedUrl, type, changed);
        return toResponse(saved);
    }

    private boolean isStale(ContentCache cache) {
        return cache.getLastCheckedAt().isBefore(LocalDateTime.now().minusHours(ttlHours));
    }

    private ContentType resolveType(URI uri) {
        String host = uri.getHost() == null ? "" : uri.getHost().toLowerCase(Locale.ROOT);
        boolean isAmazon = AMAZON_HOSTS_CONTAINS.stream().anyMatch(host::contains)
                || AMAZON_HOSTS_EXACT.contains(host);
        return isAmazon ? ContentType.AMAZON : ContentType.BLOGCARD;
    }

    private URI parseUrl(String rawUrl) {
        if (rawUrl == null || rawUrl.isBlank()) {
            throw new IllegalArgumentException("urlは必須です");
        }
        URI uri;
        try {
            uri = new URI(rawUrl.trim());
        } catch (URISyntaxException e) {
            throw new IllegalArgumentException("不正なURL形式です: " + rawUrl);
        }
        String scheme = uri.getScheme();
        boolean isHttpOrHttps = scheme != null && ("http".equalsIgnoreCase(scheme) || "https".equalsIgnoreCase(scheme));
        if (!isHttpOrHttps || uri.getHost() == null) {
            throw new IllegalArgumentException("http/https形式の絶対URLを指定してください: " + rawUrl);
        }
        return uri;
    }

    private ContentCacheResponse toResponse(ContentCache cache) {
        return new ContentCacheResponse(
                cache.getUrl(), cache.getContentType(), readJson(cache.getDataJson()),
                UtcDateTimes.toInstant(cache.getLastCheckedAt()),
                UtcDateTimes.toInstant(cache.getLastUpdatedAt()));
    }

    private String writeJson(Map<String, String> data) {
        try {
            return objectMapper.writeValueAsString(data);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("スクレイピング結果のシリアライズに失敗しました", e);
        }
    }

    private Map<String, String> readJson(String json) {
        try {
            return objectMapper.readValue(json, new TypeReference<Map<String, String>>() {
            });
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("キャッシュ済みデータのパースに失敗しました", e);
        }
    }

    private String sha256Hex(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256アルゴリズムが利用できません", e);
        }
    }
}
