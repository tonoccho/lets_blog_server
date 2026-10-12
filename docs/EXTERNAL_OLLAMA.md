# GPU PC の Ollama へ Let's Blog をつなぐ(#1745)

Let's Blog のスタックは Ollama を含まない。Ollama は GPU 付きの別 PC で動かし、Let's Blog の管理画面に
その URL を設定して使う。Let's Blog が行う LLM 呼び出しは OpenAI 互換の `POST {URL}/chat/completions` である。

```text
Let's Blog ホスト (ai-service) --- LAN --->  GPU PC (Ollama :11434)
        POST http://192.168.1.71:11434/v1/chat/completions
```

## 1. GPU PC で Ollama を用意する

1. [ollama.com](https://ollama.com/download) の手順で Ollama を導入する(NVIDIA ドライバを先に入れておく)。
2. LAN からの接続を受けるよう、`OLLAMA_HOST=0.0.0.0:11434` で待ち受ける。Linux(systemd)の例:

   ```bash
   sudo systemctl edit ollama
   ```

   ```ini
   [Service]
   Environment="OLLAMA_HOST=0.0.0.0:11434"
   Environment="OLLAMA_KEEP_ALIVE=5m"
   Environment="OLLAMA_CONTEXT_LENGTH=8192"
   Environment="OLLAMA_MAX_LOADED_MODELS=1"
   ```

   ```bash
   sudo systemctl daemon-reload && sudo systemctl restart ollama
   ```

   Windows / macOS では、同名の環境変数をシステムの環境変数(macOS は `launchctl setenv`)に設定して Ollama を再起動する。
3. 推奨する設定(Let's Blog が同梱 Ollama を動かしていたときの値)。

   | 変数 | 推奨値 | 意味 |
   | --- | --- | --- |
   | `OLLAMA_KEEP_ALIVE=5m` | 5 分 | 生成後にモデルを VRAM へ残す時間。同じ GPU で ComfyUI も動かす場合、VRAM 不足が出るなら `30s` へ下げる |
   | `OLLAMA_CONTEXT_LENGTH=8192` | 8192 | コンテキスト長。Ollama の既定は小さく、超過分は黙って切り捨てられる。伸ばすほど KV キャッシュが VRAM を食う |
   | `OLLAMA_MAX_LOADED_MODELS=1` | 1 | 同時にロードするモデル数。VRAM の取り合いを避ける |

4. 使うモデルを **GPU PC 上で** 取得する。Let's Blog は既定で `qwen2.5:7b-instruct`(約 4.7GB)を使う。

   ```bash
   ollama pull qwen2.5:7b-instruct
   ollama list
   ```

   別のモデルを使うときは、そのモデルを同じく `ollama pull` し、管理画面の `LLM_OLLAMA_MODEL`(モデル名)を合わせる。

## 2. LAN 内からだけ到達できるようにする

**Ollama には認証が無い。** `0.0.0.0` で待ち受けると、到達できる誰もが生成を実行しモデルを削除できる。
GPU PC のファイアウォールで、11434 番ポートを Let's Blog ホストのアドレス(または LAN のアドレス範囲)からだけ許可する。
インターネットへ公開してはならない(ルータのポート転送をしない)。ufw の例:

```bash
sudo ufw allow from 192.168.1.0/24 to any port 11434 proto tcp
sudo ufw deny 11434/tcp
```

Let's Blog のクライアントは Ollama に認証情報を送らない。認証付きのリバースプロキシ越しの接続は対象外である。

## 3. Let's Blog に URL を設定する

管理者が次のどちらかに、`/v1` までを含めた URL を入れる。

- システム設定(管理画面 → システム設定)の「Ollama ベースURL」(`llm_ollama_base_url`):
  `http://192.168.1.71:11434/v1`
- プロジェクトごと(プロジェクト設定 → AI・アセット)の Ollama 接続先。設定すると、そのプロジェクトはこちらを使う。
  保存した URL は接続時に検査され、docker ネットワーク内のアドレスと loopback などは拒否される。LAN のプライベートアドレスは許可される。

初回起動で既定値は書き込まれない。接続先が未設定のまま AI 執筆支援を使うと
「Ollamaの接続先が設定されていません。…」というエラーになる。

## 4. 既存環境の移行(同梱 Ollama を使っていた場合)

1. 上の手順で GPU PC の Ollama を用意する。
2. 管理画面のシステム設定で `llm_ollama_base_url` を、`http://ollama:11434/v1` から GPU PC の URL へ変更する
   (既存 DB の値は自動では書き換えない)。プロジェクト単位の上書きに `http://ollama:11434/v1` を入れていたものも直す。
3. `.env` の `OLLAMA_*` と `GPU_RUNTIME` は不要になった。消してよい(残していても無視される)。
4. `docker compose up -d --remove-orphans` で、残っている `lbs-ollama` / `lbs-ollama-cpu` / `lbs-ollama-model-init`
   コンテナを片付ける。
5. 同梱 Ollama が使っていたモデル用ボリューム `ollama_models` は **自動では削除されない**。不要になったら手で消す
   (数 GB〜数十 GB を占める)。

   ```bash
   docker volume ls | grep ollama_models
   docker volume rm <上で表示されたボリューム名>   # 例: lets_blog_server_ollama_models
   ```

   消すと同梱 Ollama に取得済みのモデルは失われる。GPU PC で改めて `ollama pull` してあることを先に確かめる。

## 5. 動作確認

GPU PC で、Let's Blog ホストから到達できるかを確かめる。

```bash
curl -s http://192.168.1.71:11434/v1/models
```

管理画面で AI 執筆支援(下書き・校正など)を実行して応答が返れば完了である。接続できないときは、GPU PC の
`OLLAMA_HOST`、ファイアウォール、Let's Blog ホストからの経路(同じ LAN か)を順に確認する。
