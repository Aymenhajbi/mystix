package ma.mystix.format.edifact;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.Test;

class EdifactParserTest {

    @Test
    void readsServiceAdviceReleaseCharacterAndMessageHeader() throws IOException {
        EdifactParser.Interchange interchange = EdifactParser.parse(fixture("invrpt-snapshot.edi"));

        assertThat(interchange.syntax()).isEqualTo("UNOC");
        assertThat(interchange.sender()).isEqualTo("6110000000118");
        assertThat(interchange.controlReference()).isEqualTo("INV0002");
        EdifactParser.Message message = interchange.messages().getFirst();
        assertThat(message.type()).isEqualTo("INVRPT");
        assertThat(message.version() + message.release()).isEqualTo("D96A");
        assertThat(message.association()).isEqualTo("EAN006");
        // "INV?+SNAP-1": the release character makes "+" part of the document number.
        Segment bgm = message.segments().get(1);
        assertThat(bgm.tag()).isEqualTo("BGM");
        assertThat(bgm.value(1, 0)).isEqualTo("INV+SNAP-1");
    }

    @Test
    void keepsEachMessageByteForByteIncludingLineBreaks() throws IOException {
        byte[] bytes = fixture("recadv.edi");
        String text = new String(bytes, StandardCharsets.ISO_8859_1);
        int from = text.indexOf("UNH+");
        int to = text.indexOf('\'', text.indexOf("UNT+")) + 1;

        EdifactParser.Message message = EdifactParser.parse(bytes).messages().getFirst();

        assertThat(new String(message.raw(), StandardCharsets.ISO_8859_1)).isEqualTo(text.substring(from, to));
        assertThat(message.raw()).contains('\n');
    }

    @Test
    void decodesTheSyntaxCharacterSet() {
        String latin1 = "UNB+UNOC:3+SENDER+RECIPIENT+261005:0700+R1'UNH+1+ORDERS:D:96A:UN'BGM+220+CAFÉ-1+9'UNT+3+1'UNZ+1+R1'";
        String utf8 = latin1.replace("UNOC", "UNOY");

        assertThat(EdifactParser.parse(latin1.getBytes(StandardCharsets.ISO_8859_1)).messages().getFirst()
                .segments().get(1).value(1, 0)).isEqualTo("CAFÉ-1");
        assertThat(EdifactParser.parse(utf8.getBytes(StandardCharsets.UTF_8)).messages().getFirst()
                .segments().get(1).value(1, 0)).isEqualTo("CAFÉ-1");
        assertThatThrownBy(() -> EdifactParser.parse(latin1.replace("UNOC", "UNOX").getBytes(StandardCharsets.ISO_8859_1)))
                .isInstanceOf(EdifactException.class).hasMessageContaining("Unsupported syntax identifier UNOX");
    }

    @Test
    void readsCustomDelimitersFromUna() {
        String custom = "UNA*|.# !UNB|UNOA*3|S|R|261005*0700|R2!UNH|1|ORDERS*D*96A*UN!BGM|220|A#|B|9!UNT|3|1!UNZ|1|R2!";
        Segment bgm = EdifactParser.parse(custom.getBytes(StandardCharsets.US_ASCII)).messages().getFirst()
                .segments().get(1);
        assertThat(bgm.value(1, 0)).isEqualTo("A|B");
    }

    @Test
    void rejectsWrongControlCountsWithTheSegmentPosition() throws IOException {
        assertThatThrownBy(() -> EdifactParser.parse(fixture("orders-bad-count.edi")))
                .isInstanceOf(EdifactException.class)
                .hasMessageContaining("UNT declares 9 segment(s), the message has 5")
                .satisfies(e -> {
                    EdifactException ex = (EdifactException) e;
                    assertThat(ex.segmentTag()).isEqualTo("UNT");
                    assertThat(ex.segmentPosition()).isEqualTo(6);
                });
        String badUnz = "UNB+UNOA:3+S+R+261005:0700+R3'UNH+1+ORDERS:D:96A:UN'UNT+2+1'UNZ+2+R3'";
        assertThatThrownBy(() -> EdifactParser.parse(badUnz.getBytes(StandardCharsets.US_ASCII)))
                .hasMessageContaining("UNZ declares 2 message(s), the interchange has 1");
        String unterminated = "UNB+UNOA:3+S+R+261005:0700+R4'UNH+1+ORDERS:D:96A:UN";
        assertThatThrownBy(() -> EdifactParser.parse(unterminated.getBytes(StandardCharsets.US_ASCII)))
                .hasMessageContaining("Segment not terminated");
    }

    private static byte[] fixture(String name) throws IOException {
        try (InputStream in = EdifactParserTest.class.getResourceAsStream("/fixtures/edifact/" + name)) {
            return in.readAllBytes();
        }
    }
}
