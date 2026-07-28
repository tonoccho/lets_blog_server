import * as vscode from 'vscode';

const API_KEY_SECRET = 'letsBlog.apiKey';

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
