import { readFileSync, readdirSync, statSync } from 'node:fs';
import path from 'node:path';

/**
 * 翻訳キーの網羅性チェック(issue #944 / AT-18、issue #718の退行検知)。
 *
 * ## なぜ「画面の文字列を生キーっぽい形式で走査する」方式を採らないか
 *
 * `apps/web/src/app/I18nProvider.tsx` の `t()` はキー欠落時に
 * `namespace.key` のような合成文字列ではなく**キー名だけ**を返す
 * (`(namespaceMessages as Record<string, string>)[key] || key`)。
 * issue #718 で実際に画面に出ていたのも `"admin"` というキー名そのもので、
 * `"header.admin"` のような見るからに不自然な文字列ではなかった。
 * したがって、画面のDOMテキストから「生キーらしきパターン」を正規表現で
 * 拾おうとしても、#718 が再現する形の欠落は検出できない
 * (`admin` は他の場所で正当な英単語として出てくることもあり、区別が付かない)。
 *
 * 代わりに、`t(namespace, key)` の**呼び出し箇所そのもの**をソースから静的に抽出し、
 * 各ロケールの `messages/*.json` に対応するキーが実在するかを機械的に照合する。
 * これは #718 が起きた経路(呼び出し箇所はあるが、片方のロケールJSONにキーが無い)を
 * 直接検出できる。呼び出し箇所は `apps/web/src/**` 全体から集めるため、
 * どの画面がその呼び出しを使っていても(ヘッダー/ナビは全認証済み画面に共通して
 * マウントされる)網羅する。
 */

const SRC_ROOT = path.resolve(__dirname, '..', '..', 'src');
const MESSAGES_DIR = path.resolve(__dirname, '..', '..', 'messages');

export interface TranslationCallSite {
  namespace: string;
  key: string;
  file: string;
}

/** `t("namespace", "key")` の形の呼び出しを1ファイルから抽出する(文字列リテラル限定)。 */
function extractCallSitesFromSource(file: string, source: string): TranslationCallSite[] {
  const sites: TranslationCallSite[] = [];
  const pattern = /\bt\(\s*["'`]([\w.-]+)["'`]\s*,\s*["'`]([\w.-]+)["'`]\s*\)/g;
  let match: RegExpExecArray | null;
  while ((match = pattern.exec(source)) !== null) {
    sites.push({ namespace: match[1], key: match[2], file });
  }
  return sites;
}

function walk(dir: string, out: string[]): void {
  for (const entry of readdirSync(dir)) {
    if (entry === 'node_modules' || entry === '__tests__') {
      continue;
    }
    const full = path.join(dir, entry);
    const stat = statSync(full);
    if (stat.isDirectory()) {
      walk(full, out);
    } else if (/\.(ts|tsx)$/.test(entry) && !entry.endsWith('.test.ts') && !entry.endsWith('.test.tsx')) {
      out.push(full);
    }
  }
}

/** `apps/web/src` 全体を走査し、`t(namespace, key)` の呼び出し箇所を集める。 */
export function scanTranslationCallSites(): TranslationCallSite[] {
  const files: string[] = [];
  walk(SRC_ROOT, files);
  const sites: TranslationCallSite[] = [];
  for (const file of files) {
    sites.push(...extractCallSitesFromSource(path.relative(SRC_ROOT, file), readFileSync(file, 'utf-8')));
  }
  return sites;
}

export type LocaleMessages = Record<string, Record<string, string>>;

export function loadLocaleMessages(): Record<string, LocaleMessages> {
  const result: Record<string, LocaleMessages> = {};
  for (const entry of readdirSync(MESSAGES_DIR)) {
    if (!entry.endsWith('.json')) {
      continue;
    }
    const locale = entry.replace(/\.json$/, '');
    result[locale] = JSON.parse(readFileSync(path.join(MESSAGES_DIR, entry), 'utf-8')) as LocaleMessages;
  }
  return result;
}

export interface MissingTranslation {
  locale: string;
  namespace: string;
  key: string;
  usedIn: string[];
}

/**
 * 呼び出し箇所の集合を、各ロケールのメッセージに対して照合する。
 *
 * 欠落しているキー(そのロケールの該当namespaceに存在しない、または値が空文字)を
 * ロケールごとにまとめて返す。1つの `namespace.key` を複数ファイルが呼んでいる場合は
 * `usedIn` にまとめる(#718は `HeaderNav.tsx` 単独の呼び出しだったが、将来複数箇所から
 * 呼ばれた場合でも重複報告にならないようにする)。
 */
export function findMissingTranslations(
  callSites: TranslationCallSite[],
  messagesByLocale: Record<string, LocaleMessages>
): MissingTranslation[] {
  const missing: MissingTranslation[] = [];
  const seen = new Map<string, MissingTranslation>();

  for (const locale of Object.keys(messagesByLocale)) {
    const messages = messagesByLocale[locale];
    for (const site of callSites) {
      const namespaceMessages = messages[site.namespace];
      const value = namespaceMessages ? namespaceMessages[site.key] : undefined;
      if (value === undefined || value === '') {
        const dedupeKey = `${locale}:${site.namespace}.${site.key}`;
        const existing = seen.get(dedupeKey);
        if (existing) {
          if (!existing.usedIn.includes(site.file)) {
            existing.usedIn.push(site.file);
          }
        } else {
          const entry: MissingTranslation = {
            locale,
            namespace: site.namespace,
            key: site.key,
            usedIn: [site.file],
          };
          seen.set(dedupeKey, entry);
          missing.push(entry);
        }
      }
    }
  }
  return missing;
}

/** ロケールJSONを深く複製し、指定した `namespace.key` を除去する(#718の再現専用)。 */
export function withoutKey(
  messagesByLocale: Record<string, LocaleMessages>,
  locale: string,
  namespace: string,
  key: string
): Record<string, LocaleMessages> {
  const clone = JSON.parse(JSON.stringify(messagesByLocale)) as Record<string, LocaleMessages>;
  delete clone[locale][namespace][key];
  return clone;
}

/**
 * 全ロケールが同一のキー集合を持つかを検証する(issue #944 シナリオ12)。
 * 戻り値は「他のロケールには存在するのに、このロケールには無いキー」のロケールごとの一覧
 * (過不足が無ければ空配列)。
 */
export function compareKeySets(
  messagesByLocale: Record<string, LocaleMessages>
): { locale: string; missingFromThisLocale: string[] }[] {
  const flatten = (messages: LocaleMessages): Set<string> => {
    const keys = new Set<string>();
    for (const namespace of Object.keys(messages)) {
      for (const key of Object.keys(messages[namespace])) {
        keys.add(`${namespace}.${key}`);
      }
    }
    return keys;
  };

  const locales = Object.keys(messagesByLocale);
  const flatByLocale = new Map(locales.map((locale) => [locale, flatten(messagesByLocale[locale])]));
  const union = new Set<string>();
  for (const set of flatByLocale.values()) {
    for (const key of set) {
      union.add(key);
    }
  }

  return locales
    .map((locale) => {
      const own = flatByLocale.get(locale) as Set<string>;
      const missingFromThisLocale = [...union].filter((key) => !own.has(key));
      return { locale, missingFromThisLocale };
    })
    .filter((entry) => entry.missingFromThisLocale.length > 0);
}
