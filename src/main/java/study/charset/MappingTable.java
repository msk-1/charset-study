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
                <title>日本語の文字コード対応表</title>
                <style>
                """).append(CSS).append("""
                </style>
                </head>
                <body>
                <main>
                <h1>日本語の文字コード対応表</h1>
                <p class="subtitle">Shift_JIS・windows-31j・Unicode・UTF-8・MJ+</p>
                """);
        h.append("""
                <p class="lead-text">日本語の文字コードを、文字集合と符号化方式の両面から見比べるための資料。次の内容をまとめている。</p>
                <ul class="lead-list">
                <li><b>文字集合と符号化方式</b>：どの符号化方式がどの文字集合を扱うかの対応表と、同じ文字を各方式で書いたバイト列</li>
                <li><b>Shift_JIS と windows-31j</b>：バイト列をデコードしたときにどの Unicode の文字になるか。windows-31j が拡張した範囲を色で示す</li>
                <li><b>JIS の区点</b>：1〜120 区に何が割り当てられているかの全体図</li>
                <li><b>UTF-8</b>：コードポイントからバイト列への変換規則と、Shift_JIS 系とのバイト数の違い</li>
                <li><b>Unicode</b>：17 の面と BMP の使い道。どこが文字・私用領域・未割り当てか</li>
                <li><b>MJ 文字と MJ+</b>：行政で使う大きな文字集合と、Unicode で表すときの注意</li>
                </ul>
                """);
        h.append("<p class=\"lead-text\">表と数値は手書きではなく、Java の <code>Charset</code> と Unicode のデータ（JDK ")
                .append(esc(System.getProperty("java.version")))
                .append("）から <code>MappingTable.java</code> で生成した。MJ 文字と MJ+ の説明は公開資料による。</p>\n");

        h.append("""
                <nav class="toc" aria-label="目次"><b>目次</b>
                <span class="tgroup">基礎</span>
                <a href="#schemes" data-legend="none">文字集合と符号化方式</a>
                <span class="tgroup">Shift_JIS・windows-31j</span>
                <a href="#legend" data-legend="sjis">凡例</a>
                <a href="#overview" data-legend="sjis">全体図：1 バイト目</a>
                <a href="#kuten" data-legend="kuten">区点の全体図</a>
                <a href="#single" data-legend="sjis">1 バイト文字</a>
                <a href="#double" data-legend="sjis">2 バイト文字</a>
                <a class="sub" href="#double-ext" data-legend="sjis">windows-31j の拡張</a>
                <a class="sub" href="#double-jis" data-legend="sjis">JIS X 0208</a>
                <span class="tgroup">Unicode</span>
                <a href="#utf8" data-legend="none">コードポイントと UTF-8</a>
                <a href="#umap" data-legend="unicode">Unicode の全体図</a>
                <a class="sub" href="#planes-map" data-legend="unicode">面ごとの全体図</a>
                <a href="#unicode" data-legend="none">Unicode から見た差分</a>
                <span class="tgroup">行政の文字</span>
                <a href="#mj" data-legend="none">MJ 文字と MJ+</a>
                """);
        h.append("</nav>\n").append(sideLegend());
        renderSchemes(h);
        renderLegend(h, singleByte, doubleByte);
        renderOverview(h, singleByte, doubleByte);
        renderKutenMap(h, doubleByte);
        renderSingleByte(h, singleByte);
        renderDoubleByte(h, doubleByte);
        renderUtf8(h, singleByte, doubleByte);
        renderUnicodeMap(h);
        renderUnicodeSide(h, singleByte, doubleByte);
        renderMj(h, doubleByte);

        h.append("</main>\n<script>\n").append(JS).append("</script>\n</body>\n</html>\n");
        return h.toString();
    }

    /** サイドパネルの凡例。見ている節に合わせて、JS で表示するブロックを切り替える。 */
    private static String sideLegend() {
        StringBuilder l = new StringBuilder("<aside class=\"side-legend\" aria-label=\"凡例\">"
                + "<button type=\"button\" class=\"sl-title\" aria-expanded=\"true\"><span>凡例</span><span class=\"sl-caret\"></span>"
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

    // ---- 文字集合と符号化方式 ----

    /** 文字集合 × 符号化方式の対応表の 1 行。cells は SCHEME_SETS と同じ順。 */
    record Scheme(String name, String javaName, String[] cells, String bytes, String asciiInTrail) {
    }

    private static final List<String> SCHEME_SETS = List.of(
            "ASCII", "JIS X 0201 ローマ字", "JIS X 0201 カタカナ", "JIS X 0208", "JIS X 0212", "JIS X 0213",
            "Windows の拡張", "Unicode");

    /** 規格の定義なので手で書く。○ は直接扱う、(○) は Unicode に含まれるので表せる。 */
    private static final List<Scheme> SCHEMES = List.of(
            new Scheme("US-ASCII", "US-ASCII",
                    new String[] {"○", "", "", "", "", "", "", ""}, "1", "—"),
            new Scheme("ISO-2022-JP（JIS コード）", "ISO-2022-JP",
                    new String[] {"○", "○", "△", "○", "", "", "", ""}, "1〜2（エスケープシーケンスで切り替える）", "出る"),
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

    private static void renderSchemes(StringBuilder h) {
        h.append("""
                <section id="schemes">
                <h2>文字集合と符号化方式</h2>
                <p>「文字コード」という言葉は、2 つの別のものをまとめて指している。</p>
                <ul>
                <li><b>文字集合</b>（符号化文字集合）：どの文字を扱うかと、各文字に振る番号を決めたもの。
                JIS X 0208 なら区点番号（「漢」は 20 区 33 点）、Unicode ならコードポイント（「漢」は U+6F22）</li>
                <li><b>符号化方式</b>：その番号を、ファイルや通信で使うバイト列にする規則。
                同じ JIS X 0208 の「漢」でも、Shift_JIS なら 8A BF、EUC-JP なら B4 C1 になる</li>
                </ul>
                <p>1 つの符号化方式が複数の文字集合を組み合わせて使うことが多い。
                Shift_JIS は JIS X 0201（1 バイト）と JIS X 0208（2 バイト）を、UTF-8 は Unicode だけを使う。
                Java の <code>Charset</code> は文字集合ではなく符号化方式に当たる。</p>
                <h3>どの符号化方式がどの文字集合を扱うか</h3>
                """);
        h.append("<div class=\"scroll\"><table class=\"list\">\n<thead><tr><th>符号化方式</th>");
        SCHEME_SETS.forEach(s -> h.append("<th>").append(esc(s)).append("</th>"));
        h.append("<th>1 文字のバイト数</th><th>ASCII の値が 2 バイト目以降に出るか</th><th>Java の名前</th></tr></thead>\n<tbody>\n");
        for (Scheme s : SCHEMES) {
            h.append("<tr><td>").append(esc(s.name)).append("</td>");
            for (String c : s.cells) {
                h.append(c.isEmpty() ? "<td class=\"x\">—</td>" : "<td class=\"c\">" + esc(c) + "</td>");
            }
            h.append("<td>").append(esc(s.bytes)).append("</td><td>").append(esc(s.asciiInTrail))
                    .append("</td><td class=\"mono\">").append(esc(s.javaName)).append("</td></tr>\n");
        }
        h.append("</tbody>\n</table></div>\n");
        h.append("""
                <ul class="note">
                <li>○：その文字集合を直接使う。(○)：Unicode がその文字集合の文字を含んでいるので表せる。—：表せない</li>
                <li>Shift_JIS 系の 1 バイト部分は、規格上は JIS X 0201 ローマ字（5C が ¥、7E が ‾）。
                JDK の Shift_JIS と x-SJIS_0213 は 5C と 7E を ASCII の \\ と ~ として扱うので △ とした。windows-31j は 1 バイト部分が ASCII</li>
                <li>ISO-2022-JP の規格（RFC 1468）は半角カナを含まない。JDK は ESC ( I で切り替えて読み書きできるので △ とした</li>
                <li>JIS X 0213 は JIS X 0208 の文字をすべて含み、第 3・第 4 水準などを加えたもの。
                Windows の拡張は NEC 特殊文字・NEC 選定 IBM 拡張文字・IBM 拡張文字で、下の「2 バイト文字」の節に詳しい</li>
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

    private static void renderLegend(StringBuilder h, List<Cell> singleByte, Map<Integer, List<Cell>> doubleByte) {
        Map<Cat, Integer> counts = new EnumMap<>(Cat.class);
        singleByte.forEach(c -> counts.merge(c.cat, 1, Integer::sum));
        doubleByte.values().forEach(cells -> cells.forEach(c -> counts.merge(c.cat, 1, Integer::sum)));

        h.append("<section id=\"legend\">\n<h2>凡例</h2>\n<table class=\"legend\">\n")
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
                </section>
                """);
    }

    /** 1 バイト目 00〜FF が何に使われているかの全体図。 */
    private static void renderOverview(StringBuilder h, List<Cell> singleByte, Map<Integer, List<Cell>> doubleByte) {
        h.append("<section id=\"overview\">\n<h2>全体図：1 バイト目の使い道</h2>\n")
                .append("<p>1 バイト目の値だけで、1 バイト文字か、2 バイト文字の始まりかが決まる。"
                        + "2 バイト文字のマスを押すと、その 1 バイト目の表に移動する。</p>\n")
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
                </section>
                """);
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

        h.append("<section id=\"kuten\">\n<h2>区点の全体図</h2>\n")
                .append("<p>2 バイト文字を JIS の区点番号で並べた図。縦が区（1〜120）、横が点（1〜94）で、1 マスが 1 文字。"
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
                        + "上の「区点の全体図」の 120 区 × 94 点がその全部で、6 万〜7 万字はどう並べても収まらない。"
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
                <h3>これまでに出てきた文字の場所</h3>
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
                .append("<p>上の表のコードポイント（U+XXXX）は、Unicode の文字の番号。"
                        + "UTF-8 はこの番号を、大きさに応じて 1〜4 バイトに分けて書く。"
                        + "各マスにカーソルを合わせると、その文字の UTF-8 のバイト列も出る。</p>\n");

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

    /** Unicode の側から見た差分。 */
    private static void renderUnicodeSide(StringBuilder h, List<Cell> singleByte, Map<Integer, List<Cell>> doubleByte) {
        h.append("<section id=\"unicode\">\n<h2>Unicode から見た差分</h2>\n");

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
              --bg: #fbfbfa; --fg: #1f2328; --muted: #6a737d; --line: #d8dadd; --head: #f0f1f3;
              --same: #ffffff; --jis: #e8eef7; --diff: #ffd8a8; --sjis: #ffc9c9;
              --nec: #c3fae8; --necibm: #d0bfff; --ibm: #a5d8ff; --gaiji: #ced4da; --w31j: #fff3bf;
              --one: #ffffff; --lead: #e9ecef; --none: repeating-linear-gradient(45deg, #eceef0 0 3px, #f8f9fa 3px 7px); --na: repeating-linear-gradient(45deg, #eceef0 0 3px, #f8f9fa 3px 7px);
              --accent: #1c7ed6; --u-assigned: #4dabf7; --u-private: #ffa94d; --u-surrogate: #f06595; --u-nonchar: #343a40; --u-unassigned: #e9ecef; --k-sym: #adb5bd; --k-alnum: #ffe066; --k-hira: #faa2c1; --k-kata: #e64980; --k-greek: #ffc078; --k-cyr: #fd7e14; --k-line: #868e96; --k-kanji1: #d9a95b; --k-kanji2: #f1dcb4; --k-cell-empty: #eceef0;
            }
            @media (prefers-color-scheme: dark) {
              :root:not([data-theme="light"]) {
                --bg: #16181c; --fg: #e6e8eb; --muted: #9aa1a9; --line: #343840; --head: #22252b;
                --same: #1c1f24; --jis: #24426e; --diff: #5c3d12; --sjis: #5c1f24;
                --nec: #0b7a5e; --necibm: #5f3dc4; --ibm: #1864ab; --gaiji: #495057; --w31j: #4d4314;
                --one: #2c3038; --lead: #343a40; --none: repeating-linear-gradient(45deg, #1a1c20 0 3px, #24272d 3px 7px); --na: repeating-linear-gradient(45deg, #1a1c20 0 3px, #24272d 3px 7px);
                --accent: #4dabf7; --u-assigned: #1c7ed6; --u-private: #e8590c; --u-surrogate: #c2255c; --u-nonchar: #f1f3f5; --u-unassigned: #2b2f36; --k-sym: #6c757d; --k-alnum: #b59f1b; --k-hira: #a8436b; --k-kata: #d6336c; --k-greek: #b8742a; --k-cyr: #d9480f; --k-line: #495057; --k-kanji1: #a7834a; --k-kanji2: #6b5a3d; --k-cell-empty: #202328;
              }
            }
            :root[data-theme="dark"] {
              --bg: #16181c; --fg: #e6e8eb; --muted: #9aa1a9; --line: #343840; --head: #22252b;
              --same: #1c1f24; --jis: #24426e; --diff: #5c3d12; --sjis: #5c1f24;
              --nec: #0b7a5e; --necibm: #5f3dc4; --ibm: #1864ab; --gaiji: #495057; --w31j: #4d4314;
              --one: #2c3038; --lead: #343a40; --none: repeating-linear-gradient(45deg, #1a1c20 0 3px, #24272d 3px 7px); --na: repeating-linear-gradient(45deg, #1a1c20 0 3px, #24272d 3px 7px);
              --accent: #4dabf7; --u-assigned: #1c7ed6; --u-private: #e8590c; --u-surrogate: #c2255c; --u-nonchar: #f1f3f5; --u-unassigned: #2b2f36; --k-sym: #6c757d; --k-alnum: #b59f1b; --k-hira: #a8436b; --k-kata: #d6336c; --k-greek: #b8742a; --k-cyr: #d9480f; --k-line: #495057; --k-kanji1: #a7834a; --k-kanji2: #6b5a3d; --k-cell-empty: #202328;
            }
            * { box-sizing: border-box; }
            body { margin: 0; background: var(--bg); color: var(--fg);
              font-family: "Hiragino Sans", "Noto Sans JP", "Yu Gothic", sans-serif; line-height: 1.7; }
            main { max-width: 1100px; margin: 0 auto; padding: 24px 16px 80px; }
            h1 { font-size: 1.6rem; margin: 0 0 8px; }
            h2 { font-size: 1.25rem; margin: 48px 0 8px; padding-bottom: 4px; border-bottom: 2px solid var(--line); }
            h3 { font-size: 1.05rem; margin: 32px 0 8px; scroll-margin-top: 16px; }
            h3 small { font-weight: normal; color: var(--muted); margin-left: 8px; }
            a { color: var(--accent); }
            code, .mono { font-family: ui-monospace, "SF Mono", Menlo, monospace; font-size: 0.92em; }
            .lead-text { color: var(--muted); }
            .subtitle { margin: -4px 0 12px; color: var(--muted); font-size: 0.95rem; }
            .lead-list { color: var(--muted); margin: 4px 0 8px; padding-left: 1.4em; }
            .lead-list li { margin: 2px 0; } .lead-list b { color: var(--fg); font-weight: 600; }
            .note { color: var(--muted); font-size: 0.9rem; }
            .scroll { overflow-x: auto; }
            table { border-collapse: collapse; }
            th, td { border: 1px solid var(--line); }
            th { background: var(--head); font-weight: 600; font-family: ui-monospace, Menlo, monospace; font-size: 0.8rem; padding: 2px 6px; }
            .legend td, .legend th, .list td, .list th { padding: 4px 10px; text-align: left; font-family: inherit; font-size: 0.92rem; }
            .legend .num { text-align: right; font-variant-numeric: tabular-nums; }
            .list td.c { text-align: center; } .list td.x { text-align: center; color: var(--muted); }
            .list td.bytes { white-space: nowrap; }
            .swatch { display: inline-block; width: 1.4em; height: 1.1em; vertical-align: middle; border: 1px solid var(--line); border-radius: 3px; }
            .grid td { width: 3.6em; min-width: 3.6em; height: 3.4em; text-align: center; vertical-align: middle; padding: 2px; }
            .grid.overview td { height: 2.2em; font-family: ui-monospace, Menlo, monospace; font-size: 0.8rem; }
            .grid.overview a { display: block; text-decoration: none; color: inherit; font-weight: 600; }
            .grid .g { display: block; font-size: 1.25rem; line-height: 1.2; }
            .grid .g i { font-style: normal; font-size: 0.7rem; color: var(--muted); }
            .grid .u { display: block; font-family: ui-monospace, Menlo, monospace; font-size: 0.6rem; color: var(--muted); line-height: 1.2; }
            .grid .diff .g { font-size: 1rem; }
            .same { background: var(--same); } .jis { background: var(--jis); } .diff { background: var(--diff); }
            .sjis { background: var(--sjis); } .nec { background: var(--nec); } .necibm { background: var(--necibm); }
            .ibm { background: var(--ibm); } .gaiji { background: var(--gaiji); } .w31j { background: var(--w31j); }
            .lead { background: var(--lead); } .one { background: var(--one); } .none { background: var(--none); } .na { background: var(--na); }
            .bands { padding-left: 0; list-style: none; } .bands li { margin: 4px 0; }
            .jump { display: flex; flex-wrap: wrap; gap: 4px; margin: 12px 0; }
            .jump a { font-family: ui-monospace, Menlo, monospace; font-size: 0.8rem; padding: 2px 6px; border: 1px solid var(--line);
              border-radius: 4px; text-decoration: none; color: var(--fg); }
            .callout { padding: 8px 16px; border: 1px solid var(--line); border-radius: 6px; }
            .list .glyph, .glyph { font-size: 1.2rem; }
            .toggle { display: inline-flex; gap: 8px; align-items: center; margin-top: 8px; cursor: pointer; }
            body.only-diff .chars td.same { opacity: 0.25; }
            /* 狭い画面：上部に固定する横並びの目次 */
            .toc { position: sticky; top: 0; z-index: 10; background: var(--bg); display: flex; flex-wrap: wrap;
              gap: 4px 14px; align-items: center; margin: 16px 0; padding: 8px 16px; border: 1px solid var(--line);
              border-radius: 0 0 6px 6px; font-size: 0.85rem; }
            .toc .tgroup { display: none; }
            .toc a.active { font-weight: 600; }
            h2, h3, details { scroll-margin-top: 72px; }
            /* 広い画面：左のサイドパネルに縦に並べる */
            @media (min-width: 1100px) {
              body { padding-left: 240px; }
              .toc { position: fixed; top: 0; left: 0; bottom: 0; width: 240px; margin: 0; padding: 20px 12px 40px;
                border: 0; border-right: 1px solid var(--line); border-radius: 0; flex-direction: column; flex-wrap: nowrap;
                align-items: stretch; gap: 1px; overflow-y: auto; background: var(--head); font-size: 0.88rem; }
              .toc > b { font-size: 0.95rem; padding: 0 8px 8px; }
              .toc .tgroup { display: block; margin: 14px 8px 4px; font-size: 0.72rem; color: var(--muted);
                letter-spacing: 0.04em; }
              .toc a { display: block; padding: 4px 8px; border-radius: 5px; text-decoration: none; color: var(--fg);
                border-left: 3px solid transparent; }
              .toc a.sub { padding-left: 22px; font-size: 0.82rem; color: var(--muted); }
              .toc a:hover { background: var(--bg); }
              .toc a.active { background: var(--bg); color: var(--accent); border-left-color: var(--accent); font-weight: 600; }
              h2, h3, details { scroll-margin-top: 16px; }
            }
            /* 凡例：右上に浮かべて表示する。見出しを押すと折りたためる */
            .side-legend { position: fixed; top: 12px; right: 16px; z-index: 30; width: 236px; max-height: calc(100vh - 24px);
              overflow-y: auto; padding: 8px 14px 10px; background: var(--head); border: 1px solid var(--line); border-radius: 8px;
              box-shadow: 0 6px 24px rgba(0, 0, 0, 0.25); }
            .side-legend .sl-title { display: flex; align-items: center; justify-content: space-between; gap: 12px; width: 100%;
              padding: 2px 0; background: none; border: 0; color: var(--fg); font: inherit; font-size: 0.85rem; font-weight: 600;
              cursor: pointer; }
            .sl-caret::after { content: "▾"; color: var(--muted); }
            .side-legend .sl-body { margin-top: 6px; }
            .side-legend.collapsed { width: auto; padding-bottom: 8px; }
            .side-legend.collapsed .sl-body { display: none; }
            .side-legend.collapsed .sl-caret::after { content: "▸"; }
            .sl-block { display: none; } .sl-block.shown { display: block; }
            .sl-item { display: flex; align-items: center; gap: 8px; font-size: 0.78rem; line-height: 1.5; padding: 1px 0; }
            .sl-item .swatch { flex: none; width: 1.2em; height: 0.95em; }
            .sl-item small { margin-left: auto; color: var(--muted); font-size: 0.7rem; }
            .sl-sep { font-size: 0.7rem; color: var(--muted); margin: 8px 0 2px; }
            .sl-note { font-size: 0.75rem; color: var(--muted); margin-top: 4px; }
            .k-diff-swatch { box-shadow: inset 0 0 0 2px #e03131; }

            .block { border: 1px solid var(--line); border-radius: 6px; margin: 6px 0; }
            .block > summary { cursor: pointer; padding: 6px 12px; display: flex; align-items: center; gap: 10px; list-style: none; }
            .block > summary::-webkit-details-marker { display: none; }
            .block > summary::before { content: "▶"; font-size: 0.65em; color: var(--muted); transition: transform 0.15s; }
            .block[open] > summary::before { transform: rotate(90deg); }
            .block[open] > summary { border-bottom: 1px solid var(--line); }
            .block > summary:hover { background: var(--head); }
            .block > .scroll, .block > .callout { margin: 8px; }
            .badge { font-family: ui-monospace, Menlo, monospace; font-weight: 600; font-size: 0.85rem; padding: 1px 8px;
              border-radius: 4px; border: 1px solid var(--line); }
            .meta { margin-left: auto; color: var(--muted); font-size: 0.85rem; display: flex; gap: 8px; align-items: center; }
            .preview { color: var(--muted); font-size: 0.95rem; letter-spacing: 0.05em; white-space: nowrap; overflow: hidden; text-overflow: ellipsis; min-width: 0; }
            .k-sym { background: var(--k-sym); } .k-alnum { background: var(--k-alnum); } .k-hira { background: var(--k-hira); }
            .k-kata { background: var(--k-kata); } .k-greek { background: var(--k-greek); } .k-cyr { background: var(--k-cyr); }
            .k-line { background: var(--k-line); } .k-kanji1 { background: var(--k-kanji1); } .k-kanji2 { background: var(--k-kanji2); }
            .k-empty { background: var(--k-cell-empty); }
            .kregions td { vertical-align: middle; } .kregions tr.muted td { color: var(--muted); }
            .kregions .note { font-size: 0.8rem; }
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
            .kmap th { background: none; font-size: 0.6rem; line-height: 8px; height: 8px; color: var(--muted); font-weight: normal;
              text-align: right; padding-right: 4px; white-space: nowrap; }
            .kmap tr:first-child th { text-align: left; height: 14px; }
            .kmap th a { color: var(--muted); text-decoration: none; }
            .kmap th a:hover { color: var(--accent); }
            .kmap td.klabel { width: auto; background: none; border: 0; font-size: 0.75rem; color: var(--fg); padding-left: 10px;
              white-space: nowrap; vertical-align: middle; }
            .kmap td.klabel span { color: var(--muted); margin-left: 4px; }
            .kmap td.klabel.kx { color: var(--muted); } .kmap td.klabel.kx b { font-weight: normal; }
            .kmap tr:not(.kgap) td:hover:not(.klabel) { outline: 2px solid var(--accent); }
            .u-assigned { background: var(--u-assigned); } .u-private { background: var(--u-private); }
            .u-surrogate { background: var(--u-surrogate); } .u-nonchar { background: var(--u-nonchar); }
            .u-unassigned { background: var(--u-unassigned); }
            .planes td { vertical-align: middle; } .planes .barcol { min-width: 220px; }
            .planecards { display: flex; flex-wrap: wrap; gap: 14px; margin: 12px 0; }
            .planecard { border: 2px solid var(--fg); border-radius: 8px; padding: 8px 10px 10px; width: 200px; }
            .planecard.unused { border-style: dashed; border-color: var(--muted); color: var(--muted); }
            .ptitle { font-size: 0.9rem; line-height: 1.4; }
            .ptitle b, .ptitle span { display: block; white-space: nowrap; overflow: hidden; text-overflow: ellipsis; } .prange { font-size: 0.7rem; color: var(--muted); margin-bottom: 6px; }
            .pgrid { display: grid; grid-template-columns: repeat(16, 10px); gap: 1px; }
            .pgrid div { width: 10px; height: 10px; }
            .pgrid div:hover { outline: 2px solid var(--accent); }
            .pnote { font-size: 0.72rem; color: var(--muted); margin-top: 6px; line-height: 1.5; }
            .ubar { height: 18px; border-radius: 3px; border: 1px solid var(--line); }
            .grid.umap td { height: 2.6em; font-family: ui-monospace, Menlo, monospace; font-size: 0.75rem; }
            .grid.umap td span { background: var(--bg); color: var(--fg); padding: 0 3px; border-radius: 3px; opacity: 0.85; }
            .pill { padding: 0 8px; border-radius: 10px; color: var(--fg); font-size: 0.8rem; }
            .controls { display: flex; gap: 8px; margin: 8px 0 4px; }
            .controls button { font: inherit; font-size: 0.85rem; padding: 4px 12px; border: 1px solid var(--line);
              background: var(--head); color: var(--fg); border-radius: 4px; cursor: pointer; }
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
