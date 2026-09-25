import * as api from './apiClient';

/**
 * AI生成結果末尾に付加する出典セクション。出典があるかのように装わないよう、
 * 検索失敗/未設定/0件時はsearchNoteでその旨を明示する。
 * commandAskAi(draft/proofread/summarize)とAskAiPanel(issue #526)の両方から共用する。
 */
export function buildSourcesSection(sources: api.SourceReference[], searchNote: string | null): string {
  if (sources.length === 0) {
    return searchNote ? `\n\n---\n*${searchNote}*\n` : '';
  }
  const list = sources.map((s) => `- [${s.title}](${s.url})`).join('\n');
  return `\n\n---\n**出典:**\n${list}\n`;
}
