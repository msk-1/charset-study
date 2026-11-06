package study.charset;

import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * 同じ文字列が文字コードごとにどんなバイト列になるかを表示する。
 * 引数を渡すとその文字列を調べる。
 */
public class Main {

    private static final List<Charset> CHARSETS = List.of(
            StandardCharsets.UTF_8,
            StandardCharsets.UTF_16BE,
            StandardCharsets.UTF_16LE,
            Charset.forName("Shift_JIS"),
            Charset.forName("windows-31j"),
            Charset.forName("EUC-JP"),
            StandardCharsets.ISO_8859_1);

    public static void main(String[] args) {
        String text = args.length > 0 ? String.join(" ", args) : "Aあ漢🍣";

        System.out.println("文字列: " + text);
        System.out.println("デフォルト文字コード: " + Charset.defaultCharset());
        System.out.println("length(): " + text.length() + " (char の数 = UTF-16 のコード単位数)");
        System.out.println("codePointCount: " + text.codePointCount(0, text.length()) + " (文字の数)");
        System.out.println();

        System.out.println("== コードポイント ==");
        text.codePoints().forEach(cp ->
                System.out.printf("  %s  U+%04X  %s%n",
                        Character.toString(cp), cp, Character.getName(cp)));
        System.out.println();

        System.out.println("== 文字コード別のバイト列 ==");
        for (Charset cs : CHARSETS) {
            byte[] bytes = text.getBytes(cs);
            String decoded = new String(bytes, cs);
            System.out.printf("  %-12s %2d bytes  %s%s%n",
                    cs.name(), bytes.length, Encodings.toHex(bytes),
                    decoded.equals(text) ? "" : "  ← 往復で壊れた: " + decoded);
        }
        System.out.println();

        System.out.println("== 文字化けの再現（UTF-8 のバイトを Shift_JIS として読む） ==");
        System.out.println("  " + Encodings.misdecode(text, StandardCharsets.UTF_8, Charset.forName("Shift_JIS")));
    }
}
