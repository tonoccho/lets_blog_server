package com.letsblog.content.config;

import com.microsoft.playwright.Browser;
import com.microsoft.playwright.BrowserType;
import com.microsoft.playwright.Playwright;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Lazy;

import java.util.List;
import java.util.concurrent.locks.ReentrantLock;

/**
 * [blogcard]/[amazon] 組み込みタグ向けスクレイピング(PlaywrightPageFetcher)、記事プレビューの
 * テーマ骨格取得(PreviewSkeletonFetcher)で使うヘッドレスChromiumを、実際に必要になったタイミングで
 * 1つ起動し、以後のリクエストで使い回す(Browser起動はプロセス生成を伴い数百ms〜数秒かかるため)。
 * legacy-apiのPlaywrightConfigと同一の実装(#576でPlaywrightを持つのはcontent-serviceになった)。
 *
 * @Lazyにしているのは、Chromiumの実行バイナリが存在しない環境(ブラウザインストールをまだ
 * 行っていないローカル開発機やCI等)でもアプリ自体は起動・他機能のテストができるようにするため。
 * PlaywrightもBrowserもプロセス/ネイティブリソースを保持するため、アプリ終了時にcloseする。
 *
 * <p><b>ここの{@code @Lazy}だけでは足りない(issue #1046)。</b>Bean定義側の{@code @Lazy}は、
 * eagerな消費者が{@link Browser}を直接注入した時点で効かなくなる。実際、
 * {@link com.letsblog.content.contentcache.PlaywrightPageFetcher}と
 * {@link com.letsblog.content.service.PreviewSkeletonFetcher}(どちらもeager singleton)が
 * コンストラクタで素の{@code Browser}を受け取っていたため、Chromiumの無いホストでは
 * {@code @SpringBootTest}が248件中53件全滅していた。<b>新たに{@code Browser}/{@code Playwright}を
 * 使うBeanを足すときは、その注入点にも{@code @Lazy}を付けること。</b>
 * media-serviceで先に同じ欠陥を直している(#1020)。付け忘れは
 * {@code PlaywrightLazyBrowserTest}のラチェットが検知する。
 */
@Configuration
public class PlaywrightConfig {

    /**
     * {@link Browser}(= {@code Connection})への排他アクセス用ロック(issue #1047)。
     *
     * <p><b>なぜ必要か。</b>Playwright JavaバインディングのConnection(Browser1個につき1本の
     * メッセージパイプ、{@code com.microsoft.playwright.impl.Connection})はスレッドセーフでは
     * ない(playwright-1.62.0.jarを逆アセンブルして{@code synchronized}が無いことを確認済み)。
     * 複数スレッドから排他なく{@code newPage()}/{@code newContext()}以降のPlaywright呼び出しを
     * 行うと、{@code PlaywrightException: Cannot find object to call pausedStateChanged}で
     * HTTP 502になる(実運用で確認済み: {@code [blogcard]}スクレイピングは4並行で4/4が失敗)。
     *
     * <p><b>{@code BrowserContext}を分けても直らない。</b>{@code BrowserContext}/{@code Page}を
     * 分けても、それらが発行するRPCは結局同じ{@code Connection}を経由するため競合は解消しない。
     * Playwright Javaの公式スレッドモデルも「Playwrightインスタンスは作成したスレッドに束縛される」
     * という前提であり、複数スレッドからの同時アクセスはそもそも想定されていない。
     *
     * <p><b>なぜクラスごとの{@code synchronized(this)}ではなく共有ロックBeanなのか。</b>
     * content-serviceには{@link Browser}の消費者が{@code PlaywrightPageFetcher}と
     * {@code PreviewSkeletonFetcher}の<b>2クラス</b>あり、どちらも同じ{@code Browser}Bean
     * (= 同じ{@code Connection})を注入される。片方のクラス内だけ{@code synchronized}にしても、
     * もう片方のクラスの呼び出しと同時に来れば競合は残る(media-serviceは消費者が
     * {@code RechartsRenderer}1クラスのみなので、{@code synchronized(this)}で足りている)。
     * したがって<b>クラスをまたいで共有できる1個のロック</b>が要る。
     *
     * <p>{@code @Lazy}な{@link Browser}への注入点は、Spring側の実装により<b>注入点ごとに
     * 異なるプロキシオブジェクト</b>が作られる(実測: 2つの{@code @Lazy Browser}注入点で
     * {@code identityHashCode}が一致しないことを確認済み)。そのため{@code synchronized(browser)}
     * のように注入された{@code Browser}自体をモニタにする案は<b>採らない</b>
     * (クラスをまたいだ排他にならない)。このBean自体は{@code @Lazy}にしていない
     * (単なる{@link ReentrantLock}オブジェクトの生成はChromiumの起動を伴わないため、
     * Chromiumの無いホストでもアプリ起動に影響しない)。
     *
     * <p><b>スループットへの影響。</b>直列化により、{@code [blogcard]}/{@code [amazon]}の
     * スクレイピングと記事プレビューの骨格取得が同時に来ると待ち行列に積まれる。
     * 1回の取得は数百ms〜数秒(ナビゲーションタイムアウトは最大30秒)だが、いずれも
     * 高頻度・大量並行が常態の経路ではない(記事保存時・プレビュー表示時の単発呼び出し)ため、
     * 許容範囲と判断した。将来問題になった場合は、{@code Browser}/{@code BrowserContext}を
     * 複数プールする設計への変更を検討すること(issue #1047のGoal参照。プーリング自体は
     * 本Issueのスコープ外)。
     *
     * <p>回帰テスト: {@code PlaywrightPageFetcherConcurrentAccessTest}・
     * {@code PreviewSkeletonFetcherConcurrentAccessTest}・{@code PlaywrightSharedBrowserAccessTest}
     * (クラスをまたいだ排他を検証する)。
     */
    @Bean
    public ReentrantLock browserAccessLock() {
        return new ReentrantLock();
    }

    @Lazy
    @Bean(destroyMethod = "close")
    public Playwright playwright() {
        return Playwright.create();
    }

    @Lazy
    @Bean(destroyMethod = "close")
    public Browser browser(Playwright playwright) {
        return playwright.chromium().launch(new BrowserType.LaunchOptions()
                .setHeadless(true)
                // コンテナはrootユーザーで実行されるため、Chromiumのサンドボックスがrootでの起動を拒否する。
                // --disable-dev-shm-usageは/dev/shmが小さいDocker環境でのクラッシュを避けるための定番設定。
                .setArgs(List.of("--no-sandbox", "--disable-dev-shm-usage")));
    }
}
