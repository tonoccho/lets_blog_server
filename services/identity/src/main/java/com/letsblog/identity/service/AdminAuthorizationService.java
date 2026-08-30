package com.letsblog.identity.service;

import org.springframework.stereotype.Service;

/**
 * legacy-apiのAdminAuthorizationServiceのうち、users/roles管理に必要な部分のみを移設(#561)。
 * requireProjectMemberOrAdmin相当のプロジェクト単位のチェックはproject/site領域に依存するため
 * legacy-apiに残したまま(Phase 19のサービス抽出Issueで改めて整理する)。
 */
@Service
public class AdminAuthorizationService {

    /** {@code users.role}カラムのadmin区分({@code UserService.VALID_ROLES}と同じ値)。 */
    private static final String ADMIN_ROLE = "admin";

    private final CurrentActorService currentActorService;

    public AdminAuthorizationService(CurrentActorService currentActorService) {
        this.currentActorService = currentActorService;
    }

    public void requireAdmin() {
        if (!currentActorService.isAdmin()) {
            throw new ForbiddenException("この操作にはadmin権限が必要です");
        }
    }

    /**
     * admin権限を要求したうえで、対象が操作者自身でないことも要求する(issue #796、#798)。
     *
     * <p>自分自身を締め出す操作を防ぐ。最後のadminが自分のアカウントを消したり無効化したりすると
     * 誰も管理できない状態になる。Web側にも同じガードがあるが
     * ({@code web/src/app/users/actions.ts})、gatewayは認可判定を行わず(ADR-0008)、
     * アクセストークンを持つクライアントはAPIを直接叩けるため、サーバー側にも置く。
     *
     * <p><b>「最後のadminか」は数えない</b>(#798で改めて判断した)。adminが2人いれば
     * 互いに削除・無効化でき、それは正当な運用である。数える設計にすると、
     * 「他のadminが同時に自分を消す」レースで両者とも通ってしまう検査時-使用時の穴が生まれ、
     * 検査の意味が薄い。ここで防ぐのは「自分で自分を締め出す」ことだけに限定する。
     *
     * @param message 拒否時のメッセージ。操作(削除/無効化など)ごとに呼び出し元が指定する
     */
    public void requireAdminAndNotSelf(Long userId, String message) {
        requireAdmin();
        Long actorId = currentActorService.getCurrentActorId();
        if (actorId != null && actorId.equals(userId)) {
            throw new ForbiddenException(message);
        }
    }

    /**
     * 自分自身のadmin権限を手放す変更を禁止する(issue #798)。
     *
     * <p>{@code PATCH /api/users/{id}}は{@code users.role}を書き換えるため、adminが自分自身を
     * {@code role="user"}へ降格させられた。降格後は{@code requireAdmin()}を要求する
     * {@code PATCH}・{@code deactivate}・{@code reactivate}・{@code DELETE}がすべて閉じるため、
     * 他にadminがいなければDBを直接操作する以外に復旧手段が無い。自己削除・自己無効化を
     * 禁止しておきながら自己降格を残すのは、#798が#796について指摘したのと同じ非対称になる。
     *
     * <p>{@code PATCH}はパスワード変更にも使われるため、{@link #requireAdminAndNotSelf}のように
     * 操作そのものを禁止するのではなく、<b>自分自身に対するadmin以外へのrole変更</b>だけを拒否する。
     * {@code role}を指定しない更新(パスワードのみ)と、admin→adminの無変更は通す。
     *
     * <p>不正なrole文字列({@code "ADMIN"}のような大小違いや{@code "editor"}など)を自分自身に
     * 指定した場合、{@code UserService#validateRole}の400ではなく先にこのガードの403が返る。
     * {@code VALID_ROLES}が完全一致({@code Set.contains})なので、ここを通す値を増やすと
     * 「ガードは抜けたが role は書き換わらない」入力が生まれるため、大小を区別したまま
     * fail-closed 側に倒している。
     *
     * @param newRole リクエストが指定したrole。{@code null}(role未指定)なら何もしない
     */
    public void requireNotSelfDemotion(Long userId, String newRole) {
        if (newRole == null || ADMIN_ROLE.equals(newRole)) {
            return;
        }
        Long actorId = currentActorService.getCurrentActorId();
        if (actorId != null && actorId.equals(userId)) {
            throw new ForbiddenException("自分自身のadmin権限は外せません");
        }
    }

    /**
     * 本人のリソース操作、またはadmin権限を持つ操作者のみを許可する。
     */
    public void requireSelfOrAdmin(Long userId) {
        if (currentActorService.isAdmin()) {
            return;
        }
        Long actorId = currentActorService.getCurrentActorId();
        if (actorId == null || !actorId.equals(userId)) {
            throw new ForbiddenException("この操作には本人またはadmin権限が必要です");
        }
    }
}
