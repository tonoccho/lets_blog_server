package com.letsblog.platform.controller;

import com.letsblog.platform.service.VscodeExtensionBuildService;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;

/**
 * システム画面からのVSCode拡張機能(.vsix)オンデマンドビルド・ダウンロード。legacy-apiの
 * VscodeExtensionControllerと同じAPI形状のままplatform-serviceへ移設したもの
 * (issue #696、C10-4)。gatewayの{@code /api/system/**}ルート(platform)を経由する。
 */
@RestController
@RequestMapping("/api/system/vscode-extension")
public class VscodeExtensionController {

    private final VscodeExtensionBuildService vscodeExtensionBuildService;

    public VscodeExtensionController(VscodeExtensionBuildService vscodeExtensionBuildService) {
        this.vscodeExtensionBuildService = vscodeExtensionBuildService;
    }

    /**
     * 認可不要: 本システム用のVSCode拡張(.vsix)を配布する(issue #830)。
     * 拡張を入れられること自体が全利用者に必要で、配布物に利用者固有のデータは含まれない。
     *
     * <p>ビルド出力はリクエストごとに一意なディレクトリに置かれる(issue #1190)。
     * レスポンス本文の送出(Springがこのメソッドの戻り値からストリーミングする段階)が
     * 完了した後に、そのディレクトリを後片付けする({@link CleanupOnCloseResource}参照)。
     */
    @GetMapping
    public ResponseEntity<Resource> download() {
        VscodeExtensionBuildService.BuiltExtension built = vscodeExtensionBuildService.buildAndGetVsix();
        Resource resource = new CleanupOnCloseResource(built,
                () -> vscodeExtensionBuildService.cleanupAfterDownload(built));
        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_OCTET_STREAM)
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + built.filename() + "\"")
                .body(resource);
    }

    /**
     * {@link FileSystemResource}を、入力ストリームが閉じられた時点(=Springがレスポンス本文の
     * 送出を終えた時点、{@code ResourceHttpMessageConverter#writeContent}が成功・失敗を問わず
     * finally節で{@code close()}する)で{@code cleanup}を実行するように拡張したもの。
     * これにより、ダウンロード中に他リクエストのビルドが同じファイルを削除してしまう心配なく
     * (issue #1190のリクエスト単位の出力先割り当てと組み合わせて)、
     * かつ送出後は使い捨ての出力先ディレクトリをディスクに残さない。
     * {@code cleanup}(= {@code VscodeExtensionBuildService.cleanupAfterDownload}が呼ぶ
     * {@code deleteRecursively})は対象が既に存在しない場合は何もしない実装のため、
     * {@code close()}が複数回呼ばれても二重実行を防ぐ特別な仕掛けは不要。
     */
    private static final class CleanupOnCloseResource extends FileSystemResource {

        private final Runnable cleanup;

        CleanupOnCloseResource(VscodeExtensionBuildService.BuiltExtension built, Runnable cleanup) {
            super(built.vsixPath());
            this.cleanup = cleanup;
        }

        @Override
        public InputStream getInputStream() throws IOException {
            InputStream delegate = super.getInputStream();
            return new FilterInputStream(delegate) {
                @Override
                public void close() throws IOException {
                    try {
                        super.close();
                    } finally {
                        cleanup.run();
                    }
                }
            };
        }
    }
}
