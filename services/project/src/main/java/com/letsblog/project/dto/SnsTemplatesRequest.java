package com.letsblog.project.dto;

/** 告知文テンプレートの保存(issue #1583)。欄が欠けている・null は空(既定の告知文を使う)として扱う。 */
public record SnsTemplatesRequest(String publishTemplate, String pvTemplate) {
}
