# GPU PC で ComfyUI を用意して Let's Blog から接続する

Let's Blog は ComfyUI を同梱しない(issue #1746)。画像生成に使う ComfyUI は、GPU 付きの別 PC(以下「GPU PC」)で
起動し、Let's Blog にはその URL を設定して接続する。Let's Blog のホストに GPU は要らない。

```
Let's Blog のホスト(media-service) ──HTTP──> GPU PC の ComfyUI(例: http://192.168.1.71:8188)
```

Let's Blog が ComfyUI に対して行うのは、画像の生成(`/prompt` `/history` `/view` `/upload/image`)と、
チェックポイント・サンプラー・LoRA などの一覧の取得(`/object_info/...`)、状態の確認(`/system_stats`)だけである。
**チェックポイントや LoRA のファイルを Let's Blog から導入・削除する機能は無い**(ComfyUI に追加・削除の API が無く、
別 PC のファイルには届かないため)。ファイルは GPU PC 側で置く。

## 1. GPU PC で ComfyUI を起動する

ComfyUI 本体の導入は ComfyUI の公式手順に従う。Let's Blog から届くようにするため、**全てのネットワークインタフェースで待ち受ける**
よう `--listen 0.0.0.0` を付けて起動する。

```bash
python main.py --listen 0.0.0.0 --port 8188
```

既定は `127.0.0.1` で待ち受けるため、`--listen` が無いと別 PC から接続できない。GPU を使うか CPU で動かすか
(`--cpu` の有無など)も GPU PC 側の起動方法で決まる。Let's Blog 側に切り替えの操作は無い(#1744)。

## 2. 認証が無いので、LAN 内からだけ届くようにする

**ComfyUI には認証が無い。** 到達できる者は、任意のワークフローを実行でき、モデルを読み込ませ、GPU を使い切れる。
そのため次を守る。

- インターネットへ公開しない。ルーターのポート開放や、グローバル IP への待ち受けをしない。
- 同じ LAN 内の Let's Blog のホストからだけ届くようにする。GPU PC のファイアウォールで、8188 番を Let's Blog の
  ホストの IP アドレスからの接続に限って許可する(例: Linux の `ufw allow from 192.168.1.10 to any port 8188`)。
- 認証付きのリバースプロキシ越しの接続は、本機能の対象外である。

## 3. チェックポイント・LoRA を GPU PC に置く

ファイルは GPU PC の ComfyUI のディレクトリに置く。Let's Blog の画面から導入・削除はできない。

| 種類 | 置き場所(ComfyUI のディレクトリ内) |
|---|---|
| チェックポイント | `models/checkpoints/` |
| LoRA | `models/loras/` |

置いたあとに ComfyUI を再起動する(または ComfyUI の画面から再読み込みする)と、Let's Blog の
プロジェクト画面「AI・アセット」→「画像生成」のチェックポイント一覧、および LoRA の一覧に現れる。
使うチェックポイントは、その一覧で「選択」して決める。プロジェクトで何も選んでいないときの既定のファイル名は
`.env` の `COMFYUI_CHECKPOINT`(既定: `v1-5-pruned-emaonly.safetensors`)で、実体は GPU PC 側に置く。

## 4. Let's Blog で接続先の URL を設定する

URL は GPU PC のアドレスとポート。`http://192.168.1.71:8188` のように、LAN のプライベートアドレス
(`192.168.x.x` / `10.x.x.x` / `172.16〜31.x.x`)を指定できる。

- **システム設定**(全プロジェクトの既定): 管理画面の「システム設定」→「画像生成AI連携」の「ComfyUI ベースURL」に入れて保存する。
- **プロジェクト設定**(そのプロジェクトだけ別の GPU PC を使う場合): プロジェクト画面の「設定」タブ →
  ComfyUI の接続情報の「ComfyUIの接続先URL(このプロジェクトで上書き)」に入れて保存する。空にして保存すると上書きを解除し、
  システム設定に戻る。

同じ画面の接続情報(またはダッシュボードの「連携サービスの状況」)で、接続先 URL と利用可否を確認できる。
URL が未設定のまま画像生成を要求すると、「ComfyUIの接続先が設定されていません」と分かるエラーになる。

接続できないときは、Let's Blog のホストから次が通るかを確かめる。

```bash
curl http://192.168.1.71:8188/system_stats
```

## 5. 既存環境の移行(同梱の ComfyUI を使っていた環境)

以前の構成(同梱の `comfyui` / `comfyui-cpu` コンテナ)から更新する場合の手順。**既存のデータは自動では消えない**。

1. **接続先を GPU PC の URL に変える。** 初回起動時に書き込まれていた旧既定値 `http://comfyui:8188` は、既存の DB
   (システム設定 `comfyui_base_url`)にそのまま残る。更新後は `comfyui` というホストが存在しないため、
   画像生成は接続できずに失敗する。「4. Let's Blog で接続先の URL を設定する」の手順で `comfyui_base_url` を GPU PC の URL へ書き換える。
   プロジェクト設定で上書きしている場合は、その値も確認する。
   (新規の環境では `comfyui_base_url` に既定値は書き込まれず、設定するまで未設定のままになる。)
2. **チェックポイントを GPU PC へ移す。** 同梱の ComfyUI で使っていたモデルは、Docker のボリューム `comfyui_models`
   にある。更新の前に、必要なファイルを取り出して GPU PC の `models/checkpoints/`(LoRA は `models/loras/`)へ置く。

   ```bash
   # 更新前(同梱の comfyui コンテナが動いているとき)
   docker cp lbs-comfyui:/root/ComfyUI/models/checkpoints ./comfyui-checkpoints
   # 取り出した ./comfyui-checkpoints の中身を GPU PC の models/checkpoints/ へ scp などでコピーする

   # コンテナが既に無い場合は、ボリュームを一時コンテナで読み出す
   docker run --rm -v lets_blog_server_comfyui_models:/models -v "$PWD":/out busybox \
     cp -r /models/checkpoints /out/comfyui-checkpoints
   ```

   ボリューム名の接頭辞(`lets_blog_server_`)は Compose のプロジェクト名による。`docker volume ls | grep comfyui` で確認する。
3. **不要になったボリュームを手動で削除する。** `comfyui_models` と `comfyui_output`(生成時の出力先)は、
   更新後はどのコンテナも使わない。**自動では削除しない**(モデルの取り戻しを誤って不可能にしないため)。
   2 で必要なファイルを移したことを確かめてから、手動で削除する。

   ```bash
   docker compose down --remove-orphans      # 旧 comfyui コンテナが残っていれば撤去する
   docker volume rm lets_blog_server_comfyui_models lets_blog_server_comfyui_output
   ```

   ボリュームを使うコンテナが残っていると `docker volume rm` は失敗する。`docker ps -a --filter volume=<名前>` で参照している
   コンテナを調べ、先に `docker rm` する。
4. **`.env` から旧設定を消す(任意)。** `COMFYUI_IMAGE` / `COMFYUI_CLI_ARGS` / `COMFYUI_MEM_LIMIT` / `COMFYUI_CPU_MEM_LIMIT` /
   `COMPOSE_PROFILES` の `gpu` / `cpu` は使われなくなった。残っていても害は無いが、`COMPOSE_PROFILES=gpu` は
   存在しないプロファイルなので、他のプロファイル(`diagram` / `db-tools`)と併記しているなら整理する。
   `COMFYUI_CHECKPOINT` は残す(選択の既定のファイル名)。

## 関連

- Ollama(ローカル LLM)の同梱の扱いは本書の対象外(別 Issue #1745)。
- 構成の全体像: [DOCKER_COMPOSE_ARCHITECTURE.md](DOCKER_COMPOSE_ARCHITECTURE.md)
- 初期セットアップ: [setup.md](setup.md)
