package study.charset;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CharsetEncoder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Shift_JIS と windows-31j の「バイト列 → Unicode」対応表を HTML で生成する。
 * 表の中身は手書きせず、実行中の JDK の Charset から取り出す。
 * 出力先は doc/charset-mapping.html。
 */
public class MappingTable {

    private static final Path OUTPUT = Path.of("doc", "charset-mapping.html");

    private static final Charset SJIS = Charset.forName("Shift_JIS");
    private static final Charset W31J = Charset.forName("windows-31j");

    /** セルの分類。表の色分けと凡例に使う。 */
    enum Cat {
        SAME("same", "共通", "どちらでも同じ Unicode の文字になる"),
        DIFF("diff", "対応先が違う", "どちらにもあるが、別の Unicode の文字になる（波ダッシュ問題など）"),
        SJIS_ONLY("sjis", "Shift_JIS のみ", "Shift_JIS だけが文字を割り当てている"),
        NEC("nec", "NEC 特殊文字", "windows-31j のみ。1 バイト目 87（13 区）"),
        NEC_IBM("necibm", "NEC 選定 IBM 拡張文字", "windows-31j のみ。1 バイト目 ED〜EE（89〜92 区）"),
        IBM("ibm", "IBM 拡張文字", "windows-31j のみ。1 バイト目 FA〜FC（115〜119 区）"),
        GAIJI("gaiji", "外字（私用領域）", "windows-31j のみ。1 バイト目 F0〜F9。Unicode の私用領域に対応"),
        W31J_OTHER("w31j", "windows-31j のみ（その他）", "上の分類に入らない windows-31j だけの割り当て"),
        LEAD("lead", "2 バイト文字の 1 バイト目", "単独では文字にならず、次のバイトと組で 1 文字になる"),
        NONE("none", "未定義", "どちらでも文字が割り当てられていない");

        final String css;
        final String label;
        final String description;

        Cat(String css, String label, String description) {
            this.css = css;
            this.label = label;
            this.description = description;
        }
    }

    /** 1 マス分の情報。 */
    record Cell(int[] bytes, String sjis, String w31j, Cat cat) {
    }

    public static void main(String[] args) throws IOException {
        // 1 バイト目ごとの 2 バイト文字の表。キーは 1 バイト目
        Map<Integer, List<Cell>> doubleByte = new LinkedHashMap<>();
        for (int lead = 0x81; lead <= 0xFC; lead++) {
            List<Cell> cells = new ArrayList<>();
            for (int trail = 0x40; trail <= 0xFC; trail++) {
                if (trail == 0x7F) {
                    continue;
                }
                cells.add(cell(new int[] {lead, trail}, categoryForLead(lead)));
            }
            if (cells.stream().anyMatch(c -> c.cat != Cat.NONE)) {
                doubleByte.put(lead, cells);
            }
        }

        List<Cell> singleByte = new ArrayList<>();
        for (int b = 0x00; b <= 0xFF; b++) {
            Cell c = cell(new int[] {b}, Cat.W31J_OTHER);
            if (c.cat == Cat.NONE && doubleByte.containsKey(b)) {
                c = new Cell(c.bytes, null, null, Cat.LEAD);
            }
            singleByte.add(c);
        }

        String html = render(singleByte, doubleByte);
        Files.createDirectories(OUTPUT.getParent());
        Files.writeString(OUTPUT, html);
        System.out.println("書き出した: " + OUTPUT + " (" + Files.size(OUTPUT) / 1024 + " KB)");
    }

    // ---- 分類 ----

    /** windows-31j だけにある文字を、1 バイト目から分類する。 */
    private static Cat categoryForLead(int lead) {
        if (lead == 0x87) {
            return Cat.NEC;
        }
        if (lead == 0xED || lead == 0xEE) {
            return Cat.NEC_IBM;
        }
        if (lead >= 0xF0 && lead <= 0xF9) {
            return Cat.GAIJI;
        }
        if (lead >= 0xFA && lead <= 0xFC) {
            return Cat.IBM;
        }
        return Cat.W31J_OTHER;
    }

    private static Cell cell(int[] bytes, Cat w31jOnlyCat) {
        String s = decodeOne(SJIS, bytes);
        String w = decodeOne(W31J, bytes);
        Cat cat;
        if (s == null && w == null) {
            cat = Cat.NONE;
        } else if (s != null && w != null) {
            cat = s.equals(w) ? Cat.SAME : Cat.DIFF;
        } else if (s != null) {
            cat = Cat.SJIS_ONLY;
        } else {
            cat = w31jOnlyCat;
        }
        return new Cell(bytes, s, w, cat);
    }

    /** bytes を厳密にデコードし、ちょうど 1 文字になればそれを返す。ならなければ null。 */
    private static String decodeOne(Charset cs, int[] bytes) {
        byte[] b = new byte[bytes.length];
        for (int i = 0; i < bytes.length; i++) {
            b[i] = (byte) bytes[i];
        }
        try {
            String s = cs.newDecoder().decode(ByteBuffer.wrap(b)).toString();
            return s.codePointCount(0, s.length()) == 1 ? s : null;
        } catch (CharacterCodingException e) {
            return null;
        }
    }

    // ---- 区点 ----

    /** Shift_JIS 方式の 2 バイトを JIS の区点番号に戻す。 */
    static int[] kuten(int lead, int trail) {
        int base = lead <= 0x9F ? (lead - 0x81) * 2 + 1 : (lead - 0xC1) * 2 + 1;
        if (trail < 0x9F) {
            return new int[] {base, trail - 0x3F - (trail >= 0x80 ? 1 : 0)};
        }
        return new int[] {base + 1, trail - 0x9E};
    }

    // ---- HTML ----

    private static String render(List<Cell> singleByte, Map<Integer, List<Cell>> doubleByte) {
        StringBuilder h = new StringBuilder(4 * 1024 * 1024);
        h.append("""
                <!doctype html>
                <html lang="ja">
                <head>
                <meta charset="utf-8">
                <meta name="viewport" content="width=device-width, initial-scale=1">
                <title>日本語の文字コード入門</title>
                <style>
                """).append(CSS).append("""
                </style>
                </head>
                <body>
                <main>
                <h1>日本語の文字コード入門</h1>
                <p class="subtitle">文字集合・符号化方式・Shift_JIS・windows-31j・Unicode・UTF-8・変換・MJ+</p>
                """);
        h.append("""
                <p class="lead-text">日本語の文字コードを、文字集合と符号化方式の両面から学ぶための資料。5 部に分けてまとめている。</p>
                <ol class="lead-list">
                <li><b>基礎</b>：文字集合・符号化方式・文字コードという言葉の違いと、どの符号化方式がどの文字集合を使うかの対応表。
                Java の文字列が Unicode で、<code>Charset</code> が符号化方式にあたること</li>
                <li><b>JIS と Shift_JIS 系</b>：文字集合 JIS X 0208 の区点と、それを Shift_JIS・windows-31j でバイト列にした表。
                windows-31j が拡張した範囲と、2 つの方式の違いも示す</li>
                <li><b>Unicode と UTF-8</b>：文字集合 Unicode の面と BMP の使い道と、それを UTF-8 でバイト列にする規則</li>
                <li><b>変換の注意点</b>：符号化方式を変換するときに起きる文字化け・文字の欠落・バイト列の変化と、その防ぎ方</li>
                <li><b>行政の文字</b>：MJ 文字と MJ+。Shift_JIS 系に入りきらない大きな文字集合と、Unicode で表すときの注意</li>
                </ol>
                """);
        h.append("<p class=\"lead-text\">表と数値は手書きではなく、Java の <code>Charset</code> と Unicode のデータ（JDK ")
                .append(esc(System.getProperty("java.version")))
                .append("）から <code>MappingTable.java</code> で生成した。MJ 文字と MJ+ の説明は公開資料による。</p>\n");

        h.append("""
                <nav class="toc" aria-label="目次"><b>CONTENTS</b>
                <span class="tgroup">01　基礎</span>
                <a href="#basics" data-legend="none">文字集合・符号化方式・文字コード</a>
                <a href="#schemes" data-legend="none">文字集合と符号化方式の対応表</a>
                <a href="#java" data-legend="none">Java の文字と Unicode</a>
                <span class="tgroup">02　JIS と Shift_JIS 系</span>
                <a href="#kuten" data-legend="kuten">JIS X 0208 の区点</a>
                <a href="#overview" data-legend="sjis">Shift_JIS 系の 1 バイト目</a>
                <a class="sub" href="#legend" data-legend="sjis">表の色分け</a>
                <a href="#single" data-legend="sjis">1 バイト文字</a>
                <a href="#double" data-legend="sjis">2 バイト文字</a>
                <a class="sub" href="#double-ext" data-legend="sjis">windows-31j の拡張</a>
                <a class="sub" href="#double-jis" data-legend="sjis">JIS X 0208</a>
                <a href="#sjis-w31j" data-legend="none">Shift_JIS と windows-31j の違い</a>
                <span class="tgroup">03　Unicode と UTF-8</span>
                <a href="#umap" data-legend="unicode">Unicode の全体図</a>
                <a class="sub" href="#planes-map" data-legend="unicode">面ごとの全体図</a>
                <a href="#utf8" data-legend="none">コードポイントと UTF-8</a>
                <span class="tgroup">04　変換の注意点</span>
                <a href="#convert" data-legend="none">文字コード変換の注意点</a>
                <a class="sub" href="#convert-check" data-legend="none">変換するときの確認項目</a>
                <span class="tgroup">05　行政の文字</span>
                <a href="#mj" data-legend="none">MJ 文字と MJ+</a>
                """);
        h.append("</nav>\n").append(sideLegend());
        // 第 1 部から順に、文字集合 → その符号化方式 の順で並べる
        part(h, "part-basics", "PART 01", "基礎");
        renderBasics(h);
        renderSchemes(h);
        renderJava(h);
        part(h, "part-jis", "PART 02", "JIS と Shift_JIS 系");
        renderKutenMap(h, doubleByte);
        renderOverview(h, singleByte, doubleByte);
        renderSingleByte(h, singleByte);
        renderDoubleByte(h, doubleByte);
        renderUnicodeSide(h, singleByte, doubleByte);
        part(h, "part-unicode", "PART 03", "Unicode と UTF-8");
        renderUnicodeMap(h);
        renderUtf8(h, singleByte, doubleByte);
        part(h, "part-convert", "PART 04", "変換の注意点");
        renderConversion(h, doubleByte);
        part(h, "part-mj", "PART 05", "行政の文字");
        renderMj(h, doubleByte);

        h.append("</main>\n<script>\n").append(JS).append("</script>\n</body>\n</html>\n");
        return h.toString();
    }

    /** 部の見出し。章（h2）をまとめる区切りとして置く。 */
    private static void part(StringBuilder h, String id, String number, String title) {
        h.append("<div class=\"part\" id=\"").append(id).append("\"><span>").append(number).append("</span>")
                .append(esc(title)).append("</div>\n");
    }

    /** サイドパネルの凡例。見ている節に合わせて、JS で表示するブロックを切り替える。 */
    private static String sideLegend() {
        StringBuilder l = new StringBuilder("<aside class=\"side-legend\" aria-label=\"凡例\">"
                + "<button type=\"button\" class=\"sl-title\" aria-expanded=\"true\"><span>LEGEND</span><span class=\"sl-caret\"></span>"
                + "</button>\n<div class=\"sl-body\">\n");

        l.append("<div class=\"sl-block\" data-legend=\"sjis\">\n");
        for (Cat c : List.of(Cat.SAME, Cat.DIFF, Cat.NEC, Cat.NEC_IBM, Cat.IBM, Cat.GAIJI, Cat.LEAD)) {
            l.append("<div class=\"sl-item\"><span class=\"swatch ").append(c.css).append("\"></span>").append(c.label)
                    .append("</div>\n");
        }
        l.append("<div class=\"sl-item\"><span class=\"swatch none\"></span>未定義・使わない値</div>\n")
                .append("<div class=\"sl-sep\">全体図：1 バイト目</div>\n")
                .append("<div class=\"sl-item\"><span class=\"swatch one\"></span>1 バイト文字</div>\n")
                .append("<div class=\"sl-item\"><span class=\"swatch jis\"></span>JIS X 0208 の 1 バイト目</div>\n")
                .append("</div>\n");

        l.append("<div class=\"sl-block\" data-legend=\"kuten\">\n");
        for (Region r : REGIONS) {
            if (r.css.equals("k-empty")) {
                continue;
            }
            String ku = r.from == r.to ? r.from + "" : r.from + "〜" + r.to;
            l.append("<div class=\"sl-item\"><span class=\"swatch ").append(r.css).append("\"></span>").append(esc(r.label))
                    .append("<small>").append(ku).append(" 区</small></div>\n");
        }
        l.append("<div class=\"sl-item\"><span class=\"swatch k-empty\"></span>空き・文字なし</div>\n")
                .append("<div class=\"sl-item\"><span class=\"swatch k-diff-swatch\"></span>対応先が違う（赤枠）</div>\n")
                .append("</div>\n");

        l.append("<div class=\"sl-block\" data-legend=\"unicode\">\n");
        for (UCat c : UCat.values()) {
            l.append("<div class=\"sl-item\"><span class=\"swatch ").append(c.css).append("\"></span>").append(c.label)
                    .append("</div>\n");
        }
        l.append("<div class=\"sl-note\">マスは下から割合で積み上げ</div>\n</div>\n");

        l.append("<div class=\"sl-block\" data-legend=\"none\"><div class=\"sl-note\">この節に色分けはない</div></div>\n");
        return l.append("</div>\n</aside>\n").toString();
    }

    // ---- 文字コード変換の注意点 ----

    /** 各コードポイントを U+XXXX で空白区切りにする。 */
    private static String cps(String s) {
        StringBuilder sb = new StringBuilder();
        s.codePoints().forEach(c -> sb.append(sb.isEmpty() ? "" : " ").append(String.format("U+%04X", c)));
        return sb.toString();
    }

    private static String bytesOf(String s, Charset cs) {
        return hexBytes(toInts(s.getBytes(cs)));
    }

    private static String decodeLenient(int[] bytes, Charset cs) {
        byte[] b = new byte[bytes.length];
        for (int i = 0; i < bytes.length; i++) {
            b[i] = (byte) bytes[i];
        }
        return new String(b, cs);
    }

    /** 文字コードを変換するときに起きる問題と防ぎ方。例の値はすべて JDK で求める。 */
    private static void renderConversion(StringBuilder h, Map<Integer, List<Cell>> doubleByte) {
        Charset utf8 = java.nio.charset.StandardCharsets.UTF_8;

        h.append("""
                <section id="convert">
                <h2>文字コード変換の注意点</h2>
                <p>文字コードの変換は「バイト列 → 文字（デコード）」と「文字 → バイト列（エンコード）」の 2 段で行う。
                Java では、どちらの段でも問題が起きたときに例外を出さず、黙って別の文字に置き換えるのが既定の動作である。
                そのため、変換の失敗はデータが欠けてから気付くことが多い。</p>
                """);

        // 1. 符号化方式の指定を省略しない
        h.append("<h3>1. 符号化方式を必ず指定する</h3>\n")
                .append("<p><code>new String(bytes)</code>・<code>getBytes()</code>・<code>FileReader</code> のように符号化方式を省略すると、"
                        + "実行環境の既定値が使われる。JDK 18 からは既定値が UTF-8 に統一された（JEP 400）ので、"
                        + "Windows で作った Shift_JIS のファイルを省略形で読むと化ける。この環境の既定値は <code>")
                .append(esc(Charset.defaultCharset().name())).append("</code>。</p>\n");
        String word = "文字";
        h.append("<div class=\"scroll\"><table class=\"list\">\n<thead><tr><th>書いた方式</th><th>読んだ方式</th><th>バイト列</th>"
                + "<th>結果</th></tr></thead>\n<tbody>\n");
        Charset[][] pairs = {{W31J, utf8}, {utf8, W31J}, {W31J, W31J}};
        for (Charset[] pr : pairs) {
            byte[] b = word.getBytes(pr[0]);
            h.append("<tr><td>").append(pr[0].name()).append("</td><td>").append(pr[1].name())
                    .append("</td><td class=\"mono bytes\">").append(hexBytes(toInts(b))).append("</td><td class=\"glyph\">")
                    .append(esc(new String(b, pr[1]))).append("</td></tr>\n");
        }
        h.append("</tbody>\n</table></div>\n")
                .append("<p class=\"note\">� （U+FFFD）は、デコードできなかったバイトの代わりに入る置換文字。"
                        + "一度置き換わると元のバイト列には戻せない。</p>\n");

        // 2. 表せない文字は黙って置き換わる
        int[] lone = {0x82};
        h.append("<h3>2. 変換できない文字は黙って置き換わる</h3>\n")
                .append("<p><code>getBytes</code> と <code>new String</code> は、変換できない文字を例外にせず置き換える。"
                        + "エンコードでは <code>?</code>（3F）に、デコードでは <code>�</code>（U+FFFD）になる。</p>\n")
                .append("<div class=\"scroll\"><table class=\"list\">\n<thead><tr><th>操作</th><th>入力</th><th>結果</th></tr></thead>\n<tbody>\n")
                .append("<tr><td>windows-31j へエンコード</td><td class=\"glyph\">🍣</td><td class=\"mono\">")
                .append(bytesOf("🍣", W31J)).append("（?）</td></tr>\n")
                .append("<tr><td>windows-31j へエンコード</td><td class=\"glyph\">𠮷</td><td class=\"mono\">")
                .append(bytesOf("𠮷", W31J)).append("（?）</td></tr>\n")
                .append("<tr><td>windows-31j でデコード（2 バイト文字の 1 バイト目だけ）</td><td class=\"mono\">")
                .append(hexBytes(lone)).append("</td><td class=\"mono\">").append(cps(decodeLenient(lone, W31J)))
                .append("</td></tr>\n</tbody>\n</table></div>\n")
                .append("<p>失敗を検出したいときは、<code>Charset.newEncoder()</code> と <code>newDecoder()</code> を使う。"
                        + "既定で <code>CodingErrorAction.REPORT</code> になっており、変換できないと "
                        + "<code>CharacterCodingException</code> を投げる。変換前に <code>canEncode</code> で調べることもできる。</p>\n")
                .append("""
                        <pre><code>CharsetEncoder enc = Charset.forName("windows-31j").newEncoder();
                        enc.canEncode("🍣");                 // false
                        enc.encode(CharBuffer.wrap("🍣"));   // UnmappableCharacterException</code></pre>
                        """);

        // 3. Shift_JIS と windows-31j の取り違え
        List<Cell> diffs = doubleByte.values().stream().flatMap(List::stream).filter(c -> c.cat == Cat.DIFF).toList();
        h.append("<h3>3. Shift_JIS と windows-31j を取り違えない</h3>\n")
                .append("<p>どちらも「Shift_JIS」と呼ばれることが多いが、Java では別の符号化方式で、")
                .append(diffs.size()).append(" 個のバイト列が別の Unicode の文字になる。"
                        + "Shift_JIS で読んだ文字を windows-31j で書くと、このうち ")
                .append(diffs.stream().filter(c -> encodeStrict(W31J, c.sjis) == null).count())
                .append(" 個は書く先がなく <code>?</code> になる（波ダッシュ問題）。"
                        + "残りは windows-31j が元のバイト列に戻してくれるが、Java 以外の変換表でも同じとは限らない。"
                        + "Windows で作られたデータは、ふつう windows-31j（MS932）で読み書きする。</p>\n")
                .append("<div class=\"scroll\"><table class=\"list\">\n<thead><tr><th>バイト列</th><th>Shift_JIS で読む</th>"
                        + "<th>windows-31j で読む</th><th>Shift_JIS で読んで windows-31j で書く</th></tr></thead>\n<tbody>\n");
        for (Cell c : diffs) {
            h.append("<tr><td class=\"mono\">").append(hexBytes(c.bytes)).append("</td><td><span class=\"glyph\">")
                    .append(esc(c.sjis)).append("</span> <span class=\"mono\">U+").append(cp(c.sjis)).append("</span></td><td><span class=\"glyph\">")
                    .append(esc(c.w31j)).append("</span> <span class=\"mono\">U+").append(cp(c.w31j)).append("</span></td><td class=\"mono\">")
                    .append(bytesOf(c.sjis, W31J)).append("</td></tr>\n");
        }
        h.append("</tbody>\n</table></div>\n");

        // 4. 読んで書き戻すとバイト列が変わる
        List<Cell> changed = new ArrayList<>();
        doubleByte.values().stream().flatMap(List::stream).filter(c -> c.w31j != null).forEach(c -> {
            int[] back = encodeStrict(W31J, c.w31j);
            if (back != null && !java.util.Arrays.equals(back, c.bytes)) {
                changed.add(c);
            }
        });
        h.append("<h3>4. 読んで書き戻すとバイト列が変わることがある</h3>\n")
                .append("<p>windows-31j では同じ文字が複数のバイト列にあるので、読んでから書き戻すと別のバイト列になる文字が ")
                .append(changed.size()).append(" 個ある。文字としては同じなので画面では気付かないが、"
                        + "ファイルの差分比較・ハッシュ値・バイト列での検索が一致しなくなる。詳しくは"
                        + "<a href=\"#sjis-w31j\">Shift_JIS と windows-31j の違い</a>を参照。</p>\n")
                .append("<div class=\"scroll\"><table class=\"list\">\n<thead><tr><th>元のバイト列</th><th>文字</th>"
                        + "<th>書き戻したバイト列</th></tr></thead>\n<tbody>\n");
        for (Cell c : changed.subList(0, Math.min(5, changed.size()))) {
            h.append("<tr><td class=\"mono\">").append(hexBytes(c.bytes)).append("</td><td class=\"glyph\">").append(esc(c.w31j))
                    .append("</td><td class=\"mono\">").append(hexBytes(encodeStrict(W31J, c.w31j))).append("</td></tr>\n");
        }
        h.append("</tbody>\n</table></div>\n<p class=\"note\">先頭の 5 個だけ示した。</p>\n");

        // 5. バイト数と文字数
        String ai = "あい";
        byte[] aiUtf8 = ai.getBytes(utf8);
        int[] cut = toInts(java.util.Arrays.copyOf(aiUtf8, 4));
        String kana = "ｱｲｳ";
        h.append("<h3>5. バイト数で切ると文字の途中で切れる</h3>\n")
                .append("<p>同じ文字でも、符号化方式によってバイト数が変わる。データベースの列の長さや通信の電文長をバイト数で決めていると、"
                        + "変換後にあふれたり、切り詰めたときに文字の途中で切れたりする。</p>\n")
                .append("<div class=\"scroll\"><table class=\"list\">\n<thead><tr><th>文字列</th><th>windows-31j</th><th>UTF-8</th>"
                        + "</tr></thead>\n<tbody>\n");
        for (String t : List.of(kana, "漢字", "🍣")) {
            h.append("<tr><td class=\"glyph\">").append(esc(t)).append("</td><td>");
            int[] w = encodeStrict(W31J, t);
            h.append(w == null ? "表せない" : w.length + " バイト").append("</td><td>").append(t.getBytes(utf8).length)
                    .append(" バイト</td></tr>\n");
        }
        h.append("</tbody>\n</table></div>\n")
                .append("<p>「").append(ai).append("」の UTF-8（<span class=\"mono\">").append(hexBytes(toInts(aiUtf8)))
                .append("</span>）を 4 バイトで切ると <span class=\"mono\">").append(hexBytes(cut)).append("</span> になり、読むと「")
                .append(esc(decodeLenient(cut, utf8))).append("」になる。切り詰めるときは、バイト数ではなく文字（コードポイント）の境目で切る。</p>\n");

        // 6. BOM
        int[] bomA = {0xEF, 0xBB, 0xBF, 0x41};
        String bomRead = decodeLenient(bomA, utf8);
        h.append("<h3>6. UTF-8 の BOM は Java では取り除かれない</h3>\n")
                .append("<p>Windows のメモ帳や Excel は、UTF-8 のファイルの先頭に BOM（<span class=\"mono\">EF BB BF</span>）を付けることがある。"
                        + "Java の UTF-8 のデコーダは BOM を取り除かないので、先頭に U+FEFF が残る。</p>\n")
                .append("<div class=\"scroll\"><table class=\"list\">\n<thead><tr><th>項目</th><th>値</th></tr></thead>\n<tbody>\n")
                .append("<tr><td>バイト列</td><td class=\"mono\">").append(hexBytes(bomA)).append("</td></tr>\n")
                .append("<tr><td>UTF-8 で読んだ文字列のコードポイント</td><td class=\"mono\">").append(cps(bomRead)).append("</td></tr>\n")
                .append("<tr><td><code>length()</code></td><td class=\"num\">").append(bomRead.length()).append("</td></tr>\n")
                .append("<tr><td><code>equals(\"A\")</code></td><td class=\"mono\">").append(bomRead.equals("A")).append("</td></tr>\n")
                .append("</tbody>\n</table></div>\n")
                .append("<p>CSV の先頭の列名が一致しない、といった形で表に出る。先頭の U+FEFF を取り除いてから使う。</p>\n");

        // 7. 正規化
        String ga = "が";
        String gaNfd = java.text.Normalizer.normalize(ga, java.text.Normalizer.Form.NFD);
        int[] gaNfdW31j = encodeStrict(W31J, gaNfd);
        h.append("<h3>7. 見た目が同じでもコードポイントが違う（正規化）</h3>\n")
                .append("<p>Unicode では「が」を 1 つのコードポイントでも、「か」＋濁点の 2 つでも表せる。"
                        + "macOS のファイル名などは後者（NFD）になることがある。後者は windows-31j に変換できず、文字列の比較も一致しない。</p>\n")
                .append("<div class=\"scroll\"><table class=\"list\">\n<thead><tr><th>形</th><th>コードポイント</th><th>windows-31j</th>"
                        + "</tr></thead>\n<tbody>\n")
                .append("<tr><td>NFC（合成済み）</td><td class=\"mono\">").append(cps(ga)).append("</td><td class=\"mono\">")
                .append(hexBytes(encodeStrict(W31J, ga))).append("</td></tr>\n")
                .append("<tr><td>NFD（分解）</td><td class=\"mono\">").append(cps(gaNfd)).append("</td><td>")
                .append(gaNfdW31j == null ? "変換できない" : "<span class=\"mono\">" + hexBytes(gaNfdW31j) + "</span>").append("</td></tr>\n")
                .append("</tbody>\n</table></div>\n")
                .append("<p>変換や比較の前に <code>Normalizer.normalize(s, Normalizer.Form.NFC)</code> でそろえる。"
                        + "ただし NFKC は「ｱ」を「ア」に、「①」を「1」にするなど文字そのものを変えるので、目的を確かめてから使う。</p>\n");

        // 8. 5C 問題
        h.append("<h3>8. 2 バイト目の 5C（\\）に注意する</h3>\n")
                .append("<p>Shift_JIS 系では「表」が <span class=\"mono\">").append(bytesOf("表", W31J))
                .append("</span> のように、2 バイト目に <code>\\</code> と同じ 5C が来る文字がある。"
                        + "バイト列のまま <code>\\</code> を探したり、エスケープ文字として扱ったりする処理（古い C のプログラム・"
                        + "CSV や SQL の手作りの処理）は、文字の途中を誤って拾う。文字列にデコードしてから処理する。</p>\n");

        h.append("""
                <h3 id="convert-check">変換するときの確認項目</h3>
                <ul>
                <li>読む側・書く側の両方で、符号化方式を明示しているか</li>
                <li>相手の「Shift_JIS」は Shift_JIS か windows-31j か（Windows 由来ならふつう windows-31j）</li>
                <li>変換できない文字を、例外・ログ・置き換え表のどれで扱うか決めているか（黙って <code>?</code> にしない）</li>
                <li>長さの上限がバイト数か文字数か。変換後のバイト数で収まるか</li>
                <li>UTF-8 の BOM の有無と、読むときに取り除くか</li>
                <li>Unicode の正規化（NFC / NFD）をそろえているか</li>
                <li>BMP の外の文字（サロゲートペア）や異体字セレクタを、文字数の数え方や切り詰めで壊していないか</li>
                </ul>
                </section>
                """);
    }

    // ---- 文字集合と符号化方式 ----

    /** 文字集合 × 符号化方式の対応表の 1 行。cells は SCHEME_SETS と同じ順。 */
    record Scheme(String name, String javaName, String[] cells, String bytes, String asciiInTrail) {
    }

    private static final List<String> SCHEME_SETS = List.of(
            "ASCII", "JIS X 0201\nローマ字", "JIS X 0201\nカタカナ", "JIS X 0208", "JIS X 0212", "JIS X 0213",
            "Windows\nの拡張", "Unicode");

    /** 規格の定義なので手で書く。○ は直接扱う、(○) は Unicode に含まれるので表せる。 */
    private static final List<Scheme> SCHEMES = List.of(
            new Scheme("US-ASCII", "US-ASCII",
                    new String[] {"○", "", "", "", "", "", "", ""}, "1", "—"),
            new Scheme("ISO-2022-JP（JIS コード）", "ISO-2022-JP",
                    new String[] {"○", "○", "△", "○", "", "", "", ""}, "1〜2 ※", "出る"),
            new Scheme("EUC-JP", "EUC-JP",
                    new String[] {"○", "", "○", "○", "○", "", "", ""}, "1〜3", "出ない"),
            new Scheme("Shift_JIS", "Shift_JIS",
                    new String[] {"△", "○", "○", "○", "", "", "", ""}, "1〜2", "出る"),
            new Scheme("windows-31j（CP932）", "windows-31j",
                    new String[] {"○", "", "○", "○", "", "", "○", ""}, "1〜2", "出る"),
            new Scheme("Shift_JIS-2004", "x-SJIS_0213",
                    new String[] {"△", "○", "○", "○", "", "○", "", ""}, "1〜2", "出る"),
            new Scheme("EUC-JIS-2004", "（JDK にない）",
                    new String[] {"○", "", "○", "○", "", "○", "", ""}, "1〜3", "出ない"),
            new Scheme("UTF-8", "UTF-8",
                    new String[] {"○", "(○)", "(○)", "(○)", "(○)", "(○)", "(○)", "○"}, "1〜4", "出ない"),
            new Scheme("UTF-16", "UTF-16 / UTF-16BE / UTF-16LE",
                    new String[] {"(○)", "(○)", "(○)", "(○)", "(○)", "(○)", "(○)", "○"}, "2 か 4", "出る"),
            new Scheme("UTF-32", "UTF-32 / UTF-32BE / UTF-32LE",
                    new String[] {"(○)", "(○)", "(○)", "(○)", "(○)", "(○)", "(○)", "○"}, "4", "出る"));

    /** バイト列を比べる文字と、どの文字集合に入っているかの説明。 */
    private static final List<String[]> SCHEME_SAMPLES = List.of(
            new String[] {"A", "ASCII"},
            new String[] {"ｱ", "JIS X 0201 カタカナ（半角カナ）"},
            new String[] {"漢", "JIS X 0208 第 1 水準"},
            new String[] {"表", "JIS X 0208 第 1 水準。Shift_JIS 系では 2 バイト目が 5C（\\ と同じ値）になる"},
            new String[] {"丂", "JIS X 0212（補助漢字）・JIS X 0213 第 4 水準"},
            new String[] {"①", "NEC 特殊文字・JIS X 0213"},
            new String[] {"髙", "IBM 拡張文字"},
            new String[] {"𠀋", "JIS X 0213 第 3 水準。Unicode では BMP の外"},
            new String[] {"🍣", "Unicode のみ"});

    private static final List<String> SAMPLE_CHARSETS = List.of(
            "US-ASCII", "ISO-2022-JP", "EUC-JP", "Shift_JIS", "windows-31j", "x-SJIS_0213", "UTF-8", "UTF-16BE",
            "UTF-32BE");

    /** s を厳密にエンコードしたバイト列。表せなければ null。 */
    private static int[] encodeStrict(Charset cs, String s) {
        try {
            ByteBuffer b = cs.newEncoder().encode(java.nio.CharBuffer.wrap(s));
            byte[] out = new byte[b.remaining()];
            b.get(out);
            return toInts(out);
        } catch (CharacterCodingException e) {
            return null;
        }
    }

    /** 文字集合・符号化方式・文字コードという言葉の整理。例のバイト列は JDK で求める。 */
    private static void renderBasics(StringBuilder h) {
        String kan = "漢";
        int[] kanSjis = encodeStrict(SJIS, kan);
        int[] kanKuten = kuten(kanSjis[0], kanSjis[1]);
        String kanKutenText = kanKuten[0] + " 区 " + kanKuten[1] + " 点";

        h.append("""
                <section id="basics">
                <h2>文字集合・符号化方式・文字コード</h2>
                <p>コンピュータは文字をそのまま覚えられないので、文字をバイト列にして保存・送信する。
                その変換は 2 段に分けて考えると見通しがよい。</p>
                <ol>
                <li><b>文字 → 番号</b>：どの文字を扱い、それぞれに何番を振るかを決める。これが<b>文字集合</b></li>
                <li><b>番号 → バイト列</b>：振った番号を、実際のバイトの並びにする規則を決める。これが<b>符号化方式</b></li>
                </ol>
                <p>「文字コード」は、この 2 つをはっきり分けずに指す日常の言葉である。</p>
                <h3>文字集合</h3>
                <p>扱う文字の範囲と、各文字の番号を決めたもの。JIS の用語では<b>符号化文字集合</b>
                （coded character set）と呼ぶ。番号の振り方は文字集合ごとに違う。</p>
                <ul>
                <li><b>ASCII</b>：英数字と記号の 128 文字。番号は 0〜127</li>
                <li><b>JIS X 0201</b>：ASCII とほぼ同じローマ字と、半角カタカナ。番号は 0〜255 の 1 バイト</li>
                <li><b>JIS X 0208</b>：ひらがな・カタカナ・漢字など。94 × 94 のマスに並べ、<b>区点番号</b>（何区の何点か）で表す</li>
                <li><b>JIS X 0212・JIS X 0213</b>：JIS X 0208 に入らなかった漢字を足したもの</li>
                <li><b>Unicode</b>：世界中の文字を 1 つにまとめたもの。番号は<b>コードポイント</b>（U+0000〜U+10FFFF）</li>
                </ul>
                <p>文字集合に入っていない文字は、その文字集合を使う限りどう工夫しても表せない。
                windows-31j で 🍣 が <code>?</code> になるのはこのためである。</p>
                <h3>符号化方式</h3>
                <p>文字集合の番号をバイト列にする規則。JIS の用語では<b>文字符号化方式</b>（character encoding scheme）と呼ぶ。
                同じ文字集合でも、符号化方式が違えばバイト列が変わる。主な作りは 3 通りある。</p>
                <ul>
                <li><b>固定長</b>：どの文字も同じバイト数にする。UTF-32 は常に 4 バイト</li>
                <li><b>可変長</b>：1 バイト目の値で、その文字が何バイトかを判断する。Shift_JIS・EUC-JP・UTF-8・UTF-16</li>
                <li><b>切り替え式</b>：エスケープシーケンスで「ここから先はどの文字集合か」を切り替える。ISO-2022-JP</li>
                </ul>
                <p>1 つの符号化方式が、複数の文字集合を組み合わせて使うことも多い。
                Shift_JIS は 1 バイト文字に JIS X 0201 を、2 バイト文字に JIS X 0208 を使う。</p>
                """);

        h.append("<h3>例：「漢」が文字集合と符号化方式を経てバイト列になるまで</h3>\n")
                .append("<div class=\"scroll\"><table class=\"list\">\n<thead><tr><th>文字</th><th>文字集合</th><th>番号</th>"
                        + "<th>符号化方式</th><th>バイト列</th></tr></thead>\n<tbody>\n");
        String[][] flow = {
                {"JIS X 0208", kanKutenText, "Shift_JIS"},
                {"JIS X 0208", kanKutenText, "EUC-JP"},
                {"JIS X 0208", kanKutenText, "ISO-2022-JP"},
                {"Unicode", "U+" + cp(kan), "UTF-8"},
                {"Unicode", "U+" + cp(kan), "UTF-16BE"},
                {"Unicode", "U+" + cp(kan), "UTF-32BE"}};
        for (String[] f : flow) {
            h.append("<tr><td class=\"glyph\">").append(kan).append("</td><td>").append(f[0])
                    .append("</td><td class=\"mono\">").append(f[1]).append("</td><td>").append(f[2])
                    .append("</td><td class=\"mono bytes\">").append(hexBytes(encodeStrict(Charset.forName(f[2]), kan)))
                    .append("</td></tr>\n");
        }
        h.append("</tbody>\n</table></div>\n")
                .append("<p class=\"note\">番号が同じでも、符号化方式によってバイト列が変わる。"
                        + "ISO-2022-JP のバイト列の前後にある <span class=\"mono\">1B 24 42</span> と "
                        + "<span class=\"mono\">1B 28 42</span> は、文字集合を切り替えるエスケープシーケンス。</p>\n");

        // 文字化けの例：UTF-8 で書いたバイト列を windows-31j で読む
        String word = "文字";
        byte[] utf8 = word.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        String garbled = new String(utf8, W31J);
        h.append("""
                <h3>文字コード</h3>
                <p>「文字コード」は日常の言葉で、文脈によって次のどれかを指す。</p>
                <ul>
                <li>文字集合のこと（「JIS X 0208 という文字コード」）</li>
                <li>符号化方式のこと（「UTF-8 で保存する」）</li>
                <li>1 文字ごとの番号やバイト列のこと（「あの文字コードは 3042」）</li>
                </ul>
                <p>実際によく目にする名前（Shift_JIS・EUC-JP・UTF-8 など）は<b>符号化方式の名前</b>で、
                どの文字集合を使うかもその名前で決まる。HTML の <code>&lt;meta charset&gt;</code>、HTTP の <code>charset=</code>、
                Java の <code>Charset</code> が指定しているのも符号化方式である。</p>
                <h3>なぜ区別するのか</h3>
                <p>文字の問題は、たいていどちらの段で起きたかで原因と対策が分かれる。</p>
                <ul>
                """);
        h.append("<li><b>符号化方式の取り違え（文字化け）</b>：書いたときと違う符号化方式で読むと、文字集合に入っている文字でも化ける。"
                        + "「").append(word).append("」を UTF-8 で書いた <span class=\"mono\">").append(hexBytes(toInts(utf8)))
                .append("</span> を windows-31j で読むと「").append(esc(garbled)).append("」になる（� は文字にならなかったバイト）。"
                        + "対策は、読む側と書く側で符号化方式をそろえること</li>\n")
                .append("<li><b>文字集合に入っていない（表せない）</b>：符号化方式が正しくても、文字集合にない文字は書けない。"
                        + "「🍣」を windows-31j に変換すると <span class=\"mono\">").append(hexBytes(toInts("🍣".getBytes(W31J))))
                .append("</span>（<code>?</code>）になる。対策は、その文字を含む文字集合（Unicode）の符号化方式に変えること</li>\n")
                .append("</ul>\n</section>\n");
    }

    // ---- Java の文字と Unicode ----

    /** 文字数の数え方を比べる文字列と、その説明。 */
    private static final List<String[]> JAVA_SAMPLES = List.of(
            new String[] {"A", "ASCII"},
            new String[] {"ｱ", "半角カナ"},
            new String[] {"漢", "BMP の漢字"},
            new String[] {"𠮷", "BMP の外の漢字"},
            new String[] {"🍣", "BMP の外の絵文字"},
            new String[] {"が", "「か」＋結合用の濁点（NFD の「が」）"},
            new String[] {"葛󠄀", "「葛」＋異体字セレクタ U+E0100"},
            new String[] {"👨‍👩‍👧", "3 つの絵文字を ZWJ（U+200D）でつないだもの"});

    /** Charset.forName に渡す名前の例と、その名前について知っておくこと。 */
    private static final List<String[]> JAVA_NAMES = List.of(
            new String[] {"Shift_JIS", "JIS X 0208 だけの Shift_JIS"},
            new String[] {"SJIS", "Shift_JIS の別名。windows-31j ではない"},
            new String[] {"windows-31j", "Windows の拡張を含む Shift_JIS"},
            new String[] {"MS932", "windows-31j の別名"},
            new String[] {"CP943C", "IBM の Shift_JIS。windows-31j とは変換表が違う"},
            new String[] {"EUC-JP", ""},
            new String[] {"UTF8", "UTF-8 の別名"},
            new String[] {"UTF-16", "読むときは BOM で順序を決め、書くときは BOM（FE FF）を付ける"});

    private static String utf16Units(String s) {
        StringBuilder sb = new StringBuilder();
        for (char c : s.toCharArray()) {
            sb.append(sb.isEmpty() ? "" : " ").append(String.format("%04X", (int) c));
        }
        return sb.toString();
    }

    private static String sysProp(String key) {
        String v = System.getProperty(key);
        return v == null ? "（未設定）" : v;
    }

    /** Java の String・char・Charset と、文字集合・符号化方式の関係。例の値はすべて JDK で求める。 */
    private static void renderJava(StringBuilder h) {
        Charset utf8 = java.nio.charset.StandardCharsets.UTF_8;
        int[] wave = {0x81, 0x60};

        h.append("""
                <section id="java">
                <h2>Java の文字と Unicode</h2>
                <p>Java では、文字列（<code>String</code>・<code>char</code>）の文字集合は Unicode に決まっている。
                Shift_JIS のファイルを読んでも、メモリの上では Unicode の文字列になる。
                符号化方式（<code>Charset</code>）が関わるのは、ファイル・通信・データベースなど外とバイト列をやり取りする境目だけである。</p>
                <h3>中は Unicode、外はバイト列</h3>
                <div class="scroll"><table class="list">
                <thead><tr><th>段</th><th>中身</th><th>主な API</th></tr></thead>
                <tbody>
                <tr><td>外から読む（デコード）</td><td>バイト列 → Unicode の文字列</td>
                <td><code>new String(bytes, cs)</code>・<code>InputStreamReader</code>・<code>Files.readString(path, cs)</code></td></tr>
                <tr><td>中で処理する</td><td>Unicode の文字列（<code>String</code>・<code>char</code>・コードポイントの <code>int</code>）</td>
                <td><code>String</code>・<code>StringBuilder</code>・<code>Character</code></td></tr>
                <tr><td>外へ書く（エンコード）</td><td>Unicode の文字列 → バイト列</td>
                <td><code>s.getBytes(cs)</code>・<code>OutputStreamWriter</code>・<code>Files.writeString(path, s, cs)</code></td></tr>
                </tbody>
                </table></div>
                <p>つまり Java の <code>Charset</code> は、どれも「Unicode ⇔ ある符号化方式のバイト列」の変換表である。
                Shift_JIS から EUC-JP への変換も、直接ではなく Unicode を経由して行う（デコードしてからエンコードする）。
                そのため、各 <code>Charset</code> がどの Unicode の文字に対応付けているかで結果が決まる。</p>
                """);
        h.append("<p>たとえば同じバイト列 <span class=\"mono\">").append(hexBytes(wave))
                .append("</span> を、Shift_JIS は <span class=\"glyph\">").append(esc(decodeLenient(wave, SJIS)))
                .append("</span>（U+").append(cp(decodeLenient(wave, SJIS))).append("）、windows-31j は <span class=\"glyph\">")
                .append(esc(decodeLenient(wave, W31J))).append("</span>（U+").append(cp(decodeLenient(wave, W31J)))
                .append("）にデコードする。見た目は似ていても Unicode では別の文字なので、"
                        + "比較や再エンコードの結果が変わる（<a href=\"#sjis-w31j\">Shift_JIS と windows-31j の違い</a>）。</p>\n");

        // char は UTF-16 のコード単位
        h.append("""
                <h3>char は 1 文字ではなく UTF-16 の 1 単位</h3>
                <p><code>char</code> は 16 ビットで、Unicode の文字そのものではなく <b>UTF-16 のコード単位</b>を 1 つ持つ。
                <code>String</code> は <code>char</code> の並びとして振る舞う。BMP（U+0000〜U+FFFF）の文字は <code>char</code> 1 つで表せるが、
                BMP の外（U+10000 以上）の文字は<b>サロゲートペア</b>と呼ぶ 2 つの <code>char</code> になる。
                <code>length()</code> が返すのは文字数ではなく <code>char</code> の数である。</p>
                """);
        h.append("<div class=\"scroll\"><table class=\"list\">\n<thead><tr><th>文字列</th><th>中身</th><th>コードポイント</th>"
                + "<th>char の値（UTF-16）</th><th class=\"c\">length()</th><th class=\"c\">codePointCount</th>"
                + "<th class=\"c\">見た目の文字数（\\X）</th><th class=\"c\">UTF-8</th><th class=\"c\">windows-31j</th></tr></thead>\n<tbody>\n");
        java.util.regex.Pattern grapheme = java.util.regex.Pattern.compile("\\X");
        for (String[] sample : JAVA_SAMPLES) {
            String s = sample[0];
            int[] w = encodeStrict(W31J, s);
            h.append("<tr><td class=\"glyph\">").append(esc(s)).append("</td><td>").append(esc(sample[1]))
                    .append("</td><td class=\"mono\">").append(cps(s)).append("</td><td class=\"mono\">").append(utf16Units(s))
                    .append("</td><td class=\"num\">").append(s.length()).append("</td><td class=\"num\">")
                    .append(s.codePointCount(0, s.length())).append("</td><td class=\"num\">")
                    .append(grapheme.matcher(s).results().count()).append("</td><td class=\"num\">")
                    .append(s.getBytes(utf8).length).append("</td><td class=\"num\">")
                    .append(w == null ? "表せない" : String.valueOf(w.length)).append("</td></tr>\n");
        }
        h.append("</tbody>\n</table></div>\n")
                .append("<ul class=\"note\">\n<li>UTF-8・windows-31j の列はバイト数。windows-31j は厳密にエンコードし、1 文字でも表せなければ「表せない」とした</li>\n"
                        + "<li>見た目の文字数は、正規表現 <code>\\X</code>（書記素クラスタ）が何回一致するかで数えた。"
                        + "コードポイントで数えても、結合文字・異体字セレクタ・ZWJ でつないだ絵文字は 1 文字にならない</li>\n"
                        + "<li>JDK 9 からは、中身が Latin-1 だけの文字列を 1 文字 1 バイトで持つ（Compact Strings）。"
                        + "メモリ上の持ち方が変わるだけで、API から見える動作は UTF-16 のまま</li>\n</ul>\n");

        // コードポイント単位の API
        String sushi = "🍣";
        String half = sushi.substring(0, 1);
        h.append("""
                <h3>文字単位で扱う API</h3>
                <p><code>char</code> 単位の API は、BMP の外の文字をサロゲートの片方ずつに分けてしまう。
                文字（コードポイント）単位で扱うときは、<code>int</code> でコードポイントを受け渡す API を使う。</p>
                <div class="scroll"><table class="list">
                <thead><tr><th>やりたいこと</th><th>char 単位（BMP の外で壊れる）</th><th>コードポイント単位</th></tr></thead>
                <tbody>
                <tr><td>文字数を数える</td><td><code>s.length()</code></td><td><code>s.codePointCount(0, s.length())</code></td></tr>
                <tr><td>1 文字ずつ見る</td><td><code>s.charAt(i)</code>・<code>s.chars()</code></td>
                <td><code>s.codePointAt(i)</code> と <code>Character.charCount(cp)</code>・<code>s.codePoints()</code></td></tr>
                <tr><td>n 文字目の位置を求める</td><td><code>n</code> をそのまま使う</td><td><code>s.offsetByCodePoints(0, n)</code></td></tr>
                <tr><td>番号から文字列を作る</td><td><code>String.valueOf((char) c)</code></td>
                <td><code>Character.toString(cp)</code>・<code>sb.appendCodePoint(cp)</code></td></tr>
                <tr><td>文字の種類を調べる</td><td><code>Character.isLetter(char)</code></td><td><code>Character.isLetter(int)</code>・<code>Character.UnicodeBlock.of(int)</code></td></tr>
                </tbody>
                </table></div>
                """);
        h.append("<p>たとえば <code>\"").append(sushi).append("\".substring(0, 1)</code> はサロゲートの片方（<span class=\"mono\">")
                .append(utf16Units(half)).append("</span>）だけの不正な文字列になり、UTF-8 へ <code>getBytes</code> すると <span class=\"mono\">")
                .append(hexBytes(toInts(half.getBytes(utf8)))).append("</span>（<code>?</code>）になる。"
                        + "<code>char c = '").append(sushi).append("';</code> は 1 つの <code>char</code> に入らないのでコンパイルできない。"
                        + "見た目の 1 文字で区切りたいときは <code>BreakIterator.getCharacterInstance()</code> か正規表現の <code>\\X</code> を使う。</p>\n");

        // Charset の名前
        h.append("""
                <h3>Java の Charset の名前</h3>
                <p><code>Charset.forName</code> は正式名のほかに別名も受け付ける。別名が指す実体は、<code>name()</code> で確かめられる。
                「SJIS」と書くと windows-31j ではなく Shift_JIS になる点に注意する。</p>
                """);
        h.append("<div class=\"scroll\"><table class=\"list\">\n<thead><tr><th>forName に渡す名前</th><th>name()（実体）</th>"
                + "<th>備考</th></tr></thead>\n<tbody>\n");
        for (String[] n : JAVA_NAMES) {
            String actual = Charset.isSupported(n[0]) ? Charset.forName(n[0]).name() : "（この JDK にない）";
            h.append("<tr><td class=\"mono\">").append(esc(n[0])).append("</td><td class=\"mono\">").append(esc(actual))
                    .append("</td><td>").append(esc(n[1])).append("</td></tr>\n");
        }
        h.append("</tbody>\n</table></div>\n")
                .append("<p class=\"note\">すべての JDK にあると保証されているのは、<code>StandardCharsets</code> にある US-ASCII・ISO-8859-1・UTF-8・"
                        + "UTF-16・UTF-16BE・UTF-16LE だけ。Shift_JIS・windows-31j・EUC-JP は "
                        + "<code>jdk.charsets</code> モジュールにあるので、<code>jlink</code> で最小の実行環境を作るとこのモジュールを含めない限り使えない。</p>\n");

        // 既定値
        h.append("""
                <h3>符号化方式を省略したときに使われる値</h3>
                <p>符号化方式を省略できる API では、次の値が使われる。どれも実行環境によって変わりうるので、外とやり取りするときは明示する。</p>
                <div class="scroll"><table class="list">
                <thead><tr><th>値</th><th>何に使われるか</th><th>この環境の値</th></tr></thead>
                <tbody>
                """);
        String[][] props = {
                {"Charset.defaultCharset()（file.encoding）",
                        "<code>new String(bytes)</code>・<code>getBytes()</code>・<code>FileReader</code>・<code>FileWriter</code>・<code>Scanner</code> など。JDK 18 から UTF-8（JEP 400）",
                        Charset.defaultCharset().name()},
                {"native.encoding", "OS 本来の符号化方式（JDK 17 から）。日本語の Windows では MS932 になる。JDK 18 より前の既定値に当たる",
                        sysProp("native.encoding")},
                {"stdout.encoding", "<code>System.out</code> が端末へ書くときの符号化方式（JDK 19 から）", sysProp("stdout.encoding")},
                {"sun.jnu.encoding", "ファイル名・コマンドライン引数・環境変数", sysProp("sun.jnu.encoding")},
                {"javac -encoding", "ソースファイル。Maven では <code>project.build.sourceEncoding</code>。JDK 18 から既定は UTF-8", "—"}};
        for (String[] p : props) {
            h.append("<tr><td class=\"mono\">").append(esc(p[0])).append("</td><td>").append(p[1]).append("</td><td class=\"mono\">")
                    .append(esc(p[2])).append("</td></tr>\n");
        }
        h.append("</tbody>\n</table></div>\n")
                .append("<p class=\"note\"><code>Files.readString</code>・<code>Files.newBufferedReader</code> など <code>java.nio.file.Files</code> の"
                        + "メソッドは、省略すると JDK の版によらず UTF-8 を使う。</p>\n");

        // ソースコードと修正 UTF-8
        byte[] modified;
        try {
            java.io.ByteArrayOutputStream bo = new java.io.ByteArrayOutputStream();
            new java.io.DataOutputStream(bo).writeUTF(sushi);
            modified = java.util.Arrays.copyOfRange(bo.toByteArray(), 2, bo.size());
        } catch (IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
        String escaped = utf16Units(sushi).replaceAll("(\\S+)", "\\\\u$1").replace(" ", "");
        h.append("<h3>ソースコードとクラスファイルの中の文字</h3>\n<ul>\n")
                .append("<li>ソースでは <code>\\uXXXX</code> で UTF-16 のコード単位を書ける。BMP の外の文字は 2 つ並べ、「")
                .append(sushi).append("」は <code>\"").append(escaped).append("\"</code> になる</li>\n")
                .append("<li><code>\\uXXXX</code> はコンパイルの最初の段で文字に置き換わる。コメントの中の <code>\\u000A</code> も改行になり、"
                        + "その後ろがコードとして扱われるので注意する</li>\n")
                .append("<li>クラスファイルの文字列定数・<code>DataOutputStream.writeUTF</code>・JNI は、UTF-8 に似た<b>修正 UTF-8</b>（modified UTF-8）を使う。"
                        + "U+0000 を <span class=\"mono\">C0 80</span> にし、BMP の外の文字はサロゲートを 1 つずつ 3 バイトにする。「")
                .append(sushi).append("」は UTF-8 では <span class=\"mono\">").append(utf8Hex(sushi))
                .append("</span>、修正 UTF-8 では <span class=\"mono\">").append(hexBytes(toInts(modified)))
                .append("</span>。ほかのプログラムと UTF-8 でやり取りする用途には使わない</li>\n")
                .append("</ul>\n</section>\n");
    }

    private static void renderSchemes(StringBuilder h) {
        h.append("""
                <section id="schemes">
                <h2>文字集合と符号化方式の対応表</h2>
                <p>前の章の整理をもとに、主な符号化方式がどの文字集合を使うかをまとめる。第 2 部以降で、このうち Shift_JIS 系と UTF-8 を詳しく見る。</p>
                <h3>どの符号化方式がどの文字集合を扱うか</h3>
                """);
        h.append("<div class=\"scroll\"><table class=\"list\">\n<thead><tr><th>符号化方式</th>");
        SCHEME_SETS.forEach(s -> h.append("<th class=\"c\">").append(esc(s).replace("\n", "<br>")).append("</th>"));
        h.append("</tr></thead>\n<tbody>\n");
        for (Scheme s : SCHEMES) {
            h.append("<tr><td>").append(esc(s.name)).append("</td>");
            for (String c : s.cells) {
                h.append(c.isEmpty() ? "<td class=\"x\">—</td>" : "<td class=\"c\">" + esc(c) + "</td>");
            }
            h.append("</tr>\n");
        }
        h.append("</tbody>\n</table></div>\n");

        // 性質は別の表に分け、上の表の列を文字集合だけにする
        h.append("<h3>符号化方式の性質</h3>\n<div class=\"scroll\"><table class=\"list\">\n<thead><tr><th>符号化方式</th>"
                + "<th>1 文字のバイト数</th><th>ASCII の値が 2 バイト目以降に出るか</th><th>Java の名前</th></tr></thead>\n<tbody>\n");
        for (Scheme s : SCHEMES) {
            h.append("<tr><td>").append(esc(s.name)).append("</td><td>").append(esc(s.bytes)).append("</td><td>")
                    .append(esc(s.asciiInTrail)).append("</td><td class=\"mono\">").append(esc(s.javaName)).append("</td></tr>\n");
        }
        h.append("</tbody>\n</table></div>\n");
        h.append("""
                <ul class="note">
                <li>○：その文字集合を直接使う。(○)：Unicode がその文字集合の文字を含んでいるので表せる。—：表せない</li>
                <li>Shift_JIS 系の 1 バイト部分は、規格上は JIS X 0201 ローマ字（5C が ¥、7E が ‾）。
                JDK の Shift_JIS と x-SJIS_0213 は 5C と 7E を ASCII の \\ と ~ として扱うので △ とした。windows-31j は 1 バイト部分が ASCII</li>
                <li>※ ISO-2022-JP は 1 文字が 1〜2 バイトで、文字集合を切り替えるたびにエスケープシーケンス（3 バイト）が加わる</li>
                <li>ISO-2022-JP の規格（RFC 1468）は半角カナを含まない。JDK は ESC ( I で切り替えて読み書きできるので △ とした</li>
                <li>JIS X 0213 は JIS X 0208 の文字をすべて含み、第 3・第 4 水準などを加えたもの。
                Windows の拡張は NEC 特殊文字・NEC 選定 IBM 拡張文字・IBM 拡張文字で、第 2 部の「2 バイト文字」の章に詳しい</li>
                <li>「ASCII の値が 2 バイト目以降に出る」方式では、バイト単位で <code>\\</code> や <code>/</code> を探す処理が
                文字の途中を誤って拾うことがある（下の表の「表」を参照）</li>
                </ul>
                <h3>同じ文字を各符号化方式で書いたバイト列</h3>
                <p>表の値は、JDK の <code>CharsetEncoder</code> で 1 文字ずつ厳密にエンコードした結果。× は表せないことを示す。
                ISO-2022-JP のバイト列には、文字集合を切り替えるエスケープシーケンス（<span class="mono">1B 24 42</span> で JIS X 0208 へ、
                <span class="mono">1B 28 42</span> で ASCII へ戻す）が含まれる。</p>
                """);
        h.append("<div class=\"scroll\"><table class=\"list\">\n<thead><tr><th>文字</th><th>どの文字集合の文字か</th>"
                + "<th>コードポイント</th>");
        SAMPLE_CHARSETS.forEach(n -> h.append("<th>").append(esc(n)).append("</th>"));
        h.append("</tr></thead>\n<tbody>\n");
        for (String[] sample : SCHEME_SAMPLES) {
            String s = sample[0];
            h.append("<tr><td class=\"glyph\">").append(esc(s)).append("</td><td>").append(esc(sample[1]))
                    .append("</td><td class=\"mono\">U+").append(cp(s)).append("</td>");
            for (String n : SAMPLE_CHARSETS) {
                int[] b = encodeStrict(Charset.forName(n), s);
                h.append(b == null ? "<td class=\"x\">×</td>"
                        : "<td class=\"mono bytes\">" + hexBytes(b) + "</td>");
            }
            h.append("</tr>\n");
        }
        h.append("</tbody>\n</table></div>\n</section>\n");
    }

    /** 1 バイト文字・2 バイト文字の表の色分け。1 バイト目の章の最後の節として出す。 */
    private static void renderLegend(StringBuilder h, List<Cell> singleByte, Map<Integer, List<Cell>> doubleByte) {
        Map<Cat, Integer> counts = new EnumMap<>(Cat.class);
        singleByte.forEach(c -> counts.merge(c.cat, 1, Integer::sum));
        doubleByte.values().forEach(cells -> cells.forEach(c -> counts.merge(c.cat, 1, Integer::sum)));

        h.append("<h3 id=\"legend\">表の色分け</h3>\n")
                .append("<p>この先の「1 バイト文字」「2 バイト文字」の表は、Shift_JIS と windows-31j で読んだ結果を比べて、次のように色分けしている。</p>\n")
                .append("<table class=\"legend\">\n")
                .append("<thead><tr><th>色</th><th>分類</th><th>意味</th><th>数</th></tr></thead>\n<tbody>\n");
        for (Cat cat : Cat.values()) {
            if (cat == Cat.NONE) {
                continue;
            }
            h.append("<tr><td><span class=\"swatch ").append(cat.css).append("\"></span></td><td>")
                    .append(cat.label).append("</td><td>").append(cat.description).append("</td><td class=\"num\">")
                    .append(counts.getOrDefault(cat, 0)).append("</td></tr>\n");
        }
        h.append("</tbody>\n</table>\n");
        h.append("""
                <p class="note">マスにカーソルを合わせると、バイト列・区点番号・Unicode の名前・UTF-8 のバイト列が出る。
                「対応先が違う」マスは <span class="mono">Shift_JIS → windows-31j</span> の順に 2 つの文字を並べている。</p>
                <label class="toggle"><input type="checkbox" id="only-diff"> 共通の文字を薄くして、差分だけを目立たせる</label>
                """);
    }

    /** 1 バイト目 00〜FF が何に使われているかの全体図。 */
    private static void renderOverview(StringBuilder h, List<Cell> singleByte, Map<Integer, List<Cell>> doubleByte) {
        h.append("<section id=\"overview\">\n<h2>Shift_JIS 系の 1 バイト目</h2>\n")
                .append("<p>Shift_JIS と windows-31j は、1 バイト文字に JIS X 0201 を、2 バイト文字に前の章の区点を使う符号化方式。"
                        + "1 バイト目の値だけで、1 バイト文字か、2 バイト文字の始まりかが決まる。</p>\n")
                .append("<h3>1 バイト目の使い道</h3>\n")
                .append("<p>2 バイト文字のマスを押すと、その 1 バイト目の表に移動する。</p>\n")
                .append("<div class=\"scroll\"><table class=\"grid overview\">\n");
        appendHeaderRow(h);
        for (int hi = 0; hi < 16; hi++) {
            h.append("<tr><th>").append(hex1(hi)).append("x</th>");
            for (int lo = 0; lo < 16; lo++) {
                int b = hi * 16 + lo;
                Cell single = singleByte.get(b);
                List<Cell> cells = doubleByte.get(b);
                if (cells != null) {
                    Cat cat = overviewCat(cells);
                    String css = cat == Cat.SAME ? "jis" : cat.css;
                    h.append("<td class=\"").append(css).append("\" title=\"").append(hex2(b))
                            .append("：2 バイト文字の 1 バイト目（").append(esc(leadLabel(b))).append("）\"><a href=\"#lead-")
                            .append(hex2(b)).append("\">").append(hex2(b)).append("</a></td>");
                } else {
                    String kind = single.cat == Cat.NONE ? "未定義"
                            : "1 バイト文字 " + describeShort(single);
                    h.append("<td class=\"").append(single.cat == Cat.NONE ? "none" : "one").append("\" title=\"").append(hex2(b))
                            .append("：").append(esc(kind)).append("\">").append(hex2(b)).append("</td>");
                }
            }
            h.append("</tr>\n");
        }
        h.append("</table></div>\n");
        h.append("""
                <ul class="bands">
                <li><span class="swatch one"></span> 00〜7F・A1〜DF：1 バイト文字（ASCII と半角カナ）</li>
                <li><span class="swatch none"></span> 斜線：どちらでも使わない値</li>
                <li><span class="swatch jis"></span> 81〜9F・E0〜EA：JIS X 0208 の文字（ひらがな・カタカナ・漢字など）。Shift_JIS と windows-31j で共通</li>
                <li><span class="swatch nec"></span> 87：NEC 特殊文字（①・Ⅰ・㈱・㎝ など）。JIS X 0208 では空きの 13 区</li>
                <li><span class="swatch necibm"></span> ED〜EE：NEC 選定 IBM 拡張文字。JIS X 0208 では空きの 89〜92 区</li>
                <li><span class="swatch gaiji"></span> F0〜F9：外字。JIS X 0208 の 94 区より外側</li>
                <li><span class="swatch ibm"></span> FA〜FC：IBM 拡張文字（髙・﨑 など）。JIS X 0208 の 94 区より外側</li>
                </ul>
                """);
        renderLegend(h, singleByte, doubleByte);
        h.append("</section>\n");
    }

    /** 全体図での 1 バイト目の色。共通の文字が 1 つでもあれば JIS X 0208 の領域とみなす。 */
    private static Cat overviewCat(List<Cell> cells) {
        if (cells.stream().anyMatch(c -> c.cat == Cat.SAME || c.cat == Cat.DIFF)) {
            return Cat.SAME;
        }
        return cells.stream().map(Cell::cat).filter(c -> c != Cat.NONE).findFirst().orElse(Cat.NONE);
    }

    /** 区の範囲ごとの内容。区点の全体図の色分けに使う。 */
    record Region(int from, int to, String label, String css, String note) {
    }

    private static final List<Region> REGIONS = List.of(
            new Region(1, 2, "記号", "k-sym", "句読点・括弧・算術記号など"),
            new Region(3, 3, "英数字", "k-alnum", "全角の 0〜9・A〜Z・a〜z"),
            new Region(4, 4, "ひらがな", "k-hira", ""),
            new Region(5, 5, "カタカナ", "k-kata", ""),
            new Region(6, 6, "ギリシャ文字", "k-greek", ""),
            new Region(7, 7, "キリル文字", "k-cyr", ""),
            new Region(8, 8, "罫線素片", "k-line", "表の枠を描く線"),
            new Region(9, 12, "空き", "k-empty", ""),
            new Region(13, 13, "NEC 特殊文字", "nec", "windows-31j のみ。①・Ⅰ・㈱・㎝ など"),
            new Region(14, 15, "空き", "k-empty", ""),
            new Region(16, 47, "第 1 水準漢字", "k-kanji1", "よく使う漢字。読み順"),
            new Region(48, 84, "第 2 水準漢字", "k-kanji2", "あまり使わない漢字。部首順"),
            new Region(85, 88, "空き", "k-empty", ""),
            new Region(89, 92, "NEC 選定 IBM 拡張文字", "necibm", "windows-31j のみ"),
            new Region(93, 94, "空き", "k-empty", ""),
            new Region(95, 114, "外字", "gaiji", "windows-31j のみ。利用者が登録する領域"),
            new Region(115, 119, "IBM 拡張文字", "ibm", "windows-31j のみ。髙・﨑 など"),
            new Region(120, 120, "空き", "k-empty", ""));

    /** 区点番号を Shift_JIS 方式の 2 バイトにする。kuten() の逆。 */
    static int[] toSjis(int ku, int ten) {
        int lead = ku <= 62 ? (ku + 257) / 2 : (ku + 385) / 2;
        int trail = ku % 2 == 1 ? ten + 0x3F + (ten >= 64 ? 1 : 0) : ten + 0x9E;
        return new int[] {lead, trail};
    }

    /** 1〜120 区 × 1〜94 点を 1 マス 1 文字で並べた全体図。 */
    private static void renderKutenMap(StringBuilder h, Map<Integer, List<Cell>> doubleByte) {
        Map<Integer, Cell> byBytes = new java.util.HashMap<>();
        doubleByte.values().forEach(cells -> cells.forEach(c -> byBytes.put(c.bytes[0] * 256 + c.bytes[1], c)));

        h.append("<section id=\"kuten\">\n<h2>JIS X 0208 の区点</h2>\n")
                .append("<p>JIS X 0208 は、文字を 94 区 × 94 点のマスに並べた文字集合。各文字の番号は「何区の何点か」（区点番号）で表す。"
                        + "Shift_JIS 系の 2 バイト文字は、この区点番号を計算でバイト列にしたもの。</p>\n")
                .append("<p>下の図は、Shift_JIS 系で表せる 2 バイト文字を区点番号で並べたもの。縦が区（1〜120）、横が点（1〜94）で、1 マスが 1 文字。"
                        + "1〜94 区が JIS X 0208 の範囲で、95 区より先は windows-31j が同じ計算式を延長して使っている。"
                        + "マスにカーソルを合わせると文字が出る。区の番号を押すと、その区の表に移動する。</p>\n");

        // 区の範囲ごとのまとめ表
        h.append("<div class=\"scroll\"><table class=\"list kregions\">\n<thead><tr><th></th><th>区</th><th>1 バイト目</th>"
                + "<th>内容</th><th>Shift_JIS</th><th>windows-31j</th><th>文字数</th></tr></thead>\n<tbody>\n");
        for (Region r : REGIONS) {
            int sjisCount = 0;
            int w31jCount = 0;
            for (int ku = r.from; ku <= r.to; ku++) {
                for (int ten = 1; ten <= 94; ten++) {
                    int[] b = toSjis(ku, ten);
                    Cell c = byBytes.get(b[0] * 256 + b[1]);
                    if (c != null && c.sjis != null) {
                        sjisCount++;
                    }
                    if (c != null && c.w31j != null) {
                        w31jCount++;
                    }
                }
            }
            String leads = hex2(toSjis(r.from, 1)[0]) + (r.from == r.to && r.from % 2 == 1 ? ""
                    : "〜" + hex2(toSjis(r.to, 94)[0]));
            if (hex2(toSjis(r.from, 1)[0]).equals(hex2(toSjis(r.to, 94)[0]))) {
                leads = hex2(toSjis(r.from, 1)[0]);
            }
            String ku = r.from == r.to ? r.from + " 区" : r.from + "〜" + r.to + " 区";
            h.append("<tr class=\"").append(r.css.equals("k-empty") ? "muted" : "").append("\"><td><span class=\"swatch ")
                    .append(r.css).append("\"></span></td><td class=\"mono\">").append(ku).append("</td><td class=\"mono\">")
                    .append(leads).append("</td><td>").append(r.label)
                    .append(r.note.isEmpty() ? "" : "<br><span class=\"note\">" + esc(r.note) + "</span>")
                    .append("</td><td class=\"num\">").append(sjisCount == 0 ? "—" : "○")
                    .append("</td><td class=\"num\">").append(w31jCount == 0 ? "—" : "○")
                    .append("</td><td class=\"num\">").append(w31jCount).append("</td></tr>\n");
        }
        h.append("</tbody>\n</table></div>\n");
        h.append("<p class=\"note\">外字（95〜114 区）は windows-31j では文字として読めるが、決まった字形がない。"
                + "赤い枠のマスは、Shift_JIS と windows-31j で対応先が違う文字。</p>\n");

        // 全体図。区の範囲（区画）ごとに枠で囲み、区画の間に隙間を空ける
        h.append("<div class=\"scroll\"><table class=\"kmap\">\n<tr><th></th>");
        for (int ten = 1; ten <= 94; ten++) {
            h.append("<th>").append(ten == 1 || ten % 10 == 0 ? String.valueOf(ten) : "").append("</th>");
        }
        h.append("<th></th></tr>\n");
        boolean firstRegion = true;
        for (Region r : REGIONS) {
            if (!firstRegion) {
                h.append("<tr class=\"kgap\"><td colspan=\"96\"></td></tr>\n");
            }
            firstRegion = false;
            boolean empty = r.css.equals("k-empty");
            for (int ku = r.from; ku <= r.to; ku++) {
                int lead = toSjis(ku, 1)[0];
                boolean hasTable = doubleByte.containsKey(lead);
                h.append("<tr><th>").append(hasTable ? "<a href=\"#lead-" + hex2(lead) + "\">" + ku + "</a>" : ku).append("</th>");
                for (int ten = 1; ten <= 94; ten++) {
                    int[] b = toSjis(ku, ten);
                    Cell c = byBytes.get(b[0] * 256 + b[1]);
                    String ch = c == null ? null : (c.w31j != null ? c.w31j : c.sjis);

                    StringBuilder cls = new StringBuilder(ch == null ? "e" : r.css);
                    if (ch != null && c.cat == Cat.DIFF) {
                        cls.append(" k-diff");
                    }
                    if (empty) {
                        cls.append(" kx");
                    }
                    if (ku == r.from) {
                        cls.append(" kt");
                    }
                    if (ku == r.to) {
                        cls.append(" kb");
                    }
                    if (ten == 1) {
                        cls.append(" kl");
                    }
                    if (ten == 94) {
                        cls.append(" kr");
                    }
                    h.append("<td class=\"").append(cls).append("\" title=\"").append(ku).append("区").append(ten).append("点　")
                            .append(hexBytes(b)).append("　");
                    if (ch == null) {
                        h.append("（文字なし）");
                    } else if (Character.getType(ch.codePointAt(0)) == Character.PRIVATE_USE) {
                        h.append("（外字）");
                    } else {
                        h.append(esc(ch));
                    }
                    h.append("　").append(esc(r.label)).append("\"></td>");
                }
                if (ku == r.from) {
                    String range = r.from == r.to ? r.from + " 区" : r.from + "〜" + r.to + " 区";
                    h.append("<td class=\"klabel").append(empty ? " kx" : "").append("\" rowspan=\"").append(r.to - r.from + 1)
                            .append("\"><b>").append(esc(r.label)).append("</b> <span>").append(range).append("</span></td>");
                }
                h.append("</tr>\n");
            }
        }
        h.append("</table></div>\n</section>\n");
    }

    /** MJ 文字・MJ+（行政事務標準文字）と、これまでの文字コードとの関係。 */
    private static void renderMj(StringBuilder h, Map<Integer, List<Cell>> doubleByte) {
        // 数値はこのページの表から数える
        long w31jChars = doubleByte.values().stream().flatMap(List::stream)
                .filter(c -> c.w31j != null && c.cat != Cat.GAIJI).count();
        long jisChars = doubleByte.values().stream().flatMap(List::stream)
                .filter(c -> c.sjis != null).count();
        int sjisCells = 188 * 60; // 2 バイト目 188 通り × 1 バイト目 81〜9F・E0〜FC の 60 通り

        // 異体字セレクタの例。葛 + VS17（U+E0100）
        String ivs = "葛" + Character.toString(0xE0100);
        int ivsLength = ivs.length();
        long ivsCodePoints = ivs.codePoints().count();
        String ivsUtf8 = utf8Hex(ivs);

        h.append("<section id=\"mj\">\n<h2>MJ 文字と MJ+（行政事務標準文字）</h2>\n");
        h.append("""
                <p>役所のシステムでは、戸籍や住民票に載る人名・地名の漢字を正確に扱う必要がある。
                JIS X 0208 や windows-31j の数千字では足りないので、行政向けにずっと大きな文字集合が整備されている。</p>
                <h3>MJ 文字（文字情報基盤）</h3>
                <ul>
                <li>行政で使う人名漢字などを集めた、約 6 万字の文字集合。戸籍統一文字や住民基本台帳ネットワーク統一文字を網羅している</li>
                <li>2010 年度に情報処理推進機構（IPA）の事業として始まり、2020 年に一般社団法人 文字情報技術促進協議会へ移管された</li>
                <li>1 文字ごとに MJ 文字図形名（MJ000001 のような番号）が振られ、Unicode のどの文字に当たるかの対応が定められている</li>
                </ul>
                <h3>MJ+（行政事務標準文字）</h3>
                <ul>
                <li>デジタル庁が、地方公共団体の基幹業務システムを標準化するために定めた文字集合。MJ に、MJ では足りなかった約 1 万字を加えた約 7 万字</li>
                <li>MJ を拡張したものなので「+」が付いている</li>
                <li>一般的なフォントファイルに入る字形の数には上限（約 6 万 5 千）があるので、1 つのフォントに収まらず、分割が必要になる</li>
                </ul>
                """);

        h.append("<h3>これまでの文字コードとの比較</h3>\n<div class=\"scroll\"><table class=\"list\">\n<thead><tr>"
                + "<th>文字集合</th><th>文字数</th><th>主な用途</th><th>Shift_JIS 系で表せるか</th></tr></thead>\n<tbody>\n")
                .append("<tr><td>JIS X 0208</td><td class=\"num\">").append(jisChars)
                .append("</td><td>一般の日本語</td><td>表せる（Shift_JIS）</td></tr>\n")
                .append("<tr><td>windows-31j</td><td class=\"num\">").append(w31jChars)
                .append("</td><td>Windows の日本語（外字を除く）</td><td>表せる</td></tr>\n")
                .append("<tr><td>MJ 文字</td><td class=\"num\">約 60,000</td><td>戸籍・住民基本台帳の人名・地名</td>"
                        + "<td>大部分は表せない</td></tr>\n")
                .append("<tr><td>MJ+（行政事務標準文字）</td><td class=\"num\">約 70,000</td><td>地方公共団体の基幹業務システム</td>"
                        + "<td>大部分は表せない</td></tr>\n")
                .append("</tbody>\n</table></div>\n")
                .append("<p class=\"note\">JIS X 0208 と windows-31j の文字数は、このページの表から数えた 2 バイト文字の数。"
                        + "MJ 文字と MJ+ の文字数は公表資料の概数で、版によって変わる。</p>\n");

        h.append("<h3>Shift_JIS 系では入りきらない</h3>\n")
                .append("<p>Shift_JIS 方式で使える 2 バイトの組み合わせは、windows-31j の拡張を含めても ")
                .append(sjisCells).append(" 通り（1 バイト目 60 通り × 2 バイト目 188 通り）しかない。"
                        + "第 2 部の「JIS X 0208 の区点」の図の 120 区 × 94 点がその全部で、6 万〜7 万字はどう並べても収まらない。"
                        + "MJ 文字や MJ+ を扱うシステムは、Unicode（UTF-8 や UTF-16）を使うことが前提になる。</p>\n");

        h.append("<h3>Unicode で表すときの注意</h3>\n<ul>\n")
                .append("<li><b>BMP の外の文字が多い</b>：人名用の珍しい漢字の多くは CJK 統合漢字の拡張領域（U+20000 以降）にある。"
                        + "UTF-8 では 4 バイト、Java の <code>char</code> では 2 つ（サロゲートペア）になる</li>\n")
                .append("<li><b>異体字セレクタ（IVS）を使う文字がある</b>：Unicode では、字形が少し違うだけの漢字は同じ番号にまとめられている。"
                        + "MJ 文字はその違いを区別したいので、基本の文字の後ろに異体字セレクタ（U+E0100〜U+E01EF、面 14）を付けて、"
                        + "どの字形かを指定することがある</li>\n")
                .append("</ul>\n");

        h.append("<p>異体字セレクタを付けた例（「葛」＋ U+E0100）。見た目は 1 文字だが、中身は 2 つのコードポイントになる。</p>\n")
                .append("<div class=\"scroll\"><table class=\"list\">\n<thead><tr><th>項目</th><th>値</th></tr></thead>\n<tbody>\n")
                .append("<tr><td>コードポイント</td><td class=\"mono\">U+845B U+E0100</td></tr>\n")
                .append("<tr><td>コードポイント数（<code>codePoints().count()</code>）</td><td class=\"num\">").append(ivsCodePoints)
                .append("</td></tr>\n")
                .append("<tr><td>Java の <code>length()</code></td><td class=\"num\">").append(ivsLength).append("</td></tr>\n")
                .append("<tr><td>UTF-8</td><td class=\"mono\">").append(ivsUtf8).append("（")
                .append(ivs.getBytes(java.nio.charset.StandardCharsets.UTF_8).length).append(" バイト）</td></tr>\n")
                .append("<tr><td>windows-31j に変換すると</td><td class=\"mono\">").append(hexBytes(toInts(ivs.getBytes(W31J))))
                .append("（異体字セレクタが ? になる）</td></tr>\n")
                .append("</tbody>\n</table></div>\n");

        h.append("""
                <h3>実務で気をつけること</h3>
                <ul>
                <li>文字数を <code>length()</code> で数えると、BMP の外の文字や異体字セレクタの分だけ多く数えてしまう</li>
                <li>データベースは 4 バイトの UTF-8 を保存できる設定にする（MySQL なら <code>utf8</code> ではなく <code>utf8mb4</code>）</li>
                <li>Shift_JIS のシステムへデータを渡すときは、表せない文字を近い字形の文字に置き換える対応表（縮退マップ）が必要になる。
                黙って <code>?</code> にすると、人名が欠ける</li>
                <li>文字列の比較では、異体字セレクタの有無で別の文字列になる（<code>"葛".equals("葛" + 異体字セレクタ)</code> は false）</li>
                </ul>
                <h3>参考</h3>
                <ul class="refs">
                <li><a href="https://www.digital.go.jp/assets/contents/node/basic_page/field_ref_resources/4748d2a4-45c7-45de-a55f-dfac4cb04a70/0e0c8a2c/20230609_local_governments_character_specification_materials_01.pdf">デジタル庁「文字要件説明資料」（地方公共団体情報システムデータ要件・連携要件標準仕様書 第 2.0 版）</a></li>
                <li><a href="https://www.digital.go.jp/assets/contents/node/basic_page/field_ref_resources/23c13667-6621-40f6-a02f-79b6aa394be0/5d7d3c08/20230403_meeting_local-governments-character-operation_02.pdf">デジタル庁「地方公共団体情報システムにおける文字要件の運用に関する検討会」資料</a></li>
                <li><a href="https://moji.or.jp/wp-content/uploads/2024/04/Session1_津田様資料_MJフォント保守環境構築1.pdf">文字情報技術促進協議会「MJ フォント保守環境構築」</a></li>
                <li><a href="https://www.dal.co.jp/column/s-mjplus/">データ・アプリケーション「行政事務標準文字とは？MJ+ の意味や問題点」</a></li>
                <li><a href="https://ja.wikipedia.org/wiki/%E6%88%B8%E7%B1%8D%E7%B5%B1%E4%B8%80%E6%96%87%E5%AD%97">Wikipedia「戸籍統一文字」</a></li>
                </ul>
                </section>
                """);
    }

    /** コードポイントの使い道の分類。 */
    enum UCat {
        ASSIGNED("u-assigned", "文字が決まっている", "Unicode が文字を割り当てた番号"),
        PRIVATE("u-private", "私用領域", "文字を決めない。利用者どうしの取り決めで使う"),
        SURROGATE("u-surrogate", "サロゲート", "文字には使わない。UTF-16 のサロゲートペア専用"),
        NONCHAR("u-nonchar", "非文字", "文字には使わない。プログラムの内部で目印に使う"),
        UNASSIGNED("u-unassigned", "未割り当て", "まだ決まっていない。将来の追加のために空けてある");

        final String css;
        final String label;
        final String description;

        UCat(String css, String label, String description) {
            this.css = css;
            this.label = label;
            this.description = description;
        }

        static UCat of(int cp) {
            if ((cp >= 0xFDD0 && cp <= 0xFDEF) || (cp & 0xFFFE) == 0xFFFE) {
                return NONCHAR;
            }
            return switch (Character.getType(cp)) {
                case Character.PRIVATE_USE -> PRIVATE;
                case Character.SURROGATE -> SURROGATE;
                case Character.UNASSIGNED -> UNASSIGNED;
                default -> ASSIGNED;
            };
        }
    }

    /** 分類ごとの数を、割合に応じた帯（linear-gradient）にする。 */
    private static String stackedBackground(int[] counts, String direction) {
        int total = java.util.Arrays.stream(counts).sum();
        StringBuilder g = new StringBuilder("linear-gradient(").append(direction);
        double start = 0;
        for (UCat c : UCat.values()) {
            int n = counts[c.ordinal()];
            if (n == 0) {
                continue;
            }
            double end = start + 100.0 * n / total;
            g.append(", var(--").append(c.css).append(") ").append(String.format("%.2f%% %.2f%%", start, end));
            start = end;
        }
        return g.append(")").toString();
    }

    private static int[] countRange(int from, int to) {
        int[] counts = new int[UCat.values().length];
        for (int cp = from; cp <= to; cp++) {
            counts[UCat.of(cp).ordinal()]++;
        }
        return counts;
    }

    /** Unicode の 0〜16 面と BMP の使い道を示す全体図。 */
    private static void renderUnicodeMap(StringBuilder h) {
        h.append("<section id=\"umap\">\n<h2>Unicode の全体図</h2>\n")
                .append("<p>Unicode のコードポイントは U+0000〜U+10FFFF の 1,114,112 個で、65,536 個ずつの「面」17 個に分かれている。"
                        + "すべての番号について使い道は決まっているが、どの文字かまで決まっているのは一部だけ。"
                        + "数値は JDK ").append(esc(System.getProperty("java.version")))
                .append(" の Unicode データから数えた。</p>\n");

        // 凡例
        int[] all = countRange(0, 0x10FFFF);
        h.append("<div class=\"scroll\"><table class=\"legend\">\n<thead><tr><th>色</th><th>分類</th><th>意味</th><th>数</th>"
                + "<th>割合</th></tr></thead>\n<tbody>\n");
        for (UCat c : UCat.values()) {
            h.append("<tr><td><span class=\"swatch ").append(c.css).append("\"></span></td><td>").append(c.label)
                    .append("</td><td>").append(c.description).append("</td><td class=\"num\">")
                    .append(String.format("%,d", all[c.ordinal()])).append("</td><td class=\"num\">")
                    .append(String.format("%.1f%%", 100.0 * all[c.ordinal()] / 0x110000)).append("</td></tr>\n");
        }
        h.append("</tbody>\n</table></div>\n");

        // 面ごとの表
        record Plane(int from, int to, String name, String content) {
        }
        List<Plane> planes = List.of(
                new Plane(0, 0, "基本多言語面（BMP）", "ASCII・ひらがな・カタカナ・よく使う漢字・ハングル・記号など。サロゲートと私用領域もここ"),
                new Plane(1, 1, "追加多言語面", "絵文字（🍣）・古代文字・数学用の英数字"),
                new Plane(2, 2, "追加漢字面", "拡張漢字（𠮷 U+20BB7・𩸽 U+29E3D・𠮟 U+20B9F）。人名用の珍しい漢字が多い"),
                new Plane(3, 3, "第三漢字面", "さらに追加された拡張漢字"),
                new Plane(4, 13, "（未使用）", "まだ何も割り当てられていない"),
                new Plane(14, 14, "追加特殊用途面", "異体字セレクタ（U+E0100〜U+E01EF）など"),
                new Plane(15, 15, "私用面 A", "私用領域。MJ+ の Unicode にない追加文字など"),
                new Plane(16, 16, "私用面 B", "私用領域"));
        h.append("<h3>面ごとの使い道</h3>\n<div class=\"scroll\"><table class=\"list planes\">\n<thead><tr><th>面</th><th>範囲</th>"
                + "<th>名前</th><th>主な中身</th><th class=\"barcol\">内訳</th></tr></thead>\n<tbody>\n");
        for (Plane pl : planes) {
            int from = pl.from * 0x10000;
            int to = pl.to * 0x10000 + 0xFFFF;
            int[] counts = countRange(from, to);
            StringBuilder tip = new StringBuilder();
            for (UCat c : UCat.values()) {
                if (counts[c.ordinal()] > 0) {
                    tip.append(c.label).append(" ").append(String.format("%,d", counts[c.ordinal()])).append("\n");
                }
            }
            String no = pl.from == pl.to ? String.valueOf(pl.from) : pl.from + "〜" + pl.to;
            h.append("<tr><td class=\"num\">").append(no).append("</td><td class=\"mono\">U+")
                    .append(String.format("%04X", from)).append("〜U+").append(String.format("%04X", to))
                    .append("</td><td>").append(pl.name).append("</td><td>").append(esc(pl.content))
                    .append("</td><td class=\"barcol\"><div class=\"ubar\" style=\"background: ")
                    .append(stackedBackground(counts, "to right")).append("\" title=\"").append(esc(tip.toString().strip()))
                    .append("\"></div></td></tr>\n");
        }
        h.append("</tbody>\n</table></div>\n")
                .append("<p class=\"note\">帯は、その面の中の分類の割合。カーソルを合わせると数が出る。</p>\n");

        // 面ごとに枠で囲った全体図。1 マスは 256 個のコードポイント（1 面 = 16 × 16 マス）
        h.append("<h3 id=\"planes-map\">面ごとの全体図</h3>\n")
                .append("<p>1 つの枠が 1 つの面（65,536 個）。中の 1 マスは 256 個のコードポイントで、16 × 16 マスで 1 面になる。"
                        + "マスの塗り分けは分類の割合（下から積み上げ）。カーソルを合わせると範囲と内訳が出る。"
                        + "面 4〜13 は 10 面とも中身が同じ（すべて未割り当て）なので、1 つにまとめた。</p>\n")
                .append("<div class=\"planecards\">\n");
        for (Plane pl : planes) {
            int base = pl.from * 0x10000;
            int[] planeCounts = countRange(base, pl.to * 0x10000 + 0xFFFF);
            boolean unused = planeCounts[UCat.UNASSIGNED.ordinal()] == (pl.to - pl.from + 1) * 0x10000;
            String no = pl.from == pl.to ? "面 " + pl.from : "面 " + pl.from + "〜" + pl.to;
            String range = pl.from == pl.to
                    ? String.format("U+%04X〜U+%04X", base, base + 0xFFFF)
                    : String.format("U+%04X〜U+%04X", base, pl.to * 0x10000 + 0xFFFF);
            h.append("<div class=\"planecard").append(unused ? " unused" : "").append("\"><div class=\"ptitle\"><b>")
                    .append(no).append("</b><span title=\"").append(esc(pl.name)).append("\">").append(pl.name).append("</span></div><div class=\"prange mono\">").append(range)
                    .append("</div><div class=\"pgrid\">");
            for (int block = 0; block < 256; block++) {
                int from = base + block * 0x100;
                int[] counts = countRange(from, from + 0xFF);
                StringBuilder tip = new StringBuilder(String.format("U+%04X〜U+%04X", from, from + 0xFF));
                Character.UnicodeBlock ub = Character.UnicodeBlock.of(from);
                if (ub != null) {
                    tip.append("\n").append(ub);
                }
                for (UCat c : UCat.values()) {
                    if (counts[c.ordinal()] > 0) {
                        tip.append("\n").append(c.label).append(" ").append(counts[c.ordinal()]);
                    }
                }
                h.append("<div style=\"background: ").append(stackedBackground(counts, "to top")).append("\" title=\"")
                        .append(esc(tip.toString())).append("\"></div>");
            }
            h.append("</div><div class=\"pnote\">").append(esc(pl.content)).append("</div></div>\n");
        }
        h.append("</div>\n");

        // BMP の全体図
        h.append("<h3>BMP（U+0000〜U+FFFF）の全体図</h3>\n")
                .append("<p>BMP を 256 個ずつのブロックに分けて 16 × 16 に並べた図。マスの数字はコードポイントの上 2 桁で、"
                        + "「30」は U+3000〜U+30FF を表す。マスの塗り分けは、そのブロックの中の分類の割合（下から積み上げ）。</p>\n")
                .append("<div class=\"scroll\"><table class=\"grid umap\">\n");
        appendHeaderRow(h);
        for (int hi = 0; hi < 16; hi++) {
            h.append("<tr><th>").append(hex1(hi)).append("x</th>");
            for (int lo = 0; lo < 16; lo++) {
                int block = hi * 16 + lo;
                int from = block * 0x100;
                int[] counts = countRange(from, from + 0xFF);
                StringBuilder tip = new StringBuilder(String.format("U+%04X〜U+%04X", from, from + 0xFF));
                String name = Character.UnicodeBlock.of(from) == null ? "" : Character.UnicodeBlock.of(from).toString();
                if (!name.isEmpty()) {
                    tip.append("\n").append(name);
                }
                for (UCat c : UCat.values()) {
                    if (counts[c.ordinal()] > 0) {
                        tip.append("\n").append(c.label).append(" ").append(counts[c.ordinal()]);
                    }
                }
                h.append("<td style=\"background: ").append(stackedBackground(counts, "to top")).append("\" title=\"")
                        .append(esc(tip.toString())).append("\"><span>").append(hex2(block)).append("</span></td>");
            }
            h.append("</tr>\n");
        }
        h.append("</table></div>\n");
        h.append("""
                <ul class="bands">
                <li><b class="mono">00〜02</b>：ASCII とラテン文字（アクセント付きの文字など）</li>
                <li><b class="mono">03〜04</b>：ギリシャ文字・キリル文字</li>
                <li><b class="mono">20〜2F</b>：句読点・記号・矢印・罫線など（①は 24、〜は 30）</li>
                <li><b class="mono">30</b>：ひらがな・カタカナ・全角の句読点（あ は U+3042）</li>
                <li><b class="mono">34〜4D</b>：CJK 統合漢字の拡張 A</li>
                <li><b class="mono">4E〜9F</b>：CJK 統合漢字。ふだん使う漢字のほとんどがここ</li>
                <li><b class="mono">AC〜D7</b>：ハングル</li>
                <li><b class="mono">D8〜DF</b>：サロゲート。文字ではなく、UTF-16 で BMP の外の文字を表すための部品</li>
                <li><b class="mono">E0〜F8</b>：私用領域。windows-31j の外字は E0〜E7 にある</li>
                <li><b class="mono">F9〜FA</b>：CJK 互換漢字（﨑 U+FA11 など）</li>
                <li><b class="mono">FF</b>：全角・半角形（～ U+FF5E、半角カナ U+FF61〜）。FFFE と FFFF は非文字</li>
                </ul>
                """);

        h.append("""
                <h3>このページに出てくる文字の場所</h3>
                <div class="scroll"><table class="list">
                <thead><tr><th>例</th><th>コードポイント</th><th>面</th><th>分類</th></tr></thead>
                <tbody>
                <tr><td>A（ASCII）</td><td class="mono">U+0041</td><td>BMP</td><td>文字が決まっている</td></tr>
                <tr><td>あ・漢</td><td class="mono">U+3042・U+6F22</td><td>BMP</td><td>文字が決まっている</td></tr>
                <tr><td>サロゲートペアの片割れ</td><td class="mono">U+D800〜U+DFFF</td><td>BMP</td><td>サロゲート</td></tr>
                <tr><td>windows-31j の外字</td><td class="mono">U+E000〜U+E757</td><td>BMP</td><td>私用領域</td></tr>
                <tr><td>macOS の Apple ロゴ</td><td class="mono">U+F8FF</td><td>BMP</td><td>私用領域</td></tr>
                <tr><td>～（全角チルダ）・半角カナ</td><td class="mono">U+FF5E・U+FF61〜</td><td>BMP</td><td>文字が決まっている</td></tr>
                <tr><td>BOM</td><td class="mono">U+FEFF</td><td>BMP</td><td>文字が決まっている（幅ゼロの空白）</td></tr>
                <tr><td>🍣</td><td class="mono">U+1F363</td><td>面 1</td><td>文字が決まっている</td></tr>
                <tr><td>𠮷・𩸽・𠮟</td><td class="mono">U+20BB7・U+29E3D・U+20B9F</td><td>面 2</td><td>文字が決まっている</td></tr>
                <tr><td>異体字セレクタ</td><td class="mono">U+E0100〜U+E01EF</td><td>面 14</td><td>文字が決まっている（制御用）</td></tr>
                <tr><td>MJ+ の Unicode にない追加文字</td><td class="mono">U+F0000〜</td><td>面 15・16</td><td>私用領域</td></tr>
                </tbody>
                </table></div>
                </section>
                """);
    }

    private static void renderSingleByte(StringBuilder h, List<Cell> singleByte) {
        long ascii = singleByte.subList(0x00, 0x80).stream().filter(c -> c.cat != Cat.NONE).count();
        long kana = singleByte.subList(0xA1, 0xE0).stream().filter(c -> c.cat != Cat.NONE).count();
        h.append("<section id=\"single\">\n<h2>1 バイト文字（00〜FF）</h2>\n")
                .append("<p>00〜7F はほぼ ASCII、A1〜DF は半角カナ（JIS X 0201）。")
                .append("「2 バイト文字の 1 バイト目」のマスは単独では文字にならない。")
                .append("JIS X 0201 では 5C は ¥、7E は ‾ だが、Java の Shift_JIS・windows-31j はどちらも ASCII と同じ \\ と ~ に対応させている。</p>\n")
                .append("<details class=\"block\"><summary><span class=\"badge one\">00〜FF</span> 1 バイト文字の表")
                .append("<span class=\"meta\">ASCII ").append(ascii).append(" 文字・半角カナ ").append(kana)
                .append(" 文字</span></summary>\n<div class=\"scroll\"><table class=\"grid chars\">\n");
        appendHeaderRow(h);
        for (int hi = 0; hi < 16; hi++) {
            h.append("<tr><th>").append(hex1(hi)).append("x</th>");
            for (int lo = 0; lo < 16; lo++) {
                appendCharCell(h, singleByte.get(hi * 16 + lo), null);
            }
            h.append("</tr>\n");
        }
        h.append("</table></div>\n</details>\n</section>\n");
    }

    private static void renderDoubleByte(StringBuilder h, Map<Integer, List<Cell>> doubleByte) {
        h.append("<section id=\"double\">\n<h2>2 バイト文字</h2>\n")
                .append("<p>1 バイト目ごとに 1 つの表を折りたたんで並べた。見出しを押すと開く。"
                        + "表の行と列で 2 バイト目を表す（行 8x・列 2 なら 2 バイト目は 82）。"
                        + "2 バイト目は 40〜7E と 80〜FC を使い、1 つの 1 バイト目に JIS の 2 区分が入る。</p>\n")
                .append("<div class=\"controls\"><button type=\"button\" data-toggle=\"open\">すべて開く</button>"
                        + "<button type=\"button\" data-toggle=\"close\">すべて閉じる</button></div>\n");

        h.append("<h3 id=\"double-ext\">windows-31j が拡張した範囲</h3>\n")
                .append("<p class=\"note\">Shift_JIS にはなく、windows-31j だけにある文字。</p>\n");
        for (int lead : List.of(0x87, 0xED, 0xEE, 0xFA, 0xFB, 0xFC)) {
            if (doubleByte.containsKey(lead)) {
                renderLeadBlock(h, lead, doubleByte.get(lead));
            }
        }
        renderGaiji(h, doubleByte);

        List<String> diffLeads = new ArrayList<>();
        doubleByte.forEach((lead, cells) -> {
            if (cells.stream().anyMatch(c -> c.cat == Cat.DIFF)) {
                diffLeads.add(hex2(lead));
            }
        });
        h.append("<h3 id=\"double-jis\">JIS X 0208 の範囲（Shift_JIS と共通）</h3>\n")
                .append("<p class=\"note\">1 バイト目 81〜9F・E0〜EA。ひらがな・カタカナ・漢字など。")
                .append("「対応先が違う」文字があるのは 1 バイト目 ").append(String.join("・", diffLeads)).append(" だけ。</p>\n");
        for (var e : doubleByte.entrySet()) {
            int lead = e.getKey();
            if (categoryForLead(lead) == Cat.W31J_OTHER) {
                renderLeadBlock(h, lead, e.getValue());
            }
        }
        h.append("</section>\n");
    }

    /** 1 バイト目 1 つ分の表を、折りたためる形で書く。 */
    private static void renderLeadBlock(StringBuilder h, int lead, List<Cell> cells) {
        Map<Cat, Integer> counts = new EnumMap<>(Cat.class);
        cells.stream().filter(c -> c.cat != Cat.NONE).forEach(c -> counts.merge(c.cat, 1, Integer::sum));
        int total = counts.values().stream().mapToInt(Integer::intValue).sum();
        Cat main = overviewCat(cells);
        String css = main == Cat.SAME ? "jis" : main.css;

        h.append("<details class=\"block\" id=\"lead-").append(hex2(lead)).append("\"><summary><span class=\"badge ")
                .append(css).append("\">").append(hex2(lead)).append("</span> ").append(esc(leadLabel(lead)))
                .append("<span class=\"preview\">").append(esc(preview(cells))).append("</span>")
                .append("<span class=\"meta\">");
        if (counts.containsKey(Cat.DIFF)) {
            h.append("<span class=\"pill diff\">対応先が違う ").append(counts.get(Cat.DIFF)).append("</span>");
        }
        h.append(total).append(" 文字</span></summary>\n<div class=\"scroll\"><table class=\"grid chars\">\n");
        appendHeaderRow(h);
        Map<Integer, Cell> byTrail = new LinkedHashMap<>();
        cells.forEach(c -> byTrail.put(c.bytes[1], c));
        for (int hi = 4; hi < 16; hi++) {
            h.append("<tr><th>").append(hex1(hi)).append("x</th>");
            for (int lo = 0; lo < 16; lo++) {
                int trail = hi * 16 + lo;
                Cell c = byTrail.get(trail);
                if (c == null) {
                    h.append("<td class=\"na\" title=\"2 バイト目に使わない値\"></td>");
                } else {
                    appendCharCell(h, c, kuten(lead, trail));
                }
            }
            h.append("</tr>\n");
        }
        h.append("</table></div>\n</details>\n");
    }

    /** 見出しに添える、表の先頭の数文字。 */
    private static String preview(List<Cell> cells) {
        StringBuilder sb = new StringBuilder();
        for (Cell c : cells) {
            String ch = c.w31j != null ? c.w31j : c.sjis;
            if (ch == null || Character.isSpaceChar(ch.codePointAt(0))) {
                continue;
            }
            sb.append(ch);
            if (sb.codePointCount(0, sb.length()) >= 12) {
                return sb + "…";
            }
        }
        return sb.toString();
    }

    /** 外字は私用領域で字形がないので、マスを並べずに範囲だけ示す。 */
    private static void renderGaiji(StringBuilder h, Map<Integer, List<Cell>> doubleByte) {
        List<Cell> all = new ArrayList<>();
        for (int lead = 0xF0; lead <= 0xF9; lead++) {
            if (doubleByte.containsKey(lead)) {
                doubleByte.get(lead).stream().filter(c -> c.w31j != null).forEach(all::add);
            }
        }
        if (all.isEmpty()) {
            return;
        }
        Cell first = all.getFirst();
        Cell last = all.getLast();
        h.append("<details class=\"block\" id=\"lead-F0\"><summary><span class=\"badge gaiji\">F0〜F9</span> ")
                .append("95区〜114区：外字<span class=\"meta\">").append(all.size()).append(" 文字</span></summary>\n");
        for (int lead = 0xF1; lead <= 0xF9; lead++) {
            h.append("<span id=\"lead-").append(hex2(lead)).append("\"></span>");
        }
        h.append("<div class=\"callout\"><p>windows-31j だけが使う、利用者が自由に文字を作って登録するための領域。")
                .append("Unicode の私用領域に順番に対応し、決まった字形はないので表は省いた。</p>\n<p class=\"mono\">")
                .append(hexBytes(first.bytes)).append(" → U+").append(cp(first.w31j)).append("　〜　")
                .append(hexBytes(last.bytes)).append(" → U+").append(cp(last.w31j))
                .append("（").append(all.size()).append(" 文字）</p></div>\n</details>\n");
    }

    /** コードポイントから UTF-8 のバイト列への変換規則と、Shift_JIS 系とのバイト数の比較。 */
    private static void renderUtf8(StringBuilder h, List<Cell> singleByte, Map<Integer, List<Cell>> doubleByte) {
        h.append("<section id=\"utf8\">\n<h2>コードポイントと UTF-8</h2>\n")
                .append("<p>UTF-8 は、前の章のコードポイント（U+XXXX）をバイト列にする符号化方式。"
                        + "番号の大きさに応じて 1〜4 バイトに分けて書く。"
                        + "第 2 部の表のマスにカーソルを合わせると、その文字の UTF-8 のバイト列も出る。</p>\n");

        // 変換規則。例のバイト列は Java の UTF-8 で実際にエンコードして出す
        String[][] rules = {
                {"U+0000〜U+007F", "1", "0xxxxxxx", "A"},
                {"U+0080〜U+07FF", "2", "110xxxxx 10xxxxxx", "¢"},
                {"U+0800〜U+FFFF", "3", "1110xxxx 10xxxxxx 10xxxxxx", "あ"},
                {"U+10000〜U+10FFFF", "4", "11110xxx 10xxxxxx 10xxxxxx 10xxxxxx", "𠮷"},
        };
        h.append("<h3>変換規則</h3>\n<div class=\"scroll\"><table class=\"list\">\n<thead><tr><th>コードポイントの範囲</th>"
                + "<th>バイト数</th><th>ビットの並び（x にコードポイントのビットを詰める）</th><th>例</th></tr></thead>\n<tbody>\n");
        for (String[] r : rules) {
            h.append("<tr><td class=\"mono\">").append(r[0]).append("</td><td class=\"num\">").append(r[1])
                    .append("</td><td class=\"mono\">").append(r[2]).append("</td><td><span class=\"glyph\">").append(esc(r[3]))
                    .append("</span> <span class=\"mono\">U+").append(cp(r[3])).append(" → ").append(utf8Hex(r[3]))
                    .append("</span></td></tr>\n");
        }
        h.append("</tbody>\n</table></div>\n");
        h.append("""
                <p class="note">1 バイト目の先頭のビットでバイト数が分かる（0 なら 1 バイト、110 なら 2 バイト、1110 なら 3 バイト、11110 なら 4 バイト）。
                2 バイト目以降は必ず 10 で始まるので、80〜BF の範囲になる。ASCII の文字（00〜7F）と取り違えることはない。</p>
                <h3>例：あ（U+3042）を UTF-8 にする</h3>
                <div class="callout"><pre class="mono">U+3042 を 2 進数にする      0011 0000 0100 0010
                3 バイトの規則に合わせて分ける  0011 | 000001 | 000010
                印を付ける                 1110<b>0011</b> | 10<b>000001</b> | 10<b>000010</b>
                16 進数にする               E3      | 81       | 82</pre></div>
                """);

        // Shift_JIS 系との バイト数の比較
        Map<String, int[]> table = new LinkedHashMap<>();
        List<Cell> all = new ArrayList<>(singleByte);
        doubleByte.values().forEach(all::addAll);
        for (Cell c : all) {
            if (c.w31j == null || c.cat == Cat.GAIJI) {
                continue;
            }
            String group = c.bytes.length == 1
                    ? (c.bytes[0] <= 0x7F ? "1 バイト：ASCII（00〜7F）" : "1 バイト：半角カナ（A1〜DF）")
                    : "2 バイト：" + regionLabel(c.bytes);
            int[] counts = table.computeIfAbsent(group, k -> new int[5]);
            counts[c.w31j.getBytes(java.nio.charset.StandardCharsets.UTF_8).length]++;
        }
        h.append("<h3>windows-31j と UTF-8 のバイト数の比較</h3>\n")
                .append("<p>windows-31j の文字はすべて BMP（U+FFFF 以下）にあるので、UTF-8 では 1〜3 バイトになる。"
                        + "半角カナは windows-31j では 1 バイトだが、UTF-8 では 3 バイトになる点に注意。外字は除いた。</p>\n")
                .append("<div class=\"scroll\"><table class=\"list\">\n<thead><tr><th>windows-31j での種類</th>"
                        + "<th>UTF-8 で 1 バイト</th><th>2 バイト</th><th>3 バイト</th></tr></thead>\n<tbody>\n");
        table.forEach((group, counts) -> h.append("<tr><td>").append(esc(group)).append("</td><td class=\"num\">")
                .append(counts[1]).append("</td><td class=\"num\">").append(counts[2]).append("</td><td class=\"num\">")
                .append(counts[3]).append("</td></tr>\n"));
        h.append("</tbody>\n</table></div>\n</section>\n");
    }

    /** Shift_JIS と windows-31j の違いを、Unicode の側から見る。 */
    private static void renderUnicodeSide(StringBuilder h, List<Cell> singleByte, Map<Integer, List<Cell>> doubleByte) {
        h.append("<section id=\"sjis-w31j\">\n<h2>Shift_JIS と windows-31j の違い</h2>\n")
                .append("<p>どちらも読んだ文字は Unicode で扱うので、違いは Unicode の側から見ると分かりやすい。"
                        + "上の表の「対応先が違う」文字のほかに、書くときに問題になるものが 2 つある。</p>\n");

        // Shift_JIS では書けるが windows-31j では書けない文字
        CharsetEncoder sjisEnc = SJIS.newEncoder();
        CharsetEncoder w31jEnc = W31J.newEncoder();
        h.append("<h3>Shift_JIS では書けるが、windows-31j では書けない文字</h3>\n")
                .append("<p>「windows-31j は Shift_JIS の上位互換」とは言い切れない例。"
                        + "同じバイト列を windows-31j は別の Unicode の文字に割り当てているので、この文字を書く先がない。</p>\n")
                .append("<div class=\"scroll\"><table class=\"list\">\n<thead><tr><th>文字</th><th>Unicode</th><th>名前</th>"
                        + "<th>UTF-8</th><th>Shift_JIS</th><th>同じバイト列の windows-31j の文字</th></tr></thead>\n<tbody>\n");
        for (int cp = 0; cp <= 0xFFFF; cp++) {
            if (Character.isSurrogate((char) cp)) {
                continue;
            }
            String s = Character.toString(cp);
            if (sjisEnc.canEncode(s) && !w31jEnc.canEncode(s)) {
                byte[] b = s.getBytes(SJIS);
                int[] ib = new int[b.length];
                for (int i = 0; i < b.length; i++) {
                    ib[i] = b[i] & 0xFF;
                }
                String w = decodeOne(W31J, ib);
                h.append("<tr><td class=\"glyph\">").append(esc(s)).append("</td><td class=\"mono\">U+").append(cp(s))
                        .append("</td><td>").append(esc(name(s))).append("</td><td class=\"mono\">").append(utf8Hex(s))
                        .append("</td><td class=\"mono\">").append(hexBytes(ib))
                        .append("</td><td>").append(w == null ? "なし"
                                : "<span class=\"glyph\">" + esc(w) + "</span> <span class=\"mono\">U+" + cp(w) + "</span> "
                                        + esc(name(w)))
                        .append("</td></tr>\n");
            }
        }
        h.append("</tbody>\n</table></div>\n");

        // windows-31j で同じ文字が複数の位置にあるもの
        Map<String, List<int[]>> positions = new LinkedHashMap<>();
        List<Cell> allCells = new ArrayList<>(singleByte);
        doubleByte.values().forEach(allCells::addAll);
        for (Cell c : allCells) {
            if (c.w31j != null) {
                positions.computeIfAbsent(c.w31j, k -> new ArrayList<>()).add(c.bytes);
            }
        }
        long dupTotal = positions.values().stream().filter(l -> l.size() >= 2).count();
        h.append("<h3>windows-31j で同じ文字が複数のバイト列にあるもの</h3>\n")
                .append("<p>NEC 特殊文字・NEC 選定 IBM 拡張文字・IBM 拡張文字が重なっているため、同じ文字に複数のバイト列がある。"
                        + "読むときはどれも同じ文字になるが、書くときはどれか 1 つ（太字）しか使われない。"
                        + "そのため、読んで書き戻すとバイト列が変わることがある。</p>\n")
                .append("<details class=\"block\"><summary>一覧<span class=\"meta\">").append(dupTotal)
                .append(" 文字</span></summary>\n")
                .append("<div class=\"scroll\"><table class=\"list\">\n<thead><tr><th>文字</th><th>Unicode</th><th>名前</th>"
                        + "<th>UTF-8</th><th>windows-31j のバイト列</th></tr></thead>\n<tbody>\n");
        for (var e : positions.entrySet()) {
            if (e.getValue().size() < 2) {
                continue;
            }
            String s = e.getKey();
            byte[] chosen = s.getBytes(W31J);
            StringBuilder list = new StringBuilder();
            for (int[] b : e.getValue()) {
                String hx = hexBytes(b);
                boolean isChosen = hx.equals(hexBytes(toInts(chosen)));
                if (!list.isEmpty()) {
                    list.append("、");
                }
                list.append(isChosen ? "<b>" + hx + "</b>" : hx).append("（").append(esc(regionLabel(b))).append("）");
            }
            h.append("<tr><td class=\"glyph\">").append(esc(s)).append("</td><td class=\"mono\">U+").append(cp(s))
                    .append("</td><td>").append(esc(name(s))).append("</td><td class=\"mono\">").append(utf8Hex(s))
                    .append("</td><td class=\"mono\">").append(list)
                    .append("</td></tr>\n");
        }
        h.append("</tbody>\n</table></div>\n</details>\n</section>\n");
    }

    // ---- 部品 ----

    private static void appendHeaderRow(StringBuilder h) {
        h.append("<tr><th></th>");
        for (int lo = 0; lo < 16; lo++) {
            h.append("<th>").append(hex1(lo)).append("</th>");
        }
        h.append("</tr>\n");
    }

    private static void appendCharCell(StringBuilder h, Cell c, int[] kuten) {
        StringBuilder title = new StringBuilder(hexBytes(c.bytes));
        if (kuten != null) {
            title.append("　").append(kuten[0]).append("区").append(kuten[1]).append("点");
        }
        title.append("\n").append(c.cat.label);
        if (c.sjis != null && c.w31j != null && !c.sjis.equals(c.w31j)) {
            title.append("\nShift_JIS: U+").append(cp(c.sjis)).append(" ").append(name(c.sjis))
                    .append("（UTF-8: ").append(utf8Hex(c.sjis)).append("）")
                    .append("\nwindows-31j: U+").append(cp(c.w31j)).append(" ").append(name(c.w31j))
                    .append("（UTF-8: ").append(utf8Hex(c.w31j)).append("）");
        } else if (c.sjis != null || c.w31j != null) {
            String s = c.sjis != null ? c.sjis : c.w31j;
            title.append("\nU+").append(cp(s)).append(" ").append(name(s))
                    .append("\nUTF-8: ").append(utf8Hex(s));
        }

        h.append("<td class=\"").append(c.cat.css).append("\" title=\"").append(esc(title.toString())).append("\">");
        switch (c.cat) {
            case NONE -> {
            }
            case LEAD -> h.append("<span class=\"g\"><i>1バイト目</i></span>");
            case DIFF -> h.append("<span class=\"g\">").append(glyph(c.sjis)).append("→").append(glyph(c.w31j))
                    .append("</span><span class=\"u\">").append(cp(c.sjis)).append("<br>").append(cp(c.w31j)).append("</span>");
            default -> {
                String s = c.sjis != null ? c.sjis : c.w31j;
                h.append("<span class=\"g\">").append(glyph(s)).append("</span><span class=\"u\">").append(cp(s))
                        .append("</span>");
            }
        }
        h.append("</td>");
    }

    /** 見えない文字は説明に置き換える。 */
    private static String glyph(String s) {
        int cp = s.codePointAt(0);
        if (Character.isISOControl(cp)) {
            return "<i>制御</i>";
        }
        if (Character.isSpaceChar(cp)) {
            return "<i>空白</i>";
        }
        if (Character.getType(cp) == Character.PRIVATE_USE) {
            return "<i>私用</i>";
        }
        return esc(s);
    }

    private static String leadLabel(int lead) {
        int[] a = kuten(lead, 0x40);
        int[] b = kuten(lead, 0xFC);
        String ku = a[0] + "区・" + b[0] + "区";
        return switch (categoryForLead(lead)) {
            case NEC -> ku + "：NEC 特殊文字";
            case NEC_IBM -> ku + "：NEC 選定 IBM 拡張文字";
            case IBM -> ku + "：IBM 拡張文字";
            case GAIJI -> ku + "：外字";
            default -> ku;
        };
    }

    private static String regionLabel(int[] b) {
        if (b.length == 1) {
            return "1 バイト";
        }
        return switch (categoryForLead(b[0])) {
            case NEC -> "NEC 特殊文字";
            case NEC_IBM -> "NEC 選定 IBM 拡張";
            case IBM -> "IBM 拡張";
            case GAIJI -> "外字";
            default -> "JIS X 0208";
        };
    }

    private static String describeShort(Cell c) {
        String s = c.sjis != null ? c.sjis : c.w31j;
        return "U+" + cp(s) + "（" + c.cat.label + "）";
    }

    private static String utf8Hex(String s) {
        return hexBytes(toInts(s.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
    }

    private static String name(String s) {
        String n = Character.getName(s.codePointAt(0));
        return n == null ? "" : n;
    }

    private static String cp(String s) {
        return String.format("%04X", s.codePointAt(0));
    }

    private static String hex1(int v) {
        return Integer.toHexString(v).toUpperCase();
    }

    private static String hex2(int v) {
        return String.format("%02X", v);
    }

    private static String hexBytes(int[] b) {
        StringBuilder sb = new StringBuilder();
        for (int x : b) {
            if (!sb.isEmpty()) {
                sb.append(' ');
            }
            sb.append(hex2(x));
        }
        return sb.toString();
    }

    private static int[] toInts(byte[] b) {
        int[] r = new int[b.length];
        for (int i = 0; i < b.length; i++) {
            r[i] = b[i] & 0xFF;
        }
        return r;
    }

    private static String esc(String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;");
    }

    private static final String CSS = """
            :root {
              --bg: #ffffff; --fg: #18181b; --muted: #52525b; --line: #e7e7ea; --head: #f4f4f5; --soft: #fafafa;
              --same: #ffffff; --jis: #e8eef7; --diff: #ffd8a8; --sjis: #ffc9c9;
              --nec: #c3fae8; --necibm: #d0bfff; --ibm: #a5d8ff; --gaiji: #ced4da; --w31j: #fff3bf;
              --one: #ffffff; --lead: #e9ecef; --none: repeating-linear-gradient(45deg, #eceef0 0 3px, #f8f9fa 3px 7px); --na: repeating-linear-gradient(45deg, #eceef0 0 3px, #f8f9fa 3px 7px);
              --accent: #2563eb; --u-assigned: #4dabf7; --u-private: #ffa94d; --u-surrogate: #f06595; --u-nonchar: #343a40; --u-unassigned: #e9ecef; --k-sym: #adb5bd; --k-alnum: #ffe066; --k-hira: #faa2c1; --k-kata: #e64980; --k-greek: #ffc078; --k-cyr: #fd7e14; --k-line: #868e96; --k-kanji1: #d9a95b; --k-kanji2: #f1dcb4; --k-cell-empty: #f0f0f2;
            }
            @media (prefers-color-scheme: dark) {
              :root:not([data-theme="light"]) {
                --bg: #0a0a0b; --fg: #ededef; --muted: #b4b4bd; --line: #232327; --head: #18181b; --soft: #111113;
                --same: #121215; --jis: #24426e; --diff: #5c3d12; --sjis: #5c1f24;
                --nec: #0b7a5e; --necibm: #5f3dc4; --ibm: #1864ab; --gaiji: #495057; --w31j: #4d4314;
                --one: #2c3038; --lead: #343a40; --none: repeating-linear-gradient(45deg, #1a1c20 0 3px, #24272d 3px 7px); --na: repeating-linear-gradient(45deg, #1a1c20 0 3px, #24272d 3px 7px);
                --accent: #8ab4ff; --u-assigned: #1c7ed6; --u-private: #e8590c; --u-surrogate: #c2255c; --u-nonchar: #f1f3f5; --u-unassigned: #2b2f36; --k-sym: #6c757d; --k-alnum: #b59f1b; --k-hira: #a8436b; --k-kata: #d6336c; --k-greek: #b8742a; --k-cyr: #d9480f; --k-line: #495057; --k-kanji1: #a7834a; --k-kanji2: #6b5a3d; --k-cell-empty: #1b1b1f;
              }
            }
            :root[data-theme="dark"] {
              --bg: #0a0a0b; --fg: #ededef; --muted: #b4b4bd; --line: #232327; --head: #18181b; --soft: #111113;
              --same: #121215; --jis: #24426e; --diff: #5c3d12; --sjis: #5c1f24;
              --nec: #0b7a5e; --necibm: #5f3dc4; --ibm: #1864ab; --gaiji: #495057; --w31j: #4d4314;
              --one: #2c3038; --lead: #343a40; --none: repeating-linear-gradient(45deg, #1a1c20 0 3px, #24272d 3px 7px); --na: repeating-linear-gradient(45deg, #1a1c20 0 3px, #24272d 3px 7px);
              --accent: #8ab4ff; --u-assigned: #1c7ed6; --u-private: #e8590c; --u-surrogate: #c2255c; --u-nonchar: #f1f3f5; --u-unassigned: #2b2f36; --k-sym: #6c757d; --k-alnum: #b59f1b; --k-hira: #a8436b; --k-kata: #d6336c; --k-greek: #b8742a; --k-cyr: #d9480f; --k-line: #495057; --k-kanji1: #a7834a; --k-kanji2: #6b5a3d; --k-cell-empty: #1b1b1f;
            }
            * { box-sizing: border-box; }
            html { -webkit-text-size-adjust: 100%; font-size: 106.25%; }
            body { margin: 0; background: var(--bg); color: var(--fg); font-size: 1rem; line-height: 1.85;
              font-family: -apple-system, BlinkMacSystemFont, "Hiragino Sans", "Noto Sans JP", "Yu Gothic UI", sans-serif;
              font-feature-settings: "palt" 1; -webkit-font-smoothing: antialiased; }
            main { max-width: 980px; margin: 0 auto; padding: 72px 24px 160px; }
            h1 { font-size: 2.4rem; line-height: 1.25; font-weight: 700; letter-spacing: -0.02em; margin: 0 0 12px; }
            h2 { font-size: 1.55rem; line-height: 1.4; font-weight: 700; letter-spacing: -0.01em; margin: 88px 0 16px; }
            h3 { font-size: 1.02rem; font-weight: 650; margin: 48px 0 10px; scroll-margin-top: 16px; }
            h3 small { font-weight: normal; color: var(--muted); margin-left: 8px; }
            p, ul, ol { margin: 0 0 14px; }
            ul, ol { padding-left: 1.3em; } li { margin: 4px 0; } li::marker { color: var(--muted); }
            b, strong { font-weight: 650; }
            a { color: var(--accent); text-decoration: none; } a:hover { text-decoration: underline; text-underline-offset: 3px; }
            code, .mono { font-family: ui-monospace, "SF Mono", "JetBrains Mono", Menlo, monospace; font-size: 0.86em;
              font-feature-settings: normal; }
            p code, li code { padding: 1px 6px; border-radius: 5px; background: var(--head); }
            .subtitle { margin: 0 0 40px; color: var(--muted); font-family: ui-monospace, "SF Mono", Menlo, monospace;
              font-size: 0.88rem; letter-spacing: 0.04em; }
            .lead-text { color: var(--muted); max-width: 46em; }
            .lead-list { color: var(--muted); margin: 0 0 20px; padding-left: 1.3em; max-width: 46em; }
            .lead-list li { margin: 6px 0; } .lead-list b { color: var(--fg); }
            .note { color: var(--muted); font-size: 0.92rem; }
            ul.note { padding-left: 1.1em; } ul.note li { margin: 6px 0; }
            .part { display: flex; align-items: baseline; gap: 16px; margin: 140px 0 -48px; padding-top: 20px;
              border-top: 1px solid var(--fg); font-size: 0.86rem; font-weight: 600; letter-spacing: 0.08em; scroll-margin-top: 72px; }
            .part > span { font-family: ui-monospace, "SF Mono", Menlo, monospace; color: var(--muted); font-weight: 500; }
            .scroll { overflow-x: auto; margin: 16px 0; }
            table { border-collapse: collapse; }
            th, td { border: 1px solid var(--line); }
            th { background: transparent; color: var(--muted); font-weight: 500; font-family: ui-monospace, "SF Mono", Menlo, monospace;
              font-size: 0.78rem; padding: 2px 6px; }
            /* 説明用の表：縦の罫線を引かず、行の下線だけにする */
            .legend, .list { width: 100%; }
            .legend td, .legend th, .list td, .list th { border: 0; border-bottom: 1px solid var(--line); padding: 9px 14px 9px 0;
              text-align: left; vertical-align: top; font-size: 0.94rem; }
            .legend th, .list th { font-family: inherit; font-size: 0.8rem; font-weight: 500; letter-spacing: 0.02em;
              border-bottom-color: var(--fg); padding-top: 0; vertical-align: bottom; line-height: 1.4; }
            .legend tbody tr:last-child td, .list tbody tr:last-child td { border-bottom: 0; }
            .legend .num, .list .num { text-align: right; font-variant-numeric: tabular-nums; padding-right: 0; }
            .list td:first-child { white-space: nowrap; } .list th.c { white-space: nowrap; } .list th.c, .list td.c { text-align: center; padding-right: 8px; padding-left: 8px; } .list td.x { text-align: center; color: var(--muted); }
            .list td.bytes, .list td.mono { white-space: nowrap; }
            .swatch { display: inline-block; width: 14px; height: 14px; vertical-align: -2px; border-radius: 4px;
              box-shadow: inset 0 0 0 1px var(--line); }
            /* 文字の表 */
            .grid { border-radius: 8px; }
            .grid td { width: 3.6em; min-width: 3.6em; height: 3.4em; text-align: center; vertical-align: middle; padding: 2px; }
            .grid th { padding: 4px 6px; }
            .grid.overview td { height: 2.4em; font-family: ui-monospace, "SF Mono", Menlo, monospace; font-size: 0.78rem; }
            .grid.overview a { display: block; text-decoration: none; color: inherit; font-weight: 600; }
            .grid .g { display: block; font-size: 1.25rem; line-height: 1.2; }
            .grid .g i { font-style: normal; font-size: 0.7rem; color: var(--muted); }
            .grid .u { display: block; font-family: ui-monospace, "SF Mono", Menlo, monospace; font-size: 0.62rem; color: var(--muted); line-height: 1.2; }
            .grid .diff .g { font-size: 1rem; }
            .same { background: var(--same); } .jis { background: var(--jis); } .diff { background: var(--diff); }
            .sjis { background: var(--sjis); } .nec { background: var(--nec); } .necibm { background: var(--necibm); }
            .ibm { background: var(--ibm); } .gaiji { background: var(--gaiji); } .w31j { background: var(--w31j); }
            .lead { background: var(--lead); } .one { background: var(--one); } .none { background: var(--none); } .na { background: var(--na); }
            .bands { padding-left: 0; list-style: none; font-size: 0.94rem; } .bands li { margin: 6px 0; display: flex; gap: 10px; align-items: baseline; }
            .jump { display: flex; flex-wrap: wrap; gap: 6px; margin: 16px 0; }
            .jump a { font-family: ui-monospace, "SF Mono", Menlo, monospace; font-size: 0.82rem; padding: 3px 9px; border: 1px solid var(--line);
              border-radius: 999px; text-decoration: none; color: var(--fg); }
            .jump a:hover { border-color: var(--fg); }
            .callout { padding: 14px 18px; background: var(--soft); border-radius: 8px; }
            .list .glyph, .glyph { font-size: 1.25rem; }
            .toggle { display: inline-flex; gap: 8px; align-items: center; margin-top: 12px; cursor: pointer; font-size: 0.92rem; color: var(--muted); }
            .toggle input { accent-color: var(--fg); }
            body.only-diff .chars td.same { opacity: 0.2; }
            /* 狭い画面：上部に固定する横並びの目次 */
            .toc { position: sticky; top: 0; z-index: 10; display: flex; flex-wrap: nowrap; overflow-x: auto; gap: 18px; align-items: center;
              margin: 0 -24px 24px; padding: 12px 24px; border-bottom: 1px solid var(--line); font-size: 0.88rem; white-space: nowrap;
              background: color-mix(in srgb, var(--bg) 85%, transparent); -webkit-backdrop-filter: blur(12px); backdrop-filter: blur(12px);
              scrollbar-width: none; }
            .toc > b, .toc .tgroup, .toc a.sub { display: none; }
            .toc a { color: var(--muted); } .toc a:hover { color: var(--fg); text-decoration: none; }
            .toc a.active { color: var(--fg); font-weight: 600; }
            h2, h3, details { scroll-margin-top: 72px; }
            /* 広い画面：左のサイドパネルに縦に並べる */
            @media (min-width: 1100px) {
              body { padding-left: 260px; }
              /* 右上の凡例と本文が重ならないよう、右側を空ける */
              main { max-width: 1300px; padding-right: 300px; }
              .toc { position: fixed; top: 0; left: 0; bottom: 0; width: 260px; margin: 0; padding: 40px 20px 40px 28px;
                border: 0; border-right: 1px solid var(--line); flex-direction: column; flex-wrap: nowrap; align-items: stretch;
                gap: 0; overflow-y: auto; background: var(--bg); -webkit-backdrop-filter: none; backdrop-filter: none;
                font-size: 0.9rem; white-space: normal; }
              .toc > b { display: block; font-size: 0.76rem; font-weight: 600; letter-spacing: 0.12em; color: var(--fg); padding-bottom: 8px; }
              .toc .tgroup { display: block; margin: 26px 0 6px; font-size: 0.78rem; color: var(--muted); letter-spacing: 0.04em; }
              .toc a { display: block; padding: 5px 0 5px 12px; margin-left: -12px; border-left: 2px solid transparent;
                color: var(--muted); line-height: 1.5; transition: color 0.15s; }
              .toc a.sub { display: block; padding-left: 26px; font-size: 0.85rem; }
              .toc a.active { color: var(--fg); border-left-color: var(--fg); font-weight: 600; }
              h2, h3, details { scroll-margin-top: 24px; }
              .part { scroll-margin-top: 24px; }
            }
            /* 凡例：右上に浮かべて表示する。見出しを押すと折りたためる */
            .side-legend { position: fixed; top: 16px; right: 16px; z-index: 30; width: 244px; max-height: calc(100vh - 32px);
              overflow-y: auto; padding: 10px 14px 12px; border: 1px solid var(--line); border-radius: 12px;
              background: color-mix(in srgb, var(--bg) 88%, transparent); -webkit-backdrop-filter: blur(16px); backdrop-filter: blur(16px);
              box-shadow: 0 8px 32px rgba(0, 0, 0, 0.08); }
            .side-legend .sl-title { display: flex; align-items: center; justify-content: space-between; gap: 12px; width: 100%;
              padding: 0; background: none; border: 0; color: var(--fg); font: inherit; font-size: 0.76rem; font-weight: 600;
              letter-spacing: 0.12em; cursor: pointer; }
            .sl-caret::after { content: "−"; color: var(--muted); font-size: 0.9rem; }
            .side-legend .sl-body { margin-top: 8px; }
            .side-legend.collapsed { width: auto; padding-bottom: 10px; }
            .side-legend.collapsed .sl-body { display: none; }
            .side-legend.collapsed .sl-caret::after { content: "+"; }
            .sl-block { display: none; } .sl-block.shown { display: block; }
            .sl-item { display: flex; align-items: center; gap: 8px; font-size: 0.82rem; line-height: 1.5; padding: 2px 0; }
            .sl-item .swatch { flex: none; width: 11px; height: 11px; border-radius: 3px; }
            .sl-item small { margin-left: auto; color: var(--muted); font-size: 0.74rem; font-family: ui-monospace, "SF Mono", Menlo, monospace; }
            .sl-sep { font-size: 0.74rem; color: var(--muted); margin: 10px 0 2px; }
            .sl-note { font-size: 0.8rem; color: var(--muted); margin-top: 4px; }
            .k-diff-swatch { box-shadow: inset 0 0 0 2px #e03131; }

            /* 折りたたみ */
            .block { border: 0; border-bottom: 1px solid var(--line); margin: 0; }
            .block:first-of-type { border-top: 1px solid var(--line); }
            .block > summary { cursor: pointer; padding: 12px 4px; display: flex; align-items: center; gap: 12px; list-style: none; }
            .block > summary::-webkit-details-marker { display: none; }
            .block > summary::before { content: ""; width: 6px; height: 6px; flex: none; border-right: 1.5px solid var(--muted);
              border-bottom: 1.5px solid var(--muted); transform: rotate(-45deg); transition: transform 0.15s; }
            .block[open] > summary::before { transform: rotate(45deg); }
            .block > summary:hover { background: var(--soft); }
            .block > .scroll, .block > .callout { margin: 4px 0 20px; }
            .badge { font-family: ui-monospace, "SF Mono", Menlo, monospace; font-weight: 600; font-size: 0.84rem; padding: 1px 8px;
              border-radius: 5px; box-shadow: inset 0 0 0 1px var(--line); }
            .meta { margin-left: auto; color: var(--muted); font-size: 0.86rem; display: flex; gap: 8px; align-items: center; white-space: nowrap; }
            .preview { color: var(--muted); font-size: 0.92rem; letter-spacing: 0.05em; white-space: nowrap; overflow: hidden; text-overflow: ellipsis; min-width: 0; }
            .k-sym { background: var(--k-sym); } .k-alnum { background: var(--k-alnum); } .k-hira { background: var(--k-hira); }
            .k-kata { background: var(--k-kata); } .k-greek { background: var(--k-greek); } .k-cyr { background: var(--k-cyr); }
            .k-line { background: var(--k-line); } .k-kanji1 { background: var(--k-kanji1); } .k-kanji2 { background: var(--k-kanji2); }
            .k-empty { background: var(--k-cell-empty); }
            .kregions td { vertical-align: middle; } .kregions tr.muted td { color: var(--muted); }
            .kregions .note { font-size: 0.84rem; }
            .kmap { --frame: var(--fg); border-collapse: collapse; margin-top: 12px; }
            .kmap th, .kmap td { padding: 0; border: 0; }
            .kmap td { width: 8px; height: 8px; min-width: 8px; border: 1px solid var(--bg); }
            .kmap td.e { background: var(--k-cell-empty); }
            .kmap td.kt { border-top: 2px solid var(--frame); } .kmap td.kb { border-bottom: 2px solid var(--frame); }
            .kmap td.kl { border-left: 2px solid var(--frame); } .kmap td.kr { border-right: 2px solid var(--frame); }
            .kmap td.kx { --frame: var(--muted); }
            .kmap td.kx.kt { border-top-style: dashed; } .kmap td.kx.kb { border-bottom-style: dashed; }
            .kmap td.kx.kl { border-left-style: dashed; } .kmap td.kx.kr { border-right-style: dashed; }
            .kmap tr.kgap td { height: 5px; border: 0; background: none; }
            .kmap td.gaiji { background: repeating-linear-gradient(45deg, var(--gaiji) 0 2px, transparent 2px 4px); }
            .kmap td.k-diff { box-shadow: inset 0 0 0 2px #e03131; }
            .kmap th { background: none; font-size: 0.58rem; line-height: 8px; height: 8px; color: var(--muted); font-weight: normal;
              text-align: right; padding-right: 4px; white-space: nowrap; }
            .kmap tr:first-child th { text-align: left; height: 14px; }
            .kmap th a { color: var(--muted); text-decoration: none; }
            .kmap th a:hover { color: var(--fg); }
            .kmap td.klabel { width: auto; background: none; border: 0; font-size: 0.78rem; color: var(--fg); padding-left: 10px;
              white-space: nowrap; vertical-align: middle; }
            .kmap td.klabel span { color: var(--muted); margin-left: 4px; }
            .kmap td.klabel.kx { color: var(--muted); } .kmap td.klabel.kx b { font-weight: normal; }
            .kmap tr:not(.kgap) td:hover:not(.klabel) { outline: 2px solid var(--fg); }
            .u-assigned { background: var(--u-assigned); } .u-private { background: var(--u-private); }
            .u-surrogate { background: var(--u-surrogate); } .u-nonchar { background: var(--u-nonchar); }
            .u-unassigned { background: var(--u-unassigned); }
            .planes td { vertical-align: middle; } .planes .barcol { min-width: 220px; }
            .planecards { display: flex; flex-wrap: wrap; gap: 16px; margin: 16px 0; }
            .planecard { border: 1px solid var(--line); border-radius: 12px; padding: 12px 14px 14px; width: 204px; background: var(--soft); }
            .planecard.unused { border-style: dashed; background: transparent; color: var(--muted); }
            .ptitle { font-size: 0.9rem; line-height: 1.45; }
            .ptitle b, .ptitle span { display: block; white-space: nowrap; overflow: hidden; text-overflow: ellipsis; }
            .prange { font-size: 0.74rem; color: var(--muted); margin-bottom: 8px; font-family: ui-monospace, "SF Mono", Menlo, monospace; }
            .pgrid { display: grid; grid-template-columns: repeat(16, 10px); gap: 1px; }
            .pgrid div { width: 10px; height: 10px; border-radius: 1px; }
            .pgrid div:hover { outline: 2px solid var(--fg); }
            .pnote { font-size: 0.76rem; color: var(--muted); margin-top: 8px; line-height: 1.55; }
            .ubar { height: 14px; border-radius: 4px; overflow: hidden; }
            .grid.umap td { height: 2.6em; font-family: ui-monospace, "SF Mono", Menlo, monospace; font-size: 0.72rem; }
            .grid.umap td span { background: var(--bg); color: var(--fg); padding: 0 3px; border-radius: 3px; opacity: 0.85; }
            .pill { padding: 0 8px; border-radius: 999px; color: var(--fg); font-size: 0.78rem; }
            .controls { display: flex; gap: 8px; margin: 16px 0 8px; }
            .controls button { font: inherit; font-size: 0.86rem; padding: 4px 14px; border: 1px solid var(--line);
              background: transparent; color: var(--fg); border-radius: 999px; cursor: pointer; }
            .controls button:hover { border-color: var(--fg); }
            .refs { font-size: 0.92rem; }
            pre { margin: 12px 0 20px; padding: 14px 18px; background: var(--head); border-radius: 8px; overflow-x: auto; line-height: 1.6; }
            pre code { padding: 0; background: none; font-size: 0.86rem; }
            ::selection { background: var(--fg); color: var(--bg); }
            """;

    private static final String JS = """
            const box = document.getElementById('only-diff');
            box.addEventListener('change', () => document.body.classList.toggle('only-diff', box.checked));

            // リンク先が折りたたみの中にあれば開いてから移動する
            function openTarget() {
              const id = decodeURIComponent(location.hash.slice(1));
              const el = id && document.getElementById(id);
              if (!el) return;
              for (let d = el.closest('details'); d; d = d.parentElement && d.parentElement.closest('details')) d.open = true;
              el.scrollIntoView({ block: 'start' });
            }
            window.addEventListener('hashchange', openTarget);
            document.addEventListener('click', e => {
              const a = e.target.closest('a[href^="#"]');
              if (a && a.getAttribute('href') === location.hash) setTimeout(openTarget, 0);
            });
            openTarget();

            // 表示中の節に対応する目次の項目を強調する
            const tocLinks = [...document.querySelectorAll('.toc a')];
            const targets = tocLinks.map(a => document.getElementById(a.getAttribute('href').slice(1))).filter(Boolean);
            function markActive() {
              let current = targets[0];
              for (const t of targets) {
                if (t.getBoundingClientRect().top <= 120) current = t;
              }
              let key = 'sjis';
              tocLinks.forEach(a => {
                const on = a.getAttribute('href') === '#' + current.id;
                a.classList.toggle('active', on);
                if (on) key = a.dataset.legend || 'none';
              });
              document.querySelectorAll('.sl-block').forEach(b => b.classList.toggle('shown', b.dataset.legend === key));
            }
            // 凡例の開閉。前回の状態を覚えておき、なければ狭い画面では閉じておく
            const legendBox = document.querySelector('.side-legend');
            const legendBtn = legendBox.querySelector('.sl-title');
            function setLegend(open) {
              legendBox.classList.toggle('collapsed', !open);
              legendBtn.setAttribute('aria-expanded', open);
            }
            let saved = null;
            try { saved = localStorage.getItem('legend-open'); } catch (e) {}
            setLegend(saved === null ? window.innerWidth >= 1100 : saved === '1');
            legendBtn.addEventListener('click', () => {
              const open = legendBox.classList.contains('collapsed');
              setLegend(open);
              try { localStorage.setItem('legend-open', open ? '1' : '0'); } catch (e) {}
            });
            document.addEventListener('scroll', markActive, { passive: true });
            markActive();

            document.querySelectorAll('[data-toggle]').forEach(b => b.addEventListener('click', () => {
              const open = b.dataset.toggle === 'open';
              b.closest('section').querySelectorAll('details.block').forEach(d => d.open = open);
            }));
            """;
}
