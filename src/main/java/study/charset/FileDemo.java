package study.charset;

import java.io.IOException;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 同じ文字列を文字コード別にファイルへ書き、別の文字コードで読み比べる。
 * ファイルは target/files/ に出力する。実行のたびに中身を消して作り直す。
 * 引数を渡すとその文字列だけを書く。
 */
public class FileDemo {

    private static final Path DIR = Path.of("target", "files");

    private static final Charset SHIFT_JIS = Charset.forName("Shift_JIS");
    private static final Charset WINDOWS_31J = Charset.forName("windows-31j");

    private static final List<Charset> CHARSETS = List.of(StandardCharsets.UTF_8, SHIFT_JIS, WINDOWS_31J);

    /** 引数がないときに書く文字列。ラベルはファイル名の先頭に付く。 */
    private static final Map<String, String> DEFAULT_CASES = new LinkedHashMap<>();

    static {
        // Shift_JIS では表せないが、windows-31j では表せる
        DEFAULT_CASES.put("w31j-ok", "①髙あ");
        // windows-31j でも表せない（BMP 外の漢字・絵文字・ハングル・ユーロ記号・波ダッシュ U+301C）
        DEFAULT_CASES.put("w31j-ng", "あ𠮷🍣한€〜");
    }

    public static void main(String[] args) throws IOException {
        Map<String, String> cases = args.length > 0
                ? Map.of("input", String.join(" ", args))
                : DEFAULT_CASES;

        resetDir();
        cases.forEach(FileDemo::write);
        readAll();
    }

    /** 前回の実行で作ったファイルが残って比較を邪魔しないように、出力先を空にする。 */
    private static void resetDir() throws IOException {
        Files.createDirectories(DIR);
        try (var files = Files.list(DIR)) {
            for (Path file : files.toList()) {
                Files.delete(file);
            }
        }
    }

    private static void write(String label, String text) {
        try {
            System.out.println("== [" + label + "] " + text + " ==");

            System.out.println("  -- Files.writeString: 表せない文字があると例外 --");
            for (Charset cs : CHARSETS) {
                Path file = DIR.resolve(label + "-" + cs.name() + ".txt");
                try {
                    Files.writeString(file, text, cs);
                    System.out.printf("  %-12s → %s  %s%n", cs.name(), file, Encodings.toHex(Files.readAllBytes(file)));
                } catch (CharacterCodingException e) {
                    Files.deleteIfExists(file);
                    System.out.printf("  %-12s → 書けない: %s%n", cs.name(), e);
                }
            }

            System.out.println("  -- getBytes: 表せない文字は黙って ? (3F) になる --");
            for (Charset cs : List.of(SHIFT_JIS, WINDOWS_31J)) {
                Path file = DIR.resolve(label + "-" + cs.name() + "-getBytes.txt");
                Files.write(file, text.getBytes(cs));
                System.out.printf("  %-12s → %s  %s%n", cs.name(), file, Encodings.toHex(Files.readAllBytes(file)));
            }
            System.out.println();
        } catch (IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
    }

    private static void readAll() throws IOException {
        System.out.println("== 読み込み（new String: 読めないバイトは黙って � になる） ==");
        try (var files = Files.list(DIR)) {
            for (Path file : files.sorted().toList()) {
                byte[] bytes = Files.readAllBytes(file);
                for (Charset cs : CHARSETS) {
                    System.out.printf("  %-34s を %-12s で読む → %s%n",
                            file.getFileName(), cs.name(), new String(bytes, cs));
                }
            }
        }
    }
}
