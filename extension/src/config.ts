import * as vscode from 'vscode';
import { logger } from './logger';
import { Actor, ActorSchema } from './schemas';
import { messageOf } from './errorHandler';

const API_KEY_SECRET = 'letsBlog.apiKey';
const ACTOR_SECRET = 'letsBlog.actor';
const PROJECT_ID_STATE = 'letsBlog.projectId';

/** Actorの定義元はschemas.ts(APIレスポンスの検証スキーマ)。ここでは型を中継する。 */
export type { Actor };

/** 設定された仲介APIサーバーのベースURL。末尾のスラッシュは除去して返す。 */
export function getServerUrl(): string {
  const url = vscode.workspace.getConfiguration('letsBlog').get<string>('serverUrl');
  return (url ?? 'https://localhost').replace(/\/+$/, '');
}

/** SecretStorageに保管されたAPIキーを取得する。未設定ならundefined。 */
export async function getApiKey(context: vscode.ExtensionContext): Promise<string | undefined> {
  return context.secrets.get(API_KEY_SECRET);
}

/** APIキーをSecretStorageへ保管する(設定ファイルには書かない)。 */
export async function setApiKey(context: vscode.ExtensionContext, value: string): Promise<void> {
  await context.secrets.store(API_KEY_SECRET, value);
}

/** APIキーを取得する。未設定の場合は対応方法を含む例外を投げる。 */
export async function requireApiKey(context: vscode.ExtensionContext): Promise<string> {
  const key = await getApiKey(context);
  if (!key) {
    throw new Error('APIキーが未設定です。「Let\'s Blog: Set API Key」を先に実行してください。');
  }
  return key;
}

/**
 * SecretStorageに保存されたActorを復元する。壊れた値が残っていると以降のログイン状態判定が
 * 常に失敗し続けるため、解析に失敗した場合はログに残した上で保存値を破棄し、再ログインを促す。
 */
export async function getActor(context: vscode.ExtensionContext): Promise<Actor | undefined> {
  const json = await context.secrets.get(ACTOR_SECRET);
  if (!json) return undefined;
  try {
    // 保存値もAPIレスポンスと同じスキーマで検証し、欠落や型不一致を早期に検出する。
    return ActorSchema.parse(JSON.parse(json));
  } catch (error) {
    logger.warn('保存されたログイン情報を読み込めませんでした。再ログインが必要です。', {
      reason: messageOf(error),
    });
    await context.secrets.delete(ACTOR_SECRET);
    void vscode.window.showWarningMessage(
      '保存されたログイン情報を読み込めませんでした。「Let\'s Blog: Login」で再ログインしてください。'
    );
    return undefined;
  }
}

/** ログインユーザーをSecretStorageへ保管する。 */
export async function setActor(context: vscode.ExtensionContext, actor: Actor): Promise<void> {
  await context.secrets.store(ACTOR_SECRET, JSON.stringify(actor));
}

/** 保管されたログインユーザーを削除する(ログアウト相当)。 */
export async function clearActor(context: vscode.ExtensionContext): Promise<void> {
  await context.secrets.delete(ACTOR_SECRET);
}

/** ログインユーザーを取得する。未ログインの場合は対応方法を含む例外を投げる。 */
export async function requireActor(context: vscode.ExtensionContext): Promise<Actor> {
  const actor = await getActor(context);
  if (!actor) {
    throw new Error('ログインしていません。「Let\'s Blog: Login」を先に実行してください。');
  }
  return actor;
}

/** 選択中のプロジェクトID。ワークスペース単位で保持する。 */
export function getProjectId(context: vscode.ExtensionContext): number | undefined {
  return context.workspaceState.get(PROJECT_ID_STATE);
}

/** 選択中のプロジェクトIDを保存する。 */
export async function setProjectId(context: vscode.ExtensionContext, projectId: number): Promise<void> {
  await context.workspaceState.update(PROJECT_ID_STATE, projectId);
}

/** 選択中のプロジェクトIDを取得する。未選択の場合は対応方法を含む例外を投げる。 */
export function requireProjectId(context: vscode.ExtensionContext): number {
  const projectId = getProjectId(context);
  if (projectId == null) {
    throw new Error('プロジェクトが未選択です。「Let\'s Blog: Select Project」を先に実行してください。');
  }
  return projectId;
}
