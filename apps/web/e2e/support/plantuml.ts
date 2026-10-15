import { deflateRawSync } from 'node:zlib';

/**
 * PlantUML 独自の符号化(raw deflate + 専用の64文字)。services/media の PlantUmlEncoder.java と同じ。
 * 受け入れテストが `/png/<encoded>` を直接叩くとき(PlantUML スタブ、#1802)に使う。
 */
const ALPHABET = '0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz-_';

export function encodePlantUml(source: string): string {
  const data = deflateRawSync(Buffer.from(source, 'utf8'), { level: 9 });
  let out = '';
  for (let i = 0; i < data.length; i += 3) {
    const b1 = data[i];
    const b2 = i + 1 < data.length ? data[i + 1] : 0;
    const b3 = i + 2 < data.length ? data[i + 2] : 0;
    out += ALPHABET[b1 >> 2]
      + ALPHABET[((b1 & 3) << 4) | (b2 >> 4)]
      + ALPHABET[((b2 & 0xf) << 2) | (b3 >> 6)]
      + ALPHABET[b3 & 0x3f];
  }
  return out;
}
