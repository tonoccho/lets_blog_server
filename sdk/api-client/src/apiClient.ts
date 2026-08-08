import type { AxiosRequestConfig } from 'axios';

export const apiClient = async <T>(
  config: AxiosRequestConfig,
  options?: any
): Promise<T> => {
  const baseUrl = process.env.REACT_APP_API_URL || process.env.NEXT_PUBLIC_API_URL || 'http://localhost:8080';
  const url = `${baseUrl}${config.url || ''}`;

  const response = await fetch(url, {
    method: config.method || 'GET',
    headers: {
      'Content-Type': 'application/json',
      ...config.headers,
    },
    body: config.data ? JSON.stringify(config.data) : undefined,
  });

  if (!response.ok) {
    throw new Error(`API Error: ${response.status} ${response.statusText}`);
  }

  if (response.status === 204) {
    return undefined as T;
  }

  const data = await response.json();
  return data as T;
};
