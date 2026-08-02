package com.letsblog.api.cms.ssh;

import java.util.List;

/**
 * SSH経由でのコマンド実行・ファイル転送を抽象化するインターフェース。
 * WordPressSshOperationsはこれにのみ依存し、単体テストではモックに差し替える。
 */
public interface SshCommandExecutor {

    /**
     * リモートホストでコマンドを実行する。stdinがnullでなければ、その内容をプロセスの標準入力へ書き込む
     * (大きなHTML本文をシェル引数として展開せず安全に渡すため)。
     */
    SshCommandResult exec(SshConnectionParams params, String command, byte[] stdin);

    /**
     * 1回の接続確立で複数のコマンドを順番に実行する(コマンドごとに別セッションを使うが、
     * 接続・認証は1回だけ)。比較テーブルの初期表示のように、同じホストへ複数種類の一覧取得を
     * まとめて行う場合に使う(接続のたびに新規TCP接続・認証をする{@link #exec}を種類ごとに
     * 呼ぶと、同時接続数を制限している共有ホスティングでConnection refusedになりやすいため)。
     */
    List<SshCommandResult> execAll(SshConnectionParams params, List<String> commands);

    /**
     * バイト列をリモートの指定パスへ転送する(SFTP)。
     */
    void putFile(SshConnectionParams params, byte[] data, String remotePath);

    /**
     * リモートの指定パスのファイルを削除する。失敗しても例外は投げない(呼び出し側でログ出力する想定)。
     */
    void removeFile(SshConnectionParams params, String remotePath);

    /**
     * observedHostKeyFingerprintは、この呼び出しで実際にリモートが提示したホスト鍵のfingerprint。
     * params.hostKeyFingerprint()が未設定(初回接続=TOFU)の場合、呼び出し元はこれを永続化して
     * 以降の接続で検証に使う。
     */
    record SshCommandResult(int exitStatus, String stdout, String stderr, String observedHostKeyFingerprint) {
        public boolean ok() {
            return exitStatus == 0;
        }
    }

    record SshConnectionParams(
            String host,
            int port,
            String user,
            String privateKeyPem,
            String hostKeyFingerprint
    ) {
    }
}
