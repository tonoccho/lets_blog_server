/**
 * TTL付きLRUキャッシュ。
 *
 * 画像生成オプション・カテゴリ一覧・サイト一覧といった「同一セッション中はほぼ変わらないが、
 * パネルを開くたびに取得していた」データの再取得を抑えるために使う。
 * 変更されうるデータを長時間握り続けないようTTLを持たせ、上限件数を超えたら
 * 最も長く参照されていない項目から捨てる。
 */

interface CacheEntry<V> {
  value: V;
  /** この時刻(epoch ms)を過ぎたら無効。 */
  expiresAt: number;
}

export interface LruCacheOptions {
  /** 保持する最大件数。超過すると最も長く参照されていない項目を捨てる。 */
  maxEntries?: number;
  /** 有効期間(ミリ秒)。 */
  ttlMs?: number;
  /** 現在時刻を返す関数。テストから差し替える。 */
  now?: () => number;
}

const DEFAULT_MAX_ENTRIES = 50;
const DEFAULT_TTL_MS = 5 * 60 * 1000;

export class LruCache<V> {
  private readonly _entries = new Map<string, CacheEntry<V>>();
  private readonly _maxEntries: number;
  private readonly _ttlMs: number;
  private readonly _now: () => number;

  constructor(options: LruCacheOptions = {}) {
    this._maxEntries = options.maxEntries ?? DEFAULT_MAX_ENTRIES;
    this._ttlMs = options.ttlMs ?? DEFAULT_TTL_MS;
    this._now = options.now ?? Date.now;
  }

  /** 有効な値があれば返す。無い、または期限切れならundefined。 */
  public get(key: string): V | undefined {
    const entry = this._entries.get(key);
    if (!entry) {
      return undefined;
    }
    if (entry.expiresAt <= this._now()) {
      this._entries.delete(key);
      return undefined;
    }
    // Mapは挿入順を保つため、削除して入れ直すことで「最近使った」位置へ移す。
    this._entries.delete(key);
    this._entries.set(key, entry);
    return entry.value;
  }

  public set(key: string, value: V): void {
    // 既存キーの更新でも最近使った位置へ移すため、一度削除する。
    this._entries.delete(key);
    this._entries.set(key, { value, expiresAt: this._now() + this._ttlMs });

    while (this._entries.size > this._maxEntries) {
      const oldestKey = this._entries.keys().next().value;
      if (oldestKey === undefined) break;
      this._entries.delete(oldestKey);
    }
  }

  /**
   * キャッシュがあればそれを返し、無ければloaderを実行して結果を保存する。
   *
   * 同じキーへの取得が並行して走った場合は、後続の呼び出しが先行のPromiseを共有する
   * (パネルを開いた直後に複数の描画処理が同じ一覧を要求しても、API呼び出しは1回で済む)。
   */
  public async getOrLoad(key: string, loader: () => Promise<V>): Promise<V> {
    const cached = this.get(key);
    if (cached !== undefined) {
      return cached;
    }

    const inFlight = this._inFlight.get(key);
    if (inFlight) {
      return inFlight;
    }

    const promise = loader()
      .then((value) => {
        this.set(key, value);
        return value;
      })
      .finally(() => {
        this._inFlight.delete(key);
      });
    this._inFlight.set(key, promise);
    return promise;
  }

  /** 特定のキーを無効化する。前方一致の接頭辞を渡すとまとめて消せる。 */
  public invalidate(keyOrPrefix: string): void {
    for (const key of [...this._entries.keys()]) {
      if (key === keyOrPrefix || key.startsWith(`${keyOrPrefix}:`)) {
        this._entries.delete(key);
      }
    }
  }

  public clear(): void {
    this._entries.clear();
    this._inFlight.clear();
  }

  public get size(): number {
    return this._entries.size;
  }

  private readonly _inFlight = new Map<string, Promise<V>>();
}
