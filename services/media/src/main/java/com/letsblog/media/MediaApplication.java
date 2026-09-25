package com.letsblog.media;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableAsync;

/**
 * media-service(#573)。ComfyUI画像生成・draw.ioダイアグラム・PlantUML/Recharts/Penpotレンダリングを
 * legacy-apiから抽出したもの。lbs_mediaスキーマ(ADR-0004)を所有する。
 */
@SpringBootApplication
@EnableAsync
public class MediaApplication {
    public static void main(String[] args) {
        SpringApplication.run(MediaApplication.class, args);
    }
}
