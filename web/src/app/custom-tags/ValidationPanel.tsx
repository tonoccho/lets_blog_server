"use client";

import type { ValidationResult } from "@/lib/apiClient";

interface ValidationPanelProps {
  result: ValidationResult | null;
  isLoading: boolean;
  error: string | null;
}

export function ValidationPanel({ result, isLoading, error }: ValidationPanelProps) {
  if (isLoading) {
    return (
      <div className="rounded-lg border border-neutral-300 bg-neutral-50 p-4">
        <p className="text-sm text-neutral-600">検証中...</p>
      </div>
    );
  }

  if (error) {
    return (
      <div className="rounded-lg border border-red-300 bg-red-50 p-4">
        <p className="text-sm font-medium text-red-800">検証エラー</p>
        <p className="mt-1 text-sm text-red-700">{error}</p>
      </div>
    );
  }

  if (!result) {
    return null;
  }

  const hasErrors = result.errors.length > 0;
  const hasWarnings = result.warnings.length > 0;

  if (!hasErrors && !hasWarnings) {
    return (
      <div className="rounded-lg border border-green-300 bg-green-50 p-4">
        <p className="text-sm font-medium text-green-800">✓ 検証成功</p>
        <p className="mt-1 text-sm text-green-700">HTMLとCSSは有効です。</p>
      </div>
    );
  }

  return (
    <div className="space-y-3 rounded-lg border border-neutral-300 bg-white p-4">
      {hasErrors && (
        <div>
          <h3 className="text-sm font-semibold text-red-800">エラー ({result.errors.length})</h3>
          <ul className="mt-2 space-y-2">
            {result.errors.map((error, idx) => (
              <li key={idx} className="rounded bg-red-50 p-2 text-sm text-red-700">
                <div className="font-medium">
                  {error.line ? `行 ${error.line}:` : ""} {error.type}
                </div>
                <div className="mt-0.5">{error.message}</div>
              </li>
            ))}
          </ul>
        </div>
      )}

      {hasWarnings && (
        <div>
          <h3 className="text-sm font-semibold text-amber-800">警告 ({result.warnings.length})</h3>
          <ul className="mt-2 space-y-2">
            {result.warnings.map((warning, idx) => (
              <li key={idx} className="rounded bg-amber-50 p-2 text-sm text-amber-700">
                <div className="font-medium">
                  {warning.line ? `行 ${warning.line}:` : ""} {warning.type}
                </div>
                <div className="mt-0.5">{warning.message}</div>
              </li>
            ))}
          </ul>
        </div>
      )}
    </div>
  );
}
