package com.mod98.alpaca.spx.service.signal;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static com.mod98.alpaca.spx.service.signal.MessageShapeGate.Decision;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pins the 4 message types from Analyze_the_messages.pdf to the gate's decisions.
 *
 *  PDF Type 1 — preparation (text+image, "خليك جاهز/مراقب/لا تنفذ") → SKIP
 *  PDF Type 2 — entry ("دخول")                                      → PARSE_ENTRY
 *  PDF Type 3 — image only                                          → SKIP
 *  PDF Type 4 — reply (cancel/modify)                               → PARSE_REPLY
 */
class MessageShapeGateTest {

    private final MessageShapeGate gate = new MessageShapeGate();

    // ---- PDF Type 1: preparation message → SKIP ----

    @Test
    @DisplayName("Type1: prep caption with 'خليك جاهز ومراقب' + image → SKIP preparation")
    void type1_preparationWithImage_skips() {
        String caption = "عقد CALL بتاريخ 28 نوفمبر استرايك : 6845 "
                + "امر التنفيذ بالعقد بسعر : 3.3 خليك جاهز ومراقب لا تنفذ اعلى من سعر التنفيذ";
        Decision d = gate.classify(true, caption, false);
        assertThat(d).isEqualTo(Decision.SKIP_PREPARATION);
        assertThat(MessageShapeGate.reasonOf(d)).isEqualTo("preparation_message");
    }

    @Test
    @DisplayName("Type1: same prep text WITHOUT image still → SKIP preparation")
    void type1_preparationTextOnly_skips() {
        assertThat(gate.classify(false, "استرايك 6845 خليك جاهز ومراقب", false))
                .isEqualTo(Decision.SKIP_PREPARATION);
    }

    // ---- PDF Type 2: entry → PARSE_ENTRY ----

    @Test
    @DisplayName("Type2: 'دخول CALL' + image → PARSE_ENTRY")
    void type2_entryWithImage_parses() {
        String caption = "🟢 دخول CALL وقف الخسارة عند مستوى : 6763 الهدف المتوقع مستوى : 6827";
        assertThat(gate.classify(true, caption, false)).isEqualTo(Decision.PARSE_ENTRY);
    }

    @Test
    @DisplayName("Type2: text-only 'دخول' (no image) still → PARSE_ENTRY")
    void type2_entryTextOnly_parses() {
        assertThat(gate.classify(false, "دخول PUT", false)).isEqualTo(Decision.PARSE_ENTRY);
    }

    @Test
    @DisplayName("Type2 priority: 'دخول' beats preparation keywords in same message")
    void type2_entryTrigger_overridesPreparation() {
        // entry trigger is the ONLY execution gate — must win over prep words
        String caption = "دخول CALL خليك جاهز ومراقب";
        assertThat(gate.classify(true, caption, false)).isEqualTo(Decision.PARSE_ENTRY);
    }

    // ---- PDF Type 3: image only → SKIP ----

    @Test
    @DisplayName("Type3: image only, no caption → SKIP image_only")
    void type3_imageOnly_skips() {
        Decision d = gate.classify(true, null, false);
        assertThat(d).isEqualTo(Decision.SKIP_IMAGE_ONLY);
        assertThat(MessageShapeGate.reasonOf(d)).isEqualTo("image_only");

        assertThat(gate.classify(true, "   ", false)).isEqualTo(Decision.SKIP_IMAGE_ONLY);
    }

    // ---- PDF Type 4: reply (cancel/modify) → PARSE_REPLY ----

    @Test
    @DisplayName("Type4: reply with 'الغاء الأمر' → PARSE_REPLY")
    void type4_cancelReply_parsesReply() {
        assertThat(gate.classify(false, "الغاء الأمر", true)).isEqualTo(Decision.PARSE_REPLY);
    }

    @Test
    @DisplayName("Type4: reply is classified as reply even with image / prep words")
    void type4_replyAlwaysWins() {
        assertThat(gate.classify(true, "خليك جاهز ومراقب", true)).isEqualTo(Decision.PARSE_REPLY);
    }

    // ---- defensive edges ----

    @Test
    @DisplayName("Empty message (no text/image/reply) → SKIP empty")
    void empty_skips() {
        assertThat(gate.classify(false, null, false)).isEqualTo(Decision.SKIP_EMPTY);
    }

    @Test
    @DisplayName("Plain chatter (no trigger, no prep) → SKIP unclear")
    void chatter_skipsUnclear() {
        assertThat(gate.classify(false, "السوق اليوم اخضر", false)).isEqualTo(Decision.SKIP_UNCLEAR);
    }
}
