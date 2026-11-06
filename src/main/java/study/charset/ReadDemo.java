package study.charset;

import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * 既存のファイルを文字コード別に読み比べる。
 * 引数にファイルかディレクトリを渡す。省略すると samples/ の中のファイルをすべて読む。
 */
public class ReadDemo {

    private static final Path DEFAULT_DIR = Path.of("samples");

    private static final List<Charset> CHARSETS = List.of(
            StandardCharsets.UTF_8,
            Charset.forName("Shift_JIS"),
            Charset.forName("windows-31j"),
            Charset.forName("EUC-JP"));

    /** 16 進表示するバイト数の上限。長いファイルで画面が埋まらないようにする。 */
    private static final int HEX_LIMIT = 48;

    /** 表示する文字数の上限。 */
    private static final int TEXT_LIMIT = 40;

    public static void main(String[] args) throws IOException {
        List<Path> targets = new ArrayList<>();
        for (String arg : args.length > 0 ? args : new String[] {DEFAULT_DIR.toString()}) {
            Path p = Path.of(arg);
            if (Files.isDirectory(p)) {
                try (var files = Files.list(p)) {
                    files.filter(Files::isRegularFile).sorted().forEach(targets::add);
                }
            } else {
                targets.add(p);
            }
        }

        for (Path file : targets) {
            read(file);
        }
    }

    private static void read(Path file) throws IOException {
        byte[] bytes = Files.readAllBytes(file);

        System.out.println("== " + file + " (" + bytes.length + " bytes) ==");
        System.out.println("  バイト列: " + hexPreview(bytes));
        System.out.println("  BOM     : " + detectBom(bytes));

        List<String> ok = new ArrayList<>();
        for (Charset cs : CHARSETS) {
            var error = Encodings.findDecodeError(bytes, cs);
            error.ifPresentOrElse(e -> { }, () -> ok.add(cs.name()));
            System.out.printf("  %-12s %s  %s%n",
                    cs.name(),
                    error.map(e -> "NG").orElse("OK"),
                    error.map(e -> e.toString()).orElseGet(() -> textPreview(new String(bytes, cs))));
        }
        System.out.println("  矛盾なく読める文字コード: " + (ok.isEmpty() ? "なし" : String.join(", ", ok)));
        System.out.println();
    }

    /** 先頭のバイト列から BOM（バイト順マーク）を判定する。 */
    private static String detectBom(byte[] b) {
        if (startsWith(b, 0xEF, 0xBB, 0xBF)) {
            return "UTF-8 の BOM あり (EF BB BF)";
        }
        if (startsWith(b, 0xFE, 0xFF)) {
            return "UTF-16BE の BOM あり (FE FF)";
        }
        if (startsWith(b, 0xFF, 0xFE)) {
            return "UTF-16LE の BOM あり (FF FE)";
        }
        return "なし";
    }

    private static boolean startsWith(byte[] b, int... prefix) {
        if (b.length < prefix.length) {
            return false;
        }
        for (int i = 0; i < prefix.length; i++) {
            if ((b[i] & 0xFF) != prefix[i]) {
                return false;
            }
        }
        return true;
    }

    private static String hexPreview(byte[] bytes) {
        if (bytes.length <= HEX_LIMIT) {
            return Encodings.toHex(bytes);
        }
        byte[] head = java.util.Arrays.copyOf(bytes, HEX_LIMIT);
        return Encodings.toHex(head) + " ...";
    }

    /** 改行や BOM を見える形にして、長ければ切り詰める。 */
    private static String textPreview(String text) {
        String visible = text.replace("\uFEFF", "[BOM]").replace("\r", "\\r").replace("\n", "\\n");
        if (visible.codePointCount(0, visible.length()) <= TEXT_LIMIT) {
            return visible;
        }
        return visible.substring(0, visible.offsetByCodePoints(0, TEXT_LIMIT)) + "...";
    }
}
