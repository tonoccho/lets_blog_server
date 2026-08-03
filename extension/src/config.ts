import * as vscode from 'vscode';

const API_KEY_SECRET = 'letsBlog.apiKey';
const ACTOR_SECRET = 'letsBlog.actor';
const PROJECT_ID_STATE = 'letsBlog.projectId';

export interface Actor {
  id: number;
  email: string;
  role: string;
}

export function getServerUrl(): string {
  const url = vscode.workspace.getConfiguration('letsBlog').get<string>('serverUrl');
  return (url ?? 'https://localhost').replace(/\/+$/, '');
}

export async function getApiKey(context: vscode.ExtensionContext): Promise<string | undefined> {
  return context.secrets.get(API_KEY_SECRET);
}

export async function setApiKey(context: vscode.ExtensionContext, value: string): Promise<void> {
  await context.secrets.store(API_KEY_SECRET, value);
}

export async function requireApiKey(context: vscode.ExtensionContext): Promise<string> {
  const key = await getApiKey(context);
  if (!key) {
    throw new Error('APIキーが未設定です。「Let\'s Blog: Set API Key」を先に実行してください。');
  }
  return key;
}

export async function getActor(context: vscode.ExtensionContext): Promise<Actor | undefined> {
  const json = await context.secrets.get(ACTOR_SECRET);
  if (!json) return undefined;
  try {
    return JSON.parse(json) as Actor;
  } catch {
    return undefined;
  }
}

export async function setActor(context: vscode.ExtensionContext, actor: Actor): Promise<void> {
  await context.secrets.store(ACTOR_SECRET, JSON.stringify(actor));
}

export async function clearActor(context: vscode.ExtensionContext): Promise<void> {
  await context.secrets.delete(ACTOR_SECRET);
}

export async function requireActor(context: vscode.ExtensionContext): Promise<Actor> {
  const actor = await getActor(context);
  if (!actor) {
    throw new Error('ユーザーが未選択です。「Let\'s Blog: Select User」を先に実行してください。');
  }
  return actor;
}

export function getProjectId(context: vscode.ExtensionContext): number | undefined {
  return context.workspaceState.get(PROJECT_ID_STATE);
}

export async function setProjectId(context: vscode.ExtensionContext, projectId: number): Promise<void> {
  await context.workspaceState.update(PROJECT_ID_STATE, projectId);
}

export function requireProjectId(context: vscode.ExtensionContext): number {
  const projectId = getProjectId(context);
  if (projectId == null) {
    throw new Error('プロジェクトが未選択です。「Let\'s Blog: Select Project」を先に実行してください。');
  }
  return projectId;
}
