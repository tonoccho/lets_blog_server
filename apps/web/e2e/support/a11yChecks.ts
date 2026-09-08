import type { Page } from '@playwright/test';
import { getViolations, injectAxe } from 'axe-playwright';

/**
 * axeの違反のうち critical・serious のものだけを文字列化して返す(issue #944 / AT-18)。
 * moderate/minor は対象外(このIssueのAC「critical・serious相当の違反が0件」に合わせる)。
 */
export async function criticalOrSeriousViolations(page: Page): Promise<string[]> {
  await injectAxe(page);
  const violations = await getViolations(page);
  return violations
    .filter((violation) => violation.impact === 'critical' || violation.impact === 'serious')
    .map((violation) => `${violation.id} (${violation.impact}): ${violation.description} [${violation.nodes.length}件]`);
}

/** 見出し階層(h1〜h6)の妥当性。h1がちょうど1つで、レベルが飛ばないことを確かめる。 */
export async function headingHierarchyViolations(page: Page): Promise<string[]> {
  const levels = await page.evaluate(() =>
    Array.from(document.querySelectorAll('h1, h2, h3, h4, h5, h6')).map((el) =>
      Number(el.tagName.substring(1))
    )
  );
  const violations: string[] = [];
  const h1Count = levels.filter((level) => level === 1).length;
  if (h1Count !== 1) {
    violations.push(`h1が${h1Count}個(1個であるべき)`);
  }
  let previous = 0;
  for (const level of levels) {
    if (previous > 0 && level > previous + 1) {
      violations.push(`見出しレベルがh${previous}からh${level}へ飛んでいる`);
    }
    previous = level;
  }
  return violations;
}

/** すべての `img` 要素にalt属性(空文字列は許容: 装飾目的)またはaria-labelがあるか。 */
export async function imageAltTextViolations(page: Page): Promise<string[]> {
  return page.evaluate(() =>
    Array.from(document.querySelectorAll('img'))
      .filter((img) => img.getAttribute('alt') === null && !img.getAttribute('aria-label'))
      .map((img) => `img[src="${img.getAttribute('src')}"]にalt属性もaria-labelも無い`)
  );
}

/** すべての `a` 要素にテキスト・aria-label・titleのいずれかがあるか。 */
export async function linkTextViolations(page: Page): Promise<string[]> {
  return page.evaluate(() =>
    Array.from(document.querySelectorAll('a')).filter((link) => {
      const text = link.textContent?.trim() ?? '';
      return !text && !link.getAttribute('aria-label') && !link.getAttribute('title');
    }).map((link) => `<a href="${link.getAttribute('href')}">にアクセシブルな名前が無い`)
  );
}

/** フォームの入力要素にラベルまたはaria-label/aria-labelledbyが関連付いているか。 */
export async function formLabelViolations(page: Page): Promise<string[]> {
  return page.evaluate(() => {
    const violations: string[] = [];
    const controls = Array.from(document.querySelectorAll('input, select, textarea')).filter(
      (el) => el.getAttribute('type') !== 'hidden'
    );
    for (const control of controls) {
      const id = control.getAttribute('id');
      const hasLabelFor = id ? document.querySelector(`label[for="${id}"]`) !== null : false;
      const hasWrappingLabel = control.closest('label') !== null;
      const hasAria = control.getAttribute('aria-label') || control.getAttribute('aria-labelledby');
      if (!hasLabelFor && !hasWrappingLabel && !hasAria) {
        violations.push(
          `<${control.tagName.toLowerCase()} name="${control.getAttribute('name') ?? ''}">にラベルが関連付いていない`
        );
      }
    }
    return violations;
  });
}

/** ページ全体に横スクロールが発生していないか(モバイル/タブレット幅の検証用)。 */
export async function horizontalScrollViolation(page: Page): Promise<string | null> {
  const overflow = await page.evaluate(() => {
    const doc = document.documentElement;
    return { scrollWidth: doc.scrollWidth, clientWidth: doc.clientWidth };
  });
  // 1px程度の丸め誤差は許容する。
  if (overflow.scrollWidth > overflow.clientWidth + 1) {
    return `横スクロールが発生している(scrollWidth=${overflow.scrollWidth}, clientWidth=${overflow.clientWidth})`;
  }
  return null;
}
