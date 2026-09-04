package com.letsblog.media.config;

import com.letsblog.media.render.RechartsRenderer;
import com.microsoft.playwright.Browser;
import com.microsoft.playwright.Playwright;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.context.annotation.Lazy;

import java.lang.annotation.Annotation;
import java.lang.reflect.AnnotatedElement;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Chromium の実行バイナリが無いホストでも media-service の ApplicationContext が起動することの
 * 回帰テスト(issue #1020)。
 *
 * <p><b>なぜ必要か。</b>{@link PlaywrightConfig} の {@code playwright}/{@code browser} には
 * 元から {@code @Lazy} が付いていた。それでも開発ホストでは {@code @SpringBootTest} が46件
 * 全滅していた。Bean 定義側の {@code @Lazy} は、<b>eager な消費者が直接注入すると効かない</b>
 * ためである。実際の連鎖は
 * {@code renderController → rechartsRenderer(ctor param 0) → browser → BrowserType.launch}
 * で、{@code chrome-headless-shell: libatk-1.0.so.0 が無い} で落ちていた。
 *
 * <p>落ちていたのは認可マトリクス(#772)・AdminAuthorization(#644)・Flyway 契約(#914)という
 * レンダリングと無関係なテストばかりで、ローカルでは常に赤のまま誰も読まない状態になっていた。
 *
 * <p>このテストは Chromium も DB も要らない。壊れたときに症状が出にくい類の退行(#994 / #1039 と
 * 同型)なので、「今直っている」ことではなく「<b>将来また eager な消費者が増えたときに気づける</b>」
 * ことを狙って2本立てにしてある。
 */
@DisplayName("media-service: Chromiumが無くてもコンテキストが起動する(issue #1020)")
class PlaywrightLazyBrowserTest {

    /** {@link PlaywrightConfig} が定義する、実体化にChromiumの起動を伴うBean。 */
    private static final Set<String> PLAYWRIGHT_BEANS = Set.of("playwright", "browser");

    /** 遅延注入が必須の型。どちらも実体化がChromiumの起動(または driver の展開)を伴う。 */
    private static final Set<Class<?>> BROWSER_TYPES = Set.of(Browser.class, Playwright.class);

    /**
     * 本丸。{@link RechartsRenderer} を含めてコンテキストを起動しても、Chromium は起動されない。
     *
     * <p>Chromium が有るホストでも意味を持つように、「例外が出ないこと」ではなく
     * 「シングルトンが<b>作られていないこと</b>」を見る。Chromium 入りのコンテナで実行した場合、
     * 遅延が壊れていれば起動自体は成功してしまうため、例外の有無だけでは検知できない。
     */
    @Test
    @DisplayName("RechartsRendererを含むコンテキストを起動してもbrowser/playwrightは実体化されない")
    void コンテキスト起動時にChromiumは起動されない() {
        try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext()) {
            context.register(PlaywrightConfig.class, RechartsRenderer.class);

            context.refresh();

            assertThat(context.getBean(RechartsRenderer.class))
                    .as("RechartsRenderer自体は従来どおり eager singleton として生成される")
                    .isNotNull();
            ConfigurableListableBeanFactory beanFactory = context.getBeanFactory();
            for (String beanName : PLAYWRIGHT_BEANS) {
                assertThat(beanFactory.containsSingleton(beanName))
                        .as("%s が起動時に実体化されている。Chromiumの無いホストでは"
                                + " ApplicationContext ごと落ちる(issue #1020)", beanName)
                        .isFalse();
            }
        }
    }

    /**
     * ラチェット。{@code com.letsblog.media} のSpring管理コンポーネントに、{@code @Lazy} の無い
     * {@link Browser}/{@link Playwright} の注入点が現れたら失敗する。
     *
     * <p>上のテストは {@link RechartsRenderer} という<b>今分かっている</b>消費者しか見ない。
     * 別のサービスが後から {@code Browser} を直接注入すれば、同じ形で再発する。走査は
     * Spring コンテキストを起動しない純粋な反射なので、DBもコンテナもChromiumも要らない
     * ({@code AuthorizationCoverageTest} と同じ流儀)。
     */
    @Test
    @DisplayName("Browser/Playwrightの注入点はすべて@Lazyである(eagerな消費者が増えていない)")
    void Browserの注入点はすべて遅延である() {
        List<String> violations = scanEagerInjectionPoints("com.letsblog.media");

        assertThat(violations)
                .as("Chromiumの起動を伴うBeanを、遅延させずに注入している箇所があります。"
                        + " その注入点に @Lazy を付けてください(issue #1020)")
                .isEmpty();
    }

    private static List<String> scanEagerInjectionPoints(String basePackage) {
        List<String> violations = new ArrayList<>();
        ClassPathScanningCandidateComponentProvider scanner =
                new ClassPathScanningCandidateComponentProvider(true);
        for (BeanDefinition definition : scanner.findCandidateComponents(basePackage)) {
            Class<?> type = resolve(definition.getBeanClassName());
            if (type == null) {
                continue;
            }
            boolean classIsLazy = type.isAnnotationPresent(Lazy.class);
            for (Constructor<?> constructor : type.getDeclaredConstructors()) {
                collectParameterViolations(violations, type, constructor.getParameters(), classIsLazy);
            }
            for (Field field : type.getDeclaredFields()) {
                if (field.isAnnotationPresent(Autowired.class)
                        && isBrowserType(field.getType())
                        && !isLazy(field, classIsLazy)) {
                    violations.add(describe(type, field.getName(), field.getType()));
                }
            }
            for (Method method : type.getDeclaredMethods()) {
                if (method.isAnnotationPresent(Bean.class)) {
                    // @Bean メソッド自身が @Lazy なら、その引数の解決も遅延する。
                    collectParameterViolations(violations, type, method.getParameters(),
                            classIsLazy || method.isAnnotationPresent(Lazy.class));
                }
            }
        }
        return violations;
    }

    private static void collectParameterViolations(List<String> violations, Class<?> owner,
                                                   Parameter[] parameters, boolean inheritedLazy) {
        for (Parameter parameter : parameters) {
            if (isBrowserType(parameter.getType()) && !isLazy(parameter, inheritedLazy)) {
                violations.add(describe(owner, parameter.getName(), parameter.getType()));
            }
        }
    }

    private static boolean isBrowserType(Class<?> type) {
        return BROWSER_TYPES.contains(type);
    }

    private static boolean isLazy(AnnotatedElement element, boolean inheritedLazy) {
        return inheritedLazy || hasAnnotation(element, Lazy.class);
    }

    private static boolean hasAnnotation(AnnotatedElement element, Class<? extends Annotation> annotation) {
        return element.isAnnotationPresent(annotation);
    }

    private static String describe(Class<?> owner, String name, Class<?> type) {
        return owner.getName() + " の " + name + " (" + type.getSimpleName() + ")";
    }

    private static Class<?> resolve(String className) {
        if (className == null) {
            return null;
        }
        try {
            return Class.forName(className, false, PlaywrightLazyBrowserTest.class.getClassLoader());
        } catch (ClassNotFoundException | LinkageError e) {
            return null;
        }
    }
}
