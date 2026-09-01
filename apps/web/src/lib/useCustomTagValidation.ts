'use client';

import { useState } from 'react';
import { validateCustomTagAction } from '@/app/custom-tags/actions';
import type { ValidationResult } from './apiClient';

export interface UseCustomTagValidationState {
  isLoading: boolean;
  error: string | null;
  result: ValidationResult | null;
}

export function useCustomTagValidation() {
  const [state, setState] = useState<UseCustomTagValidationState>({
    isLoading: false,
    error: null,
    result: null,
  });

  const validate = async (htmlTemplate: string, cssContent: string) => {
    setState({ isLoading: true, error: null, result: null });
    try {
      const { data, error } = await validateCustomTagAction({ htmlTemplate, cssContent });
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
    validate,
    reset,
  };
}
