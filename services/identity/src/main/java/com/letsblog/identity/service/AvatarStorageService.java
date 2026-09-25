package com.letsblog.identity.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

/**
 * issue #1241 AC3/AC5: アバター画像を{@code app.avatar-storage-path}配下の専用ボリュームへ
 * 保存・読み込み・(実質的な)削除する。
 *
 * <p>media-serviceの{@link com.letsblog.identity.service.AvatarImageProcessor}のjavadocで触れた
 * 通り、{@code GeneratedImageStorageService}(media-service)と同じ「専用ディレクトリへ決定的な
 * パスで書き込む」パターンだが、アバターは1ユーザーにつき常に最大1枚のため、連番管理は不要で
 * ユーザーIDそのものをファイル名(常に{@code .jpg}。{@link AvatarImageProcessor}が出力形式を
 * JPEGへ統一しているため)に使う。差し替え(AC5)は同じパスへの上書きで実現し、
 * 上書き前のバイト列はディスク上に残らない({@link Files#write(Path, byte[], java.nio.file.OpenOption...)}
 * の既定オプションはCREATE+TRUNCATE_EXISTING+WRITE)。
 */
@Service
public class AvatarStorageService {

    private final Path storageDir;

    public AvatarStorageService(@Value("${app.avatar-storage-path}") String storagePath) {
        this.storageDir = Path.of(storagePath);
    }

    public void store(Long userId, byte[] data) {
        try {
            Files.createDirectories(storageDir);
            Files.write(storageDir.resolve(fileName(userId)), data);
        } catch (IOException e) {
            throw new AvatarStorageException("アバター画像の保存に失敗しました: " + e.getMessage(), e);
        }
    }

    public Optional<byte[]> load(Long userId) {
        Path target = storageDir.resolve(fileName(userId));
        if (!Files.exists(target)) {
            return Optional.empty();
        }
        try {
            return Optional.of(Files.readAllBytes(target));
        } catch (IOException e) {
            throw new AvatarStorageException("アバター画像の読み込みに失敗しました: " + e.getMessage(), e);
        }
    }

    private String fileName(Long userId) {
        return userId + ".jpg";
    }
}
