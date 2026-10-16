package com.letsblog.plugins.comfyui;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** multipart/form-data の読み取り(受ける /images/edits)と書き出し(ComfyUI の /upload/image)。 */
final class Multipart {

    private static final Pattern BOUNDARY = Pattern.compile("boundary=(?:\"([^\"]+)\"|([^;\\s]+))",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern NAME = Pattern.compile("(?:^|[;\\s])name=\"([^\"]*)\"");
    private static final Pattern FILENAME = Pattern.compile("filename=\"([^\"]*)\"");
    private static final Pattern PART_TYPE = Pattern.compile("(?im)^content-type:[ \\t]*(.*)$");
    private static final String CRLF = "\r\n";

    /** 1 つのパート。{@code filename} はファイルでなければ null。 */
    record Part(String name, String filename, String contentType, byte[] data) {

        String text() {
            return new String(data, StandardCharsets.UTF_8);
        }
    }

    private Multipart() {
    }

    static List<Part> parse(String contentType, byte[] body) throws AdapterException {
        Matcher boundaryMatch = contentType == null ? null : BOUNDARY.matcher(contentType);
        if (boundaryMatch == null || !boundaryMatch.find()
                || !contentType.toLowerCase(Locale.ROOT).startsWith("multipart/form-data")) {
            throw AdapterException.invalidRequest("Content-Type は multipart/form-data(boundary つき)にしてください");
        }
        String boundary = boundaryMatch.group(1) != null ? boundaryMatch.group(1) : boundaryMatch.group(2);
        // ISO-8859-1 は 1 バイト = 1 文字なので、バイト列をそのまま文字列として探索できる。
        String raw = new String(body, StandardCharsets.ISO_8859_1);
        String delimiter = "--" + boundary;
        int pos = raw.indexOf(delimiter);
        List<Part> parts = new ArrayList<>();
        if (pos < 0) {
            throw malformed();
        }
        while (true) {
            pos += delimiter.length();
            if (raw.startsWith("--", pos)) {
                return parts;
            }
            int headerEnd = raw.indexOf(CRLF + CRLF, pos);
            if (!raw.startsWith(CRLF, pos) || headerEnd < pos + CRLF.length()) {
                throw malformed();
            }
            String headers = raw.substring(pos + CRLF.length(), headerEnd);
            int dataStart = headerEnd + CRLF.length() * 2;
            int next = raw.indexOf(CRLF + delimiter, dataStart);
            if (next < 0) {
                throw malformed();
            }
            parts.add(toPart(headers, Arrays.copyOfRange(body, dataStart, next)));
            pos = next + CRLF.length();
        }
    }

    private static Part toPart(String headers, byte[] data) {
        Matcher name = NAME.matcher(headers);
        Matcher filename = FILENAME.matcher(headers);
        Matcher type = PART_TYPE.matcher(headers);
        return new Part(name.find() ? name.group(1) : "", filename.find() ? filename.group(1) : null,
                type.find() ? type.group(1).strip() : "", data);
    }

    private static AdapterException malformed() {
        return AdapterException.invalidRequest("multipart/form-data の本文を読めません");
    }

    /** 書き出し用。{@code boundary} と Content-Type の組を {@link #contentType()} で得る。 */
    static final class Writer {

        private final String boundary;
        private final ByteArrayOutputStream out = new ByteArrayOutputStream();

        Writer(String boundary) {
            this.boundary = boundary;
        }

        Writer text(String name, String value) {
            write("--" + boundary + CRLF + "Content-Disposition: form-data; name=\"" + name + "\"" + CRLF + CRLF
                    + value + CRLF);
            return this;
        }

        Writer file(String name, String filename, String contentType, byte[] data) {
            write("--" + boundary + CRLF + "Content-Disposition: form-data; name=\"" + name + "\"; filename=\""
                    + filename + "\"" + CRLF + "Content-Type: " + contentType + CRLF + CRLF);
            out.writeBytes(data);
            write(CRLF);
            return this;
        }

        byte[] build() {
            write("--" + boundary + "--" + CRLF);
            return out.toByteArray();
        }

        String contentType() {
            return "multipart/form-data; boundary=" + boundary;
        }

        private void write(String text) {
            out.writeBytes(text.getBytes(StandardCharsets.UTF_8));
        }
    }
}
