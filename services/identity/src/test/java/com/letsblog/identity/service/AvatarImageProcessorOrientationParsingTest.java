package com.letsblog.identity.service;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * issue #1241: {@link AvatarImageProcessor}のJPEG Orientation解析(readJpegOrientation/
 * parseExifOrientation)の防御的分岐を直接検証する。
 *
 * <p>これら2つは{@code private}メソッドであり、かつ公開APIの{@link AvatarImageProcessor#process}
 * 経由では{@code ImageIO.read}が先に成功しないと到達しない(=渡せるバイト列が実質「本物の
 * 画像として復号できるもの」に限られる)。マーカー解析の異常系(壊れたセグメント長、
 * 未知のマーカー、TIFFヘッダの欠落等)の多くは、復号可能な実画像の中には現れない組み合わせの
 * ため、公開APIだけでは分岐を踏めない。ここではリフレクションで直接呼び出し、
 * マーカー解析ロジックそのものの分岐を検証する。
 */
class AvatarImageProcessorOrientationParsingTest {

    private final AvatarImageProcessor processor = new AvatarImageProcessor();

    private int readJpegOrientation(byte[] bytes) throws ReflectiveOperationException {
        Method method = AvatarImageProcessor.class.getDeclaredMethod("readJpegOrientation", byte[].class);
        method.setAccessible(true);
        return invokeInt(method, bytes);
    }

    private Integer parseExifOrientation(byte[] bytes, int start, int length) throws ReflectiveOperationException {
        Method method =
                AvatarImageProcessor.class.getDeclaredMethod("parseExifOrientation", byte[].class, int.class, int.class);
        method.setAccessible(true);
        try {
            return (Integer) method.invoke(processor, bytes, start, length);
        } catch (InvocationTargetException e) {
            throw new RuntimeException(e.getCause());
        }
    }

    private int invokeInt(Method method, Object... args) throws ReflectiveOperationException {
        try {
            return (int) method.invoke(processor, args);
        } catch (InvocationTargetException e) {
            throw new RuntimeException(e.getCause());
        }
    }

    // --------------------------------------------------------------- readJpegOrientation

    @Test
    void バイト数不足_4バイト未満はnormalを返す() throws ReflectiveOperationException {
        assertEquals(1, readJpegOrientation(new byte[] {(byte) 0xFF, (byte) 0xD8, 0x00}));
    }

    @Test
    void 先頭がFFD8でなければnormalを返す() throws ReflectiveOperationException {
        assertEquals(1, readJpegOrientation(new byte[] {0x00, 0x00, 0x00, 0x00}));
    }

    @Test
    void SOI直後がD8でなければnormalを返す() throws ReflectiveOperationException {
        assertEquals(1, readJpegOrientation(new byte[] {(byte) 0xFF, 0x00, 0x00, 0x00}));
    }

    @Test
    void RSTマーカーはスキップして次を読む() throws ReflectiveOperationException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        writeSoi(out);
        out.write(0xFF);
        out.write(0xD0); // RST0(スキップされる)
        writeEoi(out);
        assertEquals(1, readJpegOrientation(out.toByteArray()));
    }

    @Test
    void マーカー0x01もスキップして次を読む() throws ReflectiveOperationException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        writeSoi(out);
        out.write(0xFF);
        out.write(0x01);
        writeEoi(out);
        assertEquals(1, readJpegOrientation(out.toByteArray()));
    }

    @Test
    void EOIマーカーに達したら探索を打ち切りnormalを返す() throws ReflectiveOperationException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        writeSoi(out);
        writeEoi(out);
        assertEquals(1, readJpegOrientation(out.toByteArray()));
    }

    @Test
    void SOSマーカーに達したら探索を打ち切りnormalを返す() throws ReflectiveOperationException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        writeSoi(out);
        out.write(0xFF);
        out.write(0xDA); // SOS
        assertEquals(1, readJpegOrientation(out.toByteArray()));
    }

    @Test
    void セグメント長が不正_2未満なら打ち切ってnormalを返す() throws ReflectiveOperationException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        writeSoi(out);
        out.write(0xFF);
        out.write(0xE0); // APP0
        out.write(0x00);
        out.write(0x01); // セグメント長=1(自身の2バイトより小さく不正)
        assertEquals(1, readJpegOrientation(out.toByteArray()));
    }

    @Test
    void セグメント長がバイト列末尾を超えるなら打ち切ってnormalを返す() throws ReflectiveOperationException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        writeSoi(out);
        out.write(0xFF);
        out.write(0xE0); // APP0
        out.write(0x00);
        out.write(0x10); // セグメント長=16だが後続バイトが無い
        assertEquals(1, readJpegOrientation(out.toByteArray()));
    }

    @Test
    void APP1だがExifとして解析できなければ次のマーカーへ進む() throws ReflectiveOperationException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        writeSoi(out);
        out.write(0xFF);
        out.write(0xE1); // APP1
        byte[] payload = "NotExif!".getBytes(); // "Exif\0\0"で始まらない
        int segmentLength = payload.length + 2;
        out.write((segmentLength >> 8) & 0xFF);
        out.write(segmentLength & 0xFF);
        out.writeBytes(payload);
        writeEoi(out);
        assertEquals(1, readJpegOrientation(out.toByteArray()));
    }

    @Test
    void ループ条件そのものでバイト列を使い切って終了する() throws ReflectiveOperationException {
        // SOI(2バイト) + APP0マーカー(2バイト) + セグメント長(2バイト、値=2=自身のみ)
        // だけの6バイト。読み進めるとoffsetがbytes.lengthに達し、while条件(offset+4<=length)が
        // 自然にfalseになって終了する(break経由ではない)。
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        writeSoi(out);
        out.write(0xFF);
        out.write(0xE0);
        out.write(0x00);
        out.write(0x02); // セグメント長2(自身の2バイトのみ、ペイロード無し)
        assertEquals(1, readJpegOrientation(out.toByteArray()));
    }

    private void writeSoi(ByteArrayOutputStream out) {
        out.write(0xFF);
        out.write(0xD8);
    }

    private void writeEoi(ByteArrayOutputStream out) {
        out.write(0xFF);
        out.write(0xD9);
    }

    // --------------------------------------------------------------- parseExifOrientation

    @Test
    void lengthが8未満ならnull() throws ReflectiveOperationException {
        byte[] bytes = new byte[20];
        assertNull(parseExifOrientation(bytes, 0, 7));
    }

    @Test
    void startが範囲外ならnull() throws ReflectiveOperationException {
        byte[] bytes = new byte[10];
        assertNull(parseExifOrientation(bytes, 8, 8));
    }

    @Test
    void Exifマジックバイトが一致しなければnull() throws ReflectiveOperationException {
        byte[] bytes = "XXXXXXXXXXXXXXXXXXXX".getBytes();
        assertNull(parseExifOrientation(bytes, 0, 20));
    }

    @Test
    void TIFFヘッダの手前でバイト列が終わっていればnull() throws ReflectiveOperationException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.writeBytes(new byte[] {'E', 'x', 'i', 'f', 0, 0});
        out.write('I'); // TIFFヘッダ8バイトに満たない
        assertNull(parseExifOrientation(out.toByteArray(), 0, 8));
    }

    @Test
    void バイトオーダーマークがIIでもMMでもなければnull() throws ReflectiveOperationException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.writeBytes(new byte[] {'E', 'x', 'i', 'f', 0, 0});
        out.writeBytes(new byte[] {'X', 'X', 42, 0, 8, 0, 0, 0});
        assertNull(parseExifOrientation(out.toByteArray(), 0, 14));
    }

    @Test
    void IFD0オフセットが不正_範囲外ならnull() throws ReflectiveOperationException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.writeBytes(new byte[] {'E', 'x', 'i', 'f', 0, 0});
        // IIヘッダの後、IFDオフセットに極端に大きな値を入れてbytes.lengthを超えさせる
        out.writeBytes(new byte[] {'I', 'I', 42, 0, (byte) 0xFF, (byte) 0xFF, 0x00, 0x00});
        byte[] bytes = out.toByteArray();
        assertNull(parseExifOrientation(bytes, 0, bytes.length));
    }

    @Test
    void エントリがbytesの範囲を超えたらループを打ち切りnullを返す() throws ReflectiveOperationException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.writeBytes(new byte[] {'E', 'x', 'i', 'f', 0, 0});
        out.writeBytes(new byte[] {'I', 'I', 42, 0, 8, 0, 0, 0});
        out.writeBytes(new byte[] {2, 0}); // entryCount=2だが実際のエントリは無い(範囲外)
        byte[] bytes = out.toByteArray();
        assertNull(parseExifOrientation(bytes, 0, bytes.length));
    }

    @Test
    void Exifマジックの2文字目以降が一致しなければnull() throws ReflectiveOperationException {
        byte[] bytes = "Ex!fXXXXXXXXXXXXXX".getBytes();
        assertNull(parseExifOrientation(bytes, 0, bytes.length));
    }

    @Test
    void Exifマジック直後のヌルバイトが崩れていればnull() throws ReflectiveOperationException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.writeBytes(new byte[] {'E', 'x', 'i', 'f', 1, 0}); // 本来0であるべき5バイト目が1
        out.writeBytes(new byte[] {'I', 'I', 42, 0, 8, 0, 0, 0});
        byte[] bytes = out.toByteArray();
        assertNull(parseExifOrientation(bytes, 0, bytes.length));
    }

    @Test
    void ビッグエンディアンでOrientationを直接読み取れる() throws ReflectiveOperationException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.writeBytes(new byte[] {'E', 'x', 'i', 'f', 0, 0});
        out.writeBytes(new byte[] {'M', 'M', 0, 42, 0, 0, 0, 8}); // MM, IFDオフセット=8(BE)
        out.writeBytes(new byte[] {0, 1}); // entryCount=1(BE)
        out.writeBytes(new byte[] {0x01, 0x12}); // tag=0x0112(BE)
        out.writeBytes(new byte[] {0, 3}); // type=SHORT(BE)
        out.writeBytes(new byte[] {0, 0, 0, 1}); // count=1(BE)
        out.writeBytes(new byte[] {0, 6, 0, 0}); // value=6(BE、SHORTは先頭2バイト)
        byte[] bytes = out.toByteArray();
        assertEquals(Integer.valueOf(6), parseExifOrientation(bytes, 0, bytes.length));
    }

    @Test
    void IFD0オフセットが負ならnull() throws ReflectiveOperationException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.writeBytes(new byte[] {'E', 'x', 'i', 'f', 0, 0});
        // IIヘッダの後、IFDオフセットに-1000000(4バイトLE)を入れてtiffStart+ifdOffsetを負にする
        int negativeOffset = -1_000_000;
        out.writeBytes(new byte[] {'I', 'I', 42, 0});
        out.write(negativeOffset & 0xFF);
        out.write((negativeOffset >> 8) & 0xFF);
        out.write((negativeOffset >> 16) & 0xFF);
        out.write((negativeOffset >> 24) & 0xFF);
        byte[] bytes = out.toByteArray();
        assertNull(parseExifOrientation(bytes, 0, bytes.length));
    }

    @Test
    void Orientation値が0_範囲外の下限_ならnull() throws ReflectiveOperationException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.writeBytes(new byte[] {'E', 'x', 'i', 'f', 0, 0});
        out.writeBytes(new byte[] {'I', 'I', 42, 0, 8, 0, 0, 0});
        out.writeBytes(new byte[] {1, 0}); // entryCount=1
        out.writeBytes(new byte[] {0x12, 0x01}); // tag=0x0112(LE)
        out.writeBytes(new byte[] {3, 0}); // type=SHORT
        out.writeBytes(new byte[] {1, 0, 0, 0}); // count=1
        out.writeBytes(new byte[] {0, 0, 0, 0}); // value=0(範囲外)
        byte[] bytes = out.toByteArray();
        assertNull(parseExifOrientation(bytes, 0, bytes.length));
    }

    @Test
    void 直後のマーカーが再度SOIでもスキップして次を読む() throws ReflectiveOperationException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        writeSoi(out);
        out.write(0xFF);
        out.write(0xD8); // 埋め込みのSOI(スキップされる)
        writeEoi(out);
        assertEquals(1, readJpegOrientation(out.toByteArray()));
    }

    @Test
    void マーカーバイトの前が0xFFでなければ打ち切ってnormalを返す() throws ReflectiveOperationException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        writeSoi(out);
        out.write(0x00); // 0xFFで始まらない不正なマーカー先頭
        out.write(0x00);
        assertEquals(1, readJpegOrientation(out.toByteArray()));
    }
}
