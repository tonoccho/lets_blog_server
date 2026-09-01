import { LruCache } from '../cache';

/** 現在時刻を手動で進められる時計。TTLの検証に使う。 */
function fakeClock(start = 1_000_000): { now: () => number; advance: (ms: number) => void } {
  let current = start;
  return { now: () => current, advance: (ms: number) => (current += ms) };
}

describe('LruCache', () => {
  it('保存した値を取り出せる', () => {
    const cache = new LruCache<string>();
    cache.set('a', 'A');
    expect(cache.get('a')).toBe('A');
  });

  it('未登録のキーはundefinedを返す', () => {
    expect(new LruCache<string>().get('missing')).toBeUndefined();
  });

  it('TTLを過ぎた値は無効になる', () => {
    const clock = fakeClock();
    const cache = new LruCache<string>({ ttlMs: 1000, now: clock.now });
    cache.set('a', 'A');

    clock.advance(999);
    expect(cache.get('a')).toBe('A');

    clock.advance(1);
    expect(cache.get('a')).toBeUndefined();
    expect(cache.size).toBe(0);
  });

  it('上限を超えると最も長く参照されていない項目から捨てる', () => {
    const cache = new LruCache<string>({ maxEntries: 2 });
    cache.set('a', 'A');
    cache.set('b', 'B');
    cache.get('a'); // aを最近使ったことにする
    cache.set('c', 'C'); // 最古はb

    expect(cache.get('a')).toBe('A');
    expect(cache.get('b')).toBeUndefined();
    expect(cache.get('c')).toBe('C');
  });

  it('同じキーへの再設定でも最近使った位置へ移す', () => {
    const cache = new LruCache<string>({ maxEntries: 2 });
    cache.set('a', 'A');
    cache.set('b', 'B');
    cache.set('a', 'A2');
    cache.set('c', 'C');

    expect(cache.get('a')).toBe('A2');
    expect(cache.get('b')).toBeUndefined();
  });

  describe('getOrLoad', () => {
    it('初回はloaderを呼び、2回目以降はキャッシュを返す', async () => {
      const cache = new LruCache<string>();
      const loader = jest.fn().mockResolvedValue('値');

      await expect(cache.getOrLoad('k', loader)).resolves.toBe('値');
      await expect(cache.getOrLoad('k', loader)).resolves.toBe('値');
      expect(loader).toHaveBeenCalledTimes(1);
    });

    it('並行呼び出しでもloaderは1回しか走らない', async () => {
      const cache = new LruCache<string>();
      let resolveLoader: (value: string) => void = () => undefined;
      const loader = jest.fn().mockReturnValue(
        new Promise<string>((resolve) => {
          resolveLoader = resolve;
        })
      );

      const first = cache.getOrLoad('k', loader);
      const second = cache.getOrLoad('k', loader);
      resolveLoader('値');

      await expect(Promise.all([first, second])).resolves.toEqual(['値', '値']);
      expect(loader).toHaveBeenCalledTimes(1);
    });

    it('loaderが失敗した場合は結果を保存せず、次回は再実行する', async () => {
      const cache = new LruCache<string>();
      const loader = jest.fn().mockRejectedValueOnce(new Error('失敗')).mockResolvedValueOnce('値');

      await expect(cache.getOrLoad('k', loader)).rejects.toThrow('失敗');
      await expect(cache.getOrLoad('k', loader)).resolves.toBe('値');
      expect(loader).toHaveBeenCalledTimes(2);
    });

    it('TTL経過後はloaderを再実行する', async () => {
      const clock = fakeClock();
      const cache = new LruCache<string>({ ttlMs: 1000, now: clock.now });
      const loader = jest.fn().mockResolvedValue('値');

      await cache.getOrLoad('k', loader);
      clock.advance(1001);
      await cache.getOrLoad('k', loader);

      expect(loader).toHaveBeenCalledTimes(2);
    });
  });

  describe('invalidate', () => {
    it('キー完全一致で無効化する', () => {
      const cache = new LruCache<string>();
      cache.set('project:1:issues', 'X');
      cache.invalidate('project:1:issues');
      expect(cache.get('project:1:issues')).toBeUndefined();
    });

    it('接頭辞でまとめて無効化する', () => {
      const cache = new LruCache<string>();
      cache.set('project:1:issues', 'X');
      cache.set('project:1:categories', 'Y');
      cache.set('project:2:issues', 'Z');

      cache.invalidate('project:1');

      expect(cache.get('project:1:issues')).toBeUndefined();
      expect(cache.get('project:1:categories')).toBeUndefined();
      // 別プロジェクトのキャッシュは巻き添えにしない。
      expect(cache.get('project:2:issues')).toBe('Z');
    });

    it('接頭辞が部分一致するだけの別キーは消さない', () => {
      const cache = new LruCache<string>();
      cache.set('project:12:issues', 'X');
      cache.invalidate('project:1');
      expect(cache.get('project:12:issues')).toBe('X');
    });
  });

  it('clearですべて破棄する', () => {
    const cache = new LruCache<string>();
    cache.set('a', 'A');
    cache.set('b', 'B');
    cache.clear();
    expect(cache.size).toBe(0);
  });
});
