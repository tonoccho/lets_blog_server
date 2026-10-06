'use strict';
/**
 * ComfyUI のスタブ(issue #1106)。
 *
 * media-service の `ComfyUiClient` は ComfyUI を「キューへ投入して結果をポーリングする」
 * 非同期APIとして扱う。スタブが実装するのは、そのクライアントが実際に叩く経路だけである。
 *
 *   POST /upload/image                        参照画像のアップロード(img2img、issue #1601) → {name, subfolder, type}
 *   POST /prompt                              ワークフロー投入 → prompt_id
 *   GET  /history/{promptId}                  outputs.images(枚数ぶん)
 *   GET  /view?filename=&subfolder=&type=     画像バイト列(PNG)
 *   GET  /object_info/CheckpointLoaderSimple  チェックポイント一覧
 *   GET  /object_info/KSampler                サンプラー一覧・スケジューラー一覧
 *   GET  /object_info/LoraLoader              LoRA一覧
 *   POST /api/interrupt                       VRAM解放(clearMemory)
 *   GET  /system_stats                        platform-service の疎通確認先
 *                                             (ConnectedServiceStatusService)
 *
 * ## なぜ実機の代わりが要るのか
 *
 * 実機の ComfyUI は NVIDIA ランタイムが要り、1枚あたり数十秒かかる。batch size 16 の
 * 枚数検証を実生成で行うと受け入れテストの実行時間が現実的でなくなる。代替に使える
 * OpenAI 画像スタブは `n` を10でクランプし、seed の概念を持たないため、
 * 「batch size 16」も「リピートごとに seed が変わる」(#1101 / #1102)も検証できない。
 *
 * **実機を置き換えるわけではない。** #936(AT-10)の実生成シナリオ(`@slow`)は実機の
 * ComfyUI を使い続ける。このスタブが引き受けるのは**枚数と seed の決定的な検証**だけで、
 * 画像の見た目やモデル固有の挙動は再現しない(#1106 Out of Scope、2026-09-07 の併用方針)。
 *
 * ## 決定性
 *
 * `prompt_id` は投入されたワークフロー(`prompt`)から導く。同じワークフローを何度投げても
 * 同じ `prompt_id` と同じファイル名が返る。実機は投入ごとに新しい UUID を採番するが、
 * それでは `external-stubs.feature` の「同じ入力に常に同じ応答」を満たせない。
 * `client_id` は実クライアントが毎回 UUID を作るので、ハッシュの材料に含めない。
 *
 * ファイル名にワークフローのハッシュを混ぜるのは、別々の投入が同じ名前を返さないように
 * するため(実機は連番なので、この点だけ実機と形が違う)。
 *
 * ## 記録している状態
 *
 * 投入されたワークフローから seed / batch size / チェックポイント等を取り出し、
 * `/__control/state` の `prompts` として読めるようにする。これが
 * 「リピートごとに seed が変わる」ことを受け入れテストから確かめる唯一の手段である
 * (生成された画像だけを見ても、どの seed で作られたかは分からない)。
 */
const crypto = require('node:crypto');
const { createStub } = require('../lib/stub');

/**
 * 1×1 のPNG(固定)。`openai-image` スタブと同じバイト列をそのまま使う。
 * 生成のたびに作らないのは、エンコーダのバージョン差でバイト列が変わり決定性が
 * 崩れるのを避けるため。画像の内容は #1106 の Out of Scope。
 */
const PNG = Buffer.from(
  'iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mNk+M9QDwADhgGAWjR9awAAAABJRU5ErkJggg==',
  'base64'
);

/** `/object_info` が返す一覧。実機の並びを模した固定値。 */
const CHECKPOINTS = ['v1-5-pruned-emaonly.safetensors', 'sd_xl_base_1.0.safetensors'];
const SAMPLERS = ['euler', 'euler_ancestral', 'dpmpp_2m', 'dpmpp_2m_sde', 'ddim'];
const SCHEDULERS = ['normal', 'karras', 'exponential', 'sgm_uniform', 'simple'];
const LORAS = ['e2e-stub-lora.safetensors'];

/**
 * 記録しておく投入の上限。受け入れテストの最大は batch count 16 なので十分に足りる。
 * 上限を置くのは、スタブが長時間動いたときに際限なくメモリを持たないため。
 */
const MAX_PROMPTS = 128;

/**
 * 1回の投入で作る画像の上限。製品側の上限は16(#1102)だが、スタブが製品の検証を
 * 代行してはいけない(上限超過を拒否するのは media-service の責務)。ここでの上限は
 * 誤った巨大な batch_size でスタブが応答不能になるのを防ぐためだけのもので、
 * 製品の上限より十分に大きく取る。
 */
const MAX_BATCH_SIZE = 64;

/** 投入されたワークフローの記録。prompt_id 順(古い順)。 */
let prompts = [];

/**
 * `/upload/image` で受け取った参照画像の記録(issue #1601)。画像の中身は保持しない
 * (ファイル名と、受信したバイト数の目安だけ)。受け入れテストが「参照画像が実際に送られたか」と
 * 「LoadImage が指すファイル名が送られたものと一致するか」を確かめるために使う。
 */
let uploads = [];
/** 受け取った累計。`uploads` は上限で切り詰めるため、増減の比較にはこちらを使う。 */
let uploadCount = 0;

function resetPrompts() {
  prompts = [];
  uploads = [];
  uploadCount = 0;
}

/** multipart の `filename="..."` を取り出す。無ければ null。 */
function uploadedFilename(body) {
  const match = /filename="([^"]+)"/.exec(body || '');
  return match ? match[1] : null;
}

/** キーを再帰的にソートした JSON。オブジェクトのキー順が違うだけで別物にならないようにする。 */
function canonical(value) {
  if (Array.isArray(value)) return `[${value.map(canonical).join(',')}]`;
  if (value && typeof value === 'object') {
    return `{${Object.keys(value)
      .sort()
      .map((key) => `${JSON.stringify(key)}:${canonical(value[key])}`)
      .join(',')}}`;
  }
  return JSON.stringify(value === undefined ? null : value);
}

/** ワークフローから決まる UUID 形式の prompt_id。実機と同じ見た目で、同じ入力には同じ値。 */
function promptIdFor(workflow) {
  const hex = crypto.createHash('sha256').update(canonical(workflow)).digest('hex');
  return [hex.slice(0, 8), hex.slice(8, 12), hex.slice(12, 16), hex.slice(16, 20), hex.slice(20, 32)].join('-');
}

/** class_type でノードを探す。ノード番号はクライアント側の都合なので当てにしない。 */
function nodeOf(workflow, classType) {
  if (!workflow || typeof workflow !== 'object') return null;
  for (const node of Object.values(workflow)) {
    if (node && typeof node === 'object' && node.class_type === classType) return node;
  }
  return null;
}

function inputsOf(workflow, classType) {
  const node = nodeOf(workflow, classType);
  return node && typeof node.inputs === 'object' && node.inputs !== null ? node.inputs : {};
}

/** 投入されたワークフローを、受け入れテストが読める形へ落とす。 */
function describe(workflow, promptId) {
  const latent = inputsOf(workflow, 'EmptyLatentImage');
  const sampler = inputsOf(workflow, 'KSampler');
  const checkpoint = inputsOf(workflow, 'CheckpointLoaderSimple');
  const lora = inputsOf(workflow, 'LoraLoader');
  const loadImage = inputsOf(workflow, 'LoadImage');
  const scaleNode = nodeOf(workflow, 'ImageScale');
  const positive = nodeOf(workflow, 'CLIPTextEncode');

  const repeat = inputsOf(workflow, 'RepeatLatentBatch');
  const requested = Number(latent.batch_size ?? repeat.amount);
  const batchSize = Number.isFinite(requested) && requested > 0
    ? Math.min(MAX_BATCH_SIZE, Math.floor(requested))
    : 1;

  return {
    promptId,
    // seed は数値のまま返す。文字列にすると受け入れテストが型で落ちる。
    seed: sampler.seed === undefined ? null : Number(sampler.seed),
    batchSize,
    width: latent.width === undefined ? null : Number(latent.width),
    height: latent.height === undefined ? null : Number(latent.height),
    steps: sampler.steps === undefined ? null : Number(sampler.steps),
    cfg: sampler.cfg === undefined ? null : Number(sampler.cfg),
    samplerName: sampler.sampler_name ?? null,
    scheduler: sampler.scheduler ?? null,
    checkpoint: checkpoint.ckpt_name ?? null,
    loraName: lora.lora_name ?? null,
    // img2img(issue #1601)。参照画像を読み込むワークフローなら LoadImage が指すファイル名、無ければ null。
    referenceImage: loadImage.image ?? null,
    denoise: sampler.denoise === undefined ? null : Number(sampler.denoise),
    // txt2img は空の潜在画像(EmptyLatentImage)から始まり、img2img は始まらない。
    hasEmptyLatent: nodeOf(workflow, 'EmptyLatentImage') !== null,
    // 参照画像を生成サイズへ合わせるリサイズ(ImageScale)。無ければ null。
    scale: scaleNode && scaleNode.inputs
      ? { width: Number(scaleNode.inputs.width), height: Number(scaleNode.inputs.height) }
      : null,
    prompt: positive && positive.inputs ? (positive.inputs.text ?? null) : null,
  };
}

/** 実機の SaveImage と同じく `{prefix}_{連番}_.png`。ただし投入ごとに衝突しないよう短縮ハッシュを挟む。 */
function fileNames(workflow, promptId, batchSize) {
  const save = inputsOf(workflow, 'SaveImage');
  const prefix = typeof save.filename_prefix === 'string' && save.filename_prefix.length > 0
    ? save.filename_prefix
    : 'letsblog';
  const short = promptId.replace(/-/g, '').slice(0, 8);
  return Array.from(
    { length: batchSize },
    (unused, index) => `${prefix}_${short}_${String(index + 1).padStart(5, '0')}_.png`
  );
}

function record(workflow) {
  const promptId = promptIdFor(workflow);
  const described = describe(workflow, promptId);
  const entry = {
    ...described,
    images: fileNames(workflow, promptId, described.batchSize).map((filename) => ({
      filename,
      subfolder: '',
      type: 'output',
    })),
  };
  prompts.push(entry);
  if (prompts.length > MAX_PROMPTS) prompts = prompts.slice(-MAX_PROMPTS);
  return entry;
}

function findByPromptId(promptId) {
  // 同じワークフローを2回投げると同じ prompt_id になる。最後の投入を返す。
  for (let i = prompts.length - 1; i >= 0; i -= 1) {
    if (prompts[i].promptId === promptId) return prompts[i];
  }
  return null;
}

function historyEntry(entry) {
  return {
    prompt: [0, entry.promptId, {}, {}, []],
    outputs: {
      // 実機と同じく SaveImage ノード(既定のワークフローでは "9")の下に画像が並ぶ。
      9: { images: entry.images },
    },
    status: { status_str: 'success', completed: true, messages: [] },
  };
}

function objectInfo(node) {
  if (node === 'CheckpointLoaderSimple') {
    return {
      CheckpointLoaderSimple: {
        input: { required: { ckpt_name: [CHECKPOINTS] } },
        output: ['MODEL', 'CLIP', 'VAE'],
        name: 'CheckpointLoaderSimple',
      },
    };
  }
  if (node === 'KSampler') {
    return {
      KSampler: {
        input: {
          required: {
            // 実機は max に 2^64-1 を入れるが、JavaScript の Number では正確に表せず
            // 18446744073709552000 と書き出されてしまう。読む側(ComfyUiClient)は
            // sampler_name と scheduler しか見ないので、嘘の値を置くより落とす。
            seed: ['INT', { default: 0, min: 0 }],
            steps: ['INT', { default: 20, min: 1, max: 10000 }],
            cfg: ['FLOAT', { default: 8.0, min: 0.0, max: 100.0 }],
            sampler_name: [SAMPLERS],
            scheduler: [SCHEDULERS],
            denoise: ['FLOAT', { default: 1.0, min: 0.0, max: 1.0 }],
          },
        },
        output: ['LATENT'],
        name: 'KSampler',
      },
    };
  }
  if (node === 'LoraLoader') {
    return {
      LoraLoader: {
        input: { required: { lora_name: [LORAS] } },
        output: ['MODEL', 'CLIP'],
        name: 'LoraLoader',
      },
    };
  }
  return null;
}

createStub({
  name: 'comfyui',
  port: Number(process.env.PORT || 8080),
  // 実機の ComfyUI はエラーを {error: {type, message}, node_errors: {}} で返す。
  errorBody: (status, name) => ({
    error: { type: 'stub_error', message: `[${name}] forced ${status}`, details: '', extra_info: {} },
    node_errors: {},
  }),
  onReset: resetPrompts,
  /** 投入の記録を `/__control/state` から読めるようにする(#1106 Requirements 3)。 */
  extraState: () => ({
    prompts: prompts.map(({ images, ...rest }) => ({ ...rest, images: images.length })),
    uploads,
    uploadCount,
    lastSeed: prompts.length > 0 ? prompts[prompts.length - 1].seed : null,
  }),
  async handle({ method, pathname, query, body, res, sendJson, sendBinary }) {
    // ComfyUI は同じAPIを `/api` 付きでも公開している。クライアントは `/prompt` を使うが、
    // どちらで来ても同じ扱いにする。
    const path = pathname.replace(/^\/api(?=\/)/, '');

    if (method === 'POST' && path === '/upload/image') {
      const name = uploadedFilename(body);
      if (!name) {
        sendJson(res, 400, { error: { type: 'invalid_upload', message: 'image パートがありません' } });
        return true;
      }
      uploadCount += 1;
      uploads.push({ name, bytes: body.length });
      if (uploads.length > MAX_PROMPTS) uploads = uploads.slice(-MAX_PROMPTS);
      sendJson(res, 200, { name, subfolder: '', type: 'input' });
      return true;
    }

    if (method === 'POST' && path === '/prompt') {
      let workflow = null;
      try {
        const parsed = JSON.parse(body || '{}');
        workflow = parsed.prompt ?? null;
      } catch {
        sendJson(res, 400, { error: { type: 'invalid_prompt', message: 'body must be JSON' }, node_errors: {} });
        return true;
      }
      if (!workflow || typeof workflow !== 'object') {
        sendJson(res, 400, {
          error: { type: 'invalid_prompt', message: 'prompt(ワークフロー)がありません' },
          node_errors: {},
        });
        return true;
      }
      const entry = record(workflow);
      // number(キュー番号)は実機では投入順に増えるが、決定性を壊すのでキューは模さない。
      sendJson(res, 200, { prompt_id: entry.promptId, number: 1, node_errors: {} });
      return true;
    }

    if (method === 'GET' && path.startsWith('/history/')) {
      const promptId = decodeURIComponent(path.slice('/history/'.length));
      const entry = findByPromptId(promptId);
      // 実機は未知の prompt_id に対して空のオブジェクトを返す(まだ完了していないのと同じ形)。
      sendJson(res, 200, entry ? { [promptId]: historyEntry(entry) } : {});
      return true;
    }

    if (method === 'GET' && path === '/view') {
      const filename = query.get('filename');
      const known = prompts.some((entry) => entry.images.some((image) => image.filename === filename));
      if (!known) {
        // 知らないファイル名に画像を返すと、クライアントが filename を取り違えていても
        // 受け入れテストが通ってしまう。配線の誤りは 404 で見えるようにする。
        sendJson(res, 404, {
          error: { type: 'not_found', message: `[comfyui] unknown image: ${filename}` },
          node_errors: {},
        });
        return true;
      }
      sendBinary(res, 200, PNG, 'image/png');
      return true;
    }

    if (method === 'GET' && path.startsWith('/object_info/')) {
      const node = decodeURIComponent(path.slice('/object_info/'.length));
      const info = objectInfo(node);
      if (!info) return false;
      sendJson(res, 200, info);
      return true;
    }

    if (method === 'POST' && (path === '/interrupt' || path === '/free')) {
      // 実機は本文なしの 200 を返す。ComfyUiClient は本文を読まない(toBodilessEntity)。
      res.writeHead(200, { 'Content-Type': 'application/json; charset=utf-8' });
      res.end('{}');
      return true;
    }

    if (method === 'GET' && path === '/system_stats') {
      // platform-service の ConnectedServiceStatusService がここで疎通を判定する。
      // 受け入れテスト環境では DB の comfyui_base_url がこのスタブを指すため、実装しないと
      // 「連携サービスの状況」が常に異常になる。
      sendJson(res, 200, {
        system: { os: 'linux', comfyui_version: 'e2e-stub', python_version: 'e2e-stub' },
        devices: [{ name: 'e2e-stub', type: 'cpu', vram_total: 0, vram_free: 0 }],
      });
      return true;
    }

    return false;
  },
});
