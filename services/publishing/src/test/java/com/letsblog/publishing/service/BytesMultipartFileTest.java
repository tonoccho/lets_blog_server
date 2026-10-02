package com.letsblog.publishing.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** {@link BytesMultipartFile}の単体テスト(issue #1341)。バイト列を包んで{@code MultipartFile}として渡す。 */
class BytesMultipartFileTest {

    private static BytesMultipartFile file(byte[] bytes) {
        return new BytesMultipartFile("images", "assets/cover.png", "image/png", bytes);
    }

    @Test
    @DisplayName("名前・元のファイル名(パス区切り込み)・content type・バイト列・サイズをそのまま返す")
    void exposesGivenValues() throws IOException {
        byte[] bytes = {1, 2, 3};
        BytesMultipartFile file = file(bytes);

        assertThat(file.getName()).isEqualTo("images");
        assertThat(file.getOriginalFilename()).isEqualTo("assets/cover.png");
        assertThat(file.getContentType()).isEqualTo("image/png");
        assertThat(file.getBytes()).containsExactly(1, 2, 3);
        assertThat(file.getSize()).isEqualTo(3);
        assertThat(file.isEmpty()).isFalse();
        assertThat(file.getInputStream().readAllBytes()).containsExactly(1, 2, 3);
    }

    @Test
    @DisplayName("空のバイト列は空として扱う")
    void emptyBytes() {
        assertThat(file(new byte[0]).isEmpty()).isTrue();
    }

    @Test
    @DisplayName("getBytesは内部の配列を直接返さない(呼び出し側の変更で内容が壊れない)")
    void bytesAreCopied() throws IOException {
        byte[] original = {1, 2};
        BytesMultipartFile file = file(original);
        original[0] = 9;
        file.getBytes()[1] = 9;

        assertThat(file.getBytes()).containsExactly(1, 2);
    }

    @Test
    @DisplayName("transferToでファイルへ書き出せる")
    void transfersToFile(@TempDir Path dir) throws IOException {
        File target = dir.resolve("out.png").toFile();

        file(new byte[] {4, 5}).transferTo(target);

        assertThat(Files.readAllBytes(target.toPath())).containsExactly(4, 5);
    }

    @Test
    @DisplayName("バイト列がnullなら生成できない")
    void nullBytes() {
        assertThatThrownBy(() -> file(null)).isInstanceOf(NullPointerException.class);
    }
}
