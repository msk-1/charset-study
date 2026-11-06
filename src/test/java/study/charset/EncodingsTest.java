package study.charset;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

/** 文字コードの性質を確かめるテスト。気になったことはここに足していく。 */
class EncodingsTest {

    @Test
    void ひらがなはUTF8で3バイト() {
        assertEquals("E3 81 82", Encodings.toHex("あ".getBytes(StandardCharsets.UTF_8)));
    }

    @Test
    void ひらがなはShiftJISで2バイト() {
        assertEquals("82 A0", Encodings.toHex("あ".getBytes(Charset.forName("Shift_JIS"))));
    }

    @Test
    void 絵文字はUTF16でサロゲートペアになる() {
        String sushi = "🍣";
        assertEquals(2, sushi.length());
        assertEquals(1, sushi.codePointCount(0, sushi.length()));
        assertEquals("D8 3C DF 63", Encodings.toHex(sushi.getBytes(StandardCharsets.UTF_16BE)));
    }

    @Test
    void ASCIIの範囲はUTF8でも1バイト() {
        assertEquals("41", Encodings.toHex("A".getBytes(StandardCharsets.UTF_8)));
    }

    @Test
    void windows31jでも表せない文字がある() {
        var encoder = Charset.forName("windows-31j").newEncoder();
        for (String s : new String[] {"𠮷", "🍣", "한", "€", "〜"}) {
            assertFalse(encoder.canEncode(s), s + " は windows-31j で表せないはず");
        }
        assertTrue(encoder.canEncode("①髙"));
    }

    @Test
    void UTF8のバイト列はUTF8として矛盾なく読める() {
        byte[] bytes = "あ①𠮷".getBytes(StandardCharsets.UTF_8);
        assertTrue(Encodings.findDecodeError(bytes, StandardCharsets.UTF_8).isEmpty());
    }

    @Test
    void windows31jの丸数字はShiftJISでは読めない() {
        byte[] bytes = "①".getBytes(Charset.forName("windows-31j"));
        var error = Encodings.findDecodeError(bytes, Charset.forName("Shift_JIS"));
        assertTrue(error.isPresent());
        assertEquals(0, error.get().position());
    }

    @Test
    void ShiftJISのバイト列はUTF8の規則に合わない() {
        byte[] bytes = "あ".getBytes(Charset.forName("Shift_JIS")); // 82 A0
        var error = Encodings.findDecodeError(bytes, StandardCharsets.UTF_8);
        assertTrue(error.isPresent());
        assertTrue(error.get().malformed());
    }
}
