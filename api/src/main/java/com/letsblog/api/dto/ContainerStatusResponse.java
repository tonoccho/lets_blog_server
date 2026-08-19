package com.letsblog.api.dto;

import com.letsblog.api.dto.ConnectedServiceStatusResponse.Status;

/**
 * ダッシュボードに表示する、このアプリを構成するDockerコンテナ(lbs-*)の稼働状況(issue #280)。
 * detailはDocker Engine APIが返すStatus文字列をそのまま使う(例: "Up 2 hours (healthy)"、
 * "Exited (1) 3 minutes ago")。
 */
public record ContainerStatusResponse(String id, String name, Status status, String state, String detail) {
}
