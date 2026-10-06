'use client';

import { useState } from 'react';
import type { GenerateCustomTagInput } from './apiClient';
import { generateCustomTagAction } from '@/app/custom-tags/actions';

export interface UseCustomTagGenerationState {
  isLoading: boolean;
  error: string | null;
  /** 受理された生成ジョブのID(処理キューに追加された)。要求していない・拒否されたときは null。 */
  queuedJobId: number | null;
}

/**
 * カスタムタグのAI生成を非同期ジョブとして要求する(issue #1409)。持つのは「要求中か」「受理されたジョブID」
 * 「拒否の理由」で、生成結果ではない(結果はジョブの結果にだけあり、処理キューの「結果を見る」から画面が読む)。
 */
export function useCustomTagGeneration() {
  const [state, setState] = useState<UseCustomTagGenerationState>({
    isLoading: false,
    error: null,
    queuedJobId: null,
  });

  /** 受理されたらジョブIDを返す。拒否・失敗なら undefined(理由は `error`)。 */
  const generate = async (input: GenerateCustomTagInput): Promise<number | undefined> => {
    setState({ isLoading: true, error: null, queuedJobId: null });
    try {
      const { jobId, status, error } = await generateCustomTagAction(input);
      if (error !== undefined || jobId === undefined) {
        setState({ isLoading: false, error: error ?? '不明なエラーが発生しました', queuedJobId: null });
        return undefined;
      }
      if (status === 'failed') {
        // 実行枠と待ち行列が満杯のとき、ジョブは作られた上で failed として返る。
        setState({
          isLoading: false,
          error: 'カスタムタグ生成の待ち行列が満杯です。しばらくしてからもう一度要求してください。',
          queuedJobId: null,
        });
        return undefined;
      }
      setState({ isLoading: false, error: null, queuedJobId: jobId });
      return jobId;
    } catch (error) {
      const errorMessage = error instanceof Error ? error.message : '不明なエラーが発生しました';
      setState({ isLoading: false, error: errorMessage, queuedJobId: null });
      return undefined;
    }
  };

  const reset = () => {
    setState({ isLoading: false, error: null, queuedJobId: null });
  };

  return {
    ...state,
    generate,
    reset,
  };
}
