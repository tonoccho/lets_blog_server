# language: ja
@platform
機能: GitLabの接続先と到達性(連携サービスの状況)

  GitLab の接続先(URL)をシステム設定に持ち、連携サービスの状況に GitLab の行を出す(issue #1796。
  親 Epic #1794。前例は外部 Penpot の #1774)。到達性は `GET <URL>/users/sign_in` の応答で判定し、
  2xx だけを正常とする。3秒でタイムアウトし、再試行はしない。環境変数の既定値は持たない。

  ## 何を、どの層で確かめるか

  GitLab の接続先は ConnectionDestinationGuard の検査を通さない(`lbx-*` のホスト名をそのまま指定できる)。
  そのため受け入れテストの同じネットワーク上の GitLab スタブ(`lbx-gitlab`、`infra/e2e-stubs/gitlab/`)へ
  実際に届く。応答の 2xx 判定・3xx の非追従・タイムアウトの境界は、サービステスト
  (`GitLabReachabilityCheckerTest` / `ConnectedServiceStatusServiceGitLabTest`)でも固定している。
  GitLab の設定を書き換え、スタブへ異常を注入するので、他の受け入れシナリオと並行しないよう
  `@destructive` で単独実行する。

  @destructive
  シナリオ: システム設定画面でGitLabのURLを保存でき、再読込後も表示される
    前提 システム全体のGitLabのURLを未設定にする
    かつ 管理者としてログインする
    もし システム設定画面でGitLabのURLを「http://lbx-gitlab」で保存する
    ならば ページを開き直しても、保存したGitLabのURLが「http://lbx-gitlab」と表示される

  @destructive
  シナリオ: GitLabのURLが設定済みで応答が2xxなら、連携サービスの状況のGitLabの行は正常になる
    前提 GitLabスタブが初期状態である
    かつ システム全体のGitLabのURLを「http://lbx-gitlab」に設定する
    かつ 管理者としてログインする
    もし トップページを開く
    ならば 連携サービスの状況の「GitLab」の行は正常と表示される

  @destructive
  シナリオ: GitLabが2xx以外を返すと、連携サービスの状況のGitLabの行はエラーになり理由が表示される
    前提 GitLabスタブが初期状態である
    かつ GitLabスタブが「500」を返し続けるようにする
    かつ システム全体のGitLabのURLを「http://lbx-gitlab」に設定する
    かつ 管理者としてログインする
    もし トップページを開く
    ならば 連携サービスの状況の「GitLab」の行はエラーで理由「2xx 以外」が表示される

  @destructive
  シナリオ: GitLabの応答が3秒を超えると、連携サービスの状況のGitLabの行はエラーになり理由が表示される
    前提 GitLabスタブが初期状態である
    かつ GitLabスタブの応答を「6000」ミリ秒遅らせ続けるようにする
    かつ システム全体のGitLabのURLを「http://lbx-gitlab」に設定する
    かつ 管理者としてログインする
    もし トップページを開く
    ならば 連携サービスの状況の「GitLab」の行はエラーで理由「到達できません」が表示される

  @destructive
  シナリオ: 接続できないGitLabのURLを設定すると、連携サービスの状況のGitLabの行はエラーになり理由が表示される
    前提 システム全体のGitLabのURLを「http://192.168.77.10:3000」に設定する
    かつ 管理者としてログインする
    もし トップページを開く
    ならば 連携サービスの状況の「GitLab」の行はエラーで理由「到達できません」が表示される

  @destructive
  シナリオ: システム設定にGitLabのURLが無いときも、連携サービスの状況のGitLabの行は省かれず「未設定」の警告と設定への案内が表示される
    前提 システム全体のGitLabのURLを未設定にする
    かつ 管理者としてログインする
    もし トップページを開く
    ならば 連携サービスの状況の「GitLab」の行は警告で「未設定(システム設定で設定してください)」と表示される
