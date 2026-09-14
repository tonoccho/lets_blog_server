package com.letsblog.identity.service;

import com.letsblog.identity.domain.User;
import com.letsblog.identity.repository.UserRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * {@link UserService#migrateToKeycloak}が1ユーザーずつ呼ぶ、ローカル保存専用の協調Bean(issue #964)。
 *
 * <p><b>なぜ独立したBean/トランザクションが必要か</b>。{@code migrateToKeycloak}は1回の呼び出しで
 * 複数ユーザーを1ループで処理する。{@code UserService}自身のメソッド(例:
 * {@code this.saveAndFlush(...)})として実装すると、Spring AOPのプロキシを経由しないため
 * {@code @Transactional}が効かず、ループ全体が呼び出し元の(あるいは全く無い)トランザクション
 * 境界をそのまま使い続けてしまう。その状態で1ユーザーの{@code saveAndFlush}が失敗すると、
 * 同じHibernateセッション/永続化コンテキストを共有する他ユーザーの{@code saveAndFlush}まで
 * 巻き込んで失敗し始めたり、最終的にトランザクション全体がロールバックされて、既に
 * {@code migrated}として記録した(=Keycloakアカウント作成・パスワード再設定メール送信も
 * 済んでいる)ユーザーの保存まで失われたりし得る(レビュー指摘、issue #964)。これは
 * このIssueが解消しようとした「孤児ユーザー」問題を、場所を変えて再発させることになる。
 *
 * <p>そこで1ユーザー分のローカル保存だけを、独立したBeanのメソッドとして
 * {@code @Transactional(propagation = REQUIRES_NEW)}で実行する。自己注入
 * ({@code @Lazy @Autowired}で自分自身のプロキシを持つ)ではなく別Beanに切り出したのは、
 * このプロジェクトのテスト(モック注入によるコンストラクタインジェクション)の流儀に
 * 素直に乗るためであり、自己注入固有の落とし穴(プロキシがテストのモック構築を経由しない、
 * 循環初期化の分かりにくさ)を避けるためでもある。呼び出しごとに独立した新規トランザクションで
 * 実行されるため、あるユーザーの保存失敗が他のユーザーのセッション/永続化コンテキストへ
 * 波及することはなく、既に成功した(コミット済みの)ユーザーの保存が後続の失敗によって
 * 巻き戻されることもない。
 *
 * <p><b>この保証の検証範囲</b>。Mockitoによる単体テストは呼び出しの委譲(=
 * {@code migrateToKeycloak}自身が{@code userRepository.saveAndFlush}を直接呼ばず、
 * このBeanへ委譲していること)までしか検証できず、実際のHibernateセッションが
 * トランザクション境界どおりに分離されることまでは検証できない(モックはセッションを
 * 持たない)。実DBに対する統合テスト(例: {@code @DataJpaTest}やTestcontainers)で
 * 検証するのが本来望ましいが、本環境では{@code lbs-mysql}コンテナにポートが公開されておらず
 * 実行できない(#964のレビューコメント、実装者・レビュアー双方で確認済み)。そのため
 * このBeanへの切り出し自体を、モックでは検証しきれない保証をアーキテクチャ的に
 * 担保する手段として採用している(レビューが提示した選択肢(a))。
 */
@Service
public class UserMigrationPersister {

    private final UserRepository userRepository;

    public UserMigrationPersister(UserRepository userRepository) {
        this.userRepository = userRepository;
    }

    /**
     * 1ユーザー分の{@code keycloakSub}等の変更を、呼び出し元とは独立した新規トランザクションで
     * 即時にフラッシュする。呼び出し元が{@code @Transactional}であってもなくても、このメソッドの
     * 呼び出しは常に新しいトランザクション(=新しいHibernateセッション)で実行される
     * ({@code REQUIRES_NEW})。
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void saveAndFlush(User user) {
        userRepository.saveAndFlush(user);
    }
}
