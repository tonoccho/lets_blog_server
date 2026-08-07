'use client';

import { useState } from 'react';
import type { GenerateCustomTagInput, CustomTag } from './apiClient';
import { generateCustomTagAction } from '@/app/custom-tags/actions';

export interface UseCustomTagGenerationState {
  isLoading: boolean;
  error: string | null;
  result: CustomTag | null;
}

export function useCustomTagGeneration() {
  const [state, setState] = useState<UseCustomTagGenerationState>({
    isLoading: false,
    error: null,
    result: null,
  });

  const generate = async (input: GenerateCustomTagInput) => {
    setState({ isLoading: true, error: null, result: null });
    try {
      const { data, error } = await generateCustomTagAction(input);
      if (error) {
        setState({ isLoading: false, error, result: null });
        throw new Error(error);
      }
      setState({ isLoading: false, error: null, result: data || null });
      return data;
    } catch (error) {
      const errorMessage = error instanceof Error ? error.message : '不明なエラーが発生しました';
      setState({ isLoading: false, error: errorMessage, result: null });
      throw error;
    }
  };

  const reset = () => {
    setState({ isLoading: false, error: null, result: null });
  };

  return {
    ...state,
    generate,
    reset,
  };
}
