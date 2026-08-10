import * as fs from 'fs';

/** multipart/form-dataの1パート。ファイルパートはfilenameを持つ。 */
export type MultipartPart =
  | { kind: 'field'; name: string; value: string }
  | { kind: 'file'; name: string; filename: string; filePath: string; contentType?: string };

export interface MultipartBody {
  body: Buffer;
  contentType: string;
}

const DEFAULT_FILE_CONTENT_TYPE = 'application/octet-stream';

/**
 * multipart/form-dataのリクエストボディを組み立てる。
 *
 * form-dataパッケージを置き換えるための最小実装。ストリームではなくBufferを返すため、
 * ネイティブfetchとhttpsモジュールのどちらのトランスポートでも同じボディを使い回せる
 * (リトライ時に読み直せないストリームと違い、Bufferは再送できる)。
 * 記事に同梱する画像は数枚・数MB規模のため、メモリ上に展開して問題ない。
 */
export function buildMultipartBody(parts: MultipartPart[]): MultipartBody {
  const boundary = `----LetsBlogFormBoundary${randomToken()}`;
  const chunks: Buffer[] = [];

  for (const part of parts) {
    chunks.push(Buffer.from(`--${boundary}\r\n`, 'utf-8'));
    if (part.kind === 'field') {
      chunks.push(
        Buffer.from(
          `Content-Disposition: form-data; name="${escapeHeaderValue(part.name)}"\r\n\r\n`,
          'utf-8'
        )
      );
      chunks.push(Buffer.from(part.value, 'utf-8'));
    } else {
      chunks.push(
        Buffer.from(
          `Content-Disposition: form-data; name="${escapeHeaderValue(part.name)}";` +
            ` filename="${escapeHeaderValue(part.filename)}"\r\n` +
            `Content-Type: ${part.contentType ?? DEFAULT_FILE_CONTENT_TYPE}\r\n\r\n`,
          'utf-8'
        )
      );
      chunks.push(fs.readFileSync(part.filePath));
    }
    chunks.push(Buffer.from('\r\n', 'utf-8'));
  }

  chunks.push(Buffer.from(`--${boundary}--\r\n`, 'utf-8'));

  return {
    body: Buffer.concat(chunks),
    contentType: `multipart/form-data; boundary=${boundary}`,
  };
}

/**
 * ヘッダ値に含まれる引用符・改行を除去する。ファイル名は利用者が付けた任意の文字列であり、
 * そのまま埋め込むとヘッダを壊す(あるいは意図しないパートを注入できてしまう)ため。
 */
function escapeHeaderValue(value: string): string {
  return value.replace(/[\r\n]/g, '').replace(/"/g, '%22');
}

function randomToken(): string {
  return Math.random().toString(36).slice(2) + Date.now().toString(36);
}
