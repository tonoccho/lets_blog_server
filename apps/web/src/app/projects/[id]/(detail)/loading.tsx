import { RouteLoading } from "@/components/RouteLoading";

/**
 * プロジェクト詳細画面(`page.tsx`)のloading UI(issue #1475)。
 *
 * この画面は初期表示で `GET /api/projects/{id}/bulk-management/categories/comparison`
 * (gateway アクセスログの実測で 11,012ms)を待つ。loading UI が無いと、その間ずっと
 * HTML が1バイトも返らず、クリックしても画面が変わらない。
 *
 * `(detail)` ルートグループに置くのは、この loading UI を `page.tsx` だけに効かせるため。
 * `[id]` 直下に置くと、実測の裏付けが無い `plan` / `posts` / `tags` などの配下の画面にも
 * かかり、速い画面に一瞬のちらつきを増やしてしまう。URL は変わらない(`/projects/{id}`)。
 * 取得失敗時は従来どおり `notFound()` / `app/projects/error.tsx` が描く(loading UI のまま止まらない)。
 */
export default function ProjectDetailLoading() {
  return <RouteLoading label="プロジェクトを読み込み中…" />;
}
