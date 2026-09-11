package com.letsblog.publishing.config.support;

import org.yaml.snakeyaml.Yaml;

import java.io.IOException;
import java.io.InputStream;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * issue #1095: {@code services/publishing/src/main/resources/application.yml}に実際に
 * 記述されている{@code spring.datasource.*}をそのまま読み取る。
 *
 * <p>テストが検証する設定値をJavaコード側に複製すると、application.ymlだけを変更した
 * 場合にテストが追随せず気づかれないまま乖離する。それを避けるため、本番のリソース
 * ファイルそのものをクラスパス経由で読む(このクラスはtestソースセットにあるが、
 * Gradleの標準的な構成により{@code src/main/resources}はテスト実行時クラスパスにも
 * 乗るため、"application.yml"というリソース名はmain側のものを指す)。
 */
public final class ProductionDataSourceYamlSettings {

    private final Map<String, Object> hikari;
    private final String urlTemplate;

    private ProductionDataSourceYamlSettings(Map<String, Object> hikari, String urlTemplate) {
        this.hikari = hikari;
        this.urlTemplate = urlTemplate;
    }

    @SuppressWarnings("unchecked")
    public static ProductionDataSourceYamlSettings loadFromMainApplicationYml() throws IOException {
        try (InputStream in = ProductionDataSourceYamlSettings.class
                .getClassLoader().getResourceAsStream("application.yml")) {
            if (in == null) {
                throw new IllegalStateException("application.ymlがクラスパス上に見つからない");
            }
            Map<String, Object> root = new Yaml().load(in);
            Map<String, Object> spring = (Map<String, Object>) root.get("spring");
            Map<String, Object> datasource = (Map<String, Object>) spring.get("datasource");
            Map<String, Object> hikari = (Map<String, Object>) datasource.get("hikari");
            String url = (String) datasource.get("url");
            return new ProductionDataSourceYamlSettings(hikari, url);
        }
    }

    /** {@code spring.datasource.hikari.<key>}の値(未設定ならnull)。 */
    public Object hikari(String key) {
        return hikari == null ? null : hikari.get(key);
    }

    public long hikariLong(String key, long defaultValue) {
        Object value = hikari(key);
        return value == null ? defaultValue : Long.parseLong(String.valueOf(value));
    }

    /**
     * {@code spring.datasource.url}のデフォルト値(${SPRING_DATASOURCE_URL:デフォルト}の
     * デフォルト部分)から、クエリ文字列(?以降)だけを取り出す。connectTimeout/socketTimeout等の
     * URLパラメータが本番設定に含まれているかを、実ファイルに対してそのまま検証するため。
     */
    public String extractQueryStringFromUrlDefault() {
        // ${SPRING_DATASOURCE_URL:jdbc:mysql://host:3306/db?a=b&c=d} という形式から
        // "?"以降を取り出す。
        Matcher matcher = Pattern.compile("\\$\\{SPRING_DATASOURCE_URL:[^}]*?\\?([^}]*)}")
                .matcher(urlTemplate);
        if (!matcher.find()) {
            return "";
        }
        return matcher.group(1);
    }
}
