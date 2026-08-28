package com.letsblog.platform.dto;

import com.letsblog.platform.dto.ConnectedServiceStatusResponse.Status;

/**
 * legacy-apiから移設(issue #695、C10-3、元は issue #280)。ダッシュボードに表示する、このアプリ自体を
 * 構成するDockerコンテナ(container_name: lbs-*)の稼働状況。detailはDocker Engine APIが返す
 * Status文字列をそのまま使う(例: "Up 2 hours (healthy)"、"Exited (1) 3 minutes ago")。
 */
public record ContainerStatusResponse(String id, String name, Status status, String state, String detail) {
}
