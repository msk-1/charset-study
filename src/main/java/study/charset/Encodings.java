package study.charset;

import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.Charset;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CoderResult;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.Optional;

/** 文字コード学習用の小さなユーティリティ。 */
public final class Encodings {

    private static final HexFormat HEX = HexFormat.ofDelimiter(" ").withUpperCase();

    private Encodings() {
    }

    /** バイト列を "E3 81 82" のような 16 進表記にする。 */
    public static String toHex(byte[] bytes) {
        return HEX.formatHex(bytes);
    }

    /** text を encodeWith でバイト列にし、それを decodeWith で読み直す（文字化けの再現）。 */
    public static String misdecode(String text, Charset encodeWith, Charset decodeWith) {
        return new String(text.getBytes(encodeWith), decodeWith);
    }

    /**
     * デコードで見つかった問題。
     *
     * @param position  問題のあるバイトの位置（先頭が 0）
     * @param bytes     問題のあるバイト列
     * @param malformed true ならその文字コードの規則に合わないバイト列、false なら規則には合うが対応する文字がない
     */
    public record DecodeError(int position, byte[] bytes, boolean malformed) {

        @Override
        public String toString() {
            return "位置 " + position + " の " + toHex(bytes) + " が"
                    + (malformed ? "規則に合わない" : "対応する文字がない");
        }
    }

    /**
     * bytes を cs で厳密にデコードし、最初に見つかった問題を返す。問題がなければ空。
     * new String(bytes, cs) と違い、読めないバイトを � に置き換えずに報告する。
     */
    public static Optional<DecodeError> findDecodeError(byte[] bytes, Charset cs) {
        // newDecoder() の既定は REPORT（置き換えずに報告する）
        CharsetDecoder decoder = cs.newDecoder();
        ByteBuffer in = ByteBuffer.wrap(bytes);
        CharBuffer out = CharBuffer.allocate((int) (bytes.length * (double) decoder.maxCharsPerByte()) + 1);

        CoderResult result = decoder.decode(in, out, true);
        if (result.isUnderflow()) {
            result = decoder.flush(out);
        }
        if (!result.isError()) {
            return Optional.empty();
        }
        int pos = in.position();
        byte[] bad = Arrays.copyOfRange(bytes, pos, Math.min(bytes.length, pos + result.length()));
        return Optional.of(new DecodeError(pos, bad, result.isMalformed()));
    }
}
