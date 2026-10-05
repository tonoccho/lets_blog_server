package com.letsblog.project.dto;

/** X の接続が済んだ結果(issue #1574)。トークンは含まない。 */
public record XConnectResult(Long projectId, String accountName) {
}
