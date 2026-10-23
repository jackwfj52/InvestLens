package org.jack.backend.common.json;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.*;

class CanonicalJsonTests {
    private final JsonMapper json = JsonMapper.builder().build();

    @Test
    void keysUseUnicodeCodePointsAndStringsRemainUtf8WithoutNormalization() {
        var object = json.createObjectNode().put("\uD800\uDC00", "😀").put("\uE000", "e\u0301/黄金");
        assertThat(CanonicalJson.serialize(object)).isEqualTo("{\"\uE000\":\"e\u0301/黄金\",\"\uD800\uDC00\":\"😀\"}");
        assertThat(CanonicalJson.sha256(json.createObjectNode().put("x", "e\u0301")))
                .isNotEqualTo(CanonicalJson.sha256(json.createObjectNode().put("x", "é")));
    }

    @Test
    void escapingUsesLowercaseHexAndTheSpecifiedShortForms() {
        assertThat(CanonicalJson.serialize(json.createObjectNode().put("x", "\"\\\b\t\n\f\r\u001b/")))
                .isEqualTo("{\"x\":\"\\\"\\\\\\b\\t\\n\\f\\r\\u001b/\"}");
    }

    @Test
    void objectOrderIsIgnoredWhileArrayOrderAndIntegerPrecisionAreRetained() {
        assertThat(CanonicalJson.serialize(json.readTree("{\"z\":[3,2,1],\"n\":123456789012345678901234567890,\"a\":true,\"b\":null}")))
                .isEqualTo("{\"a\":true,\"b\":null,\"n\":123456789012345678901234567890,\"z\":[3,2,1]}");
        assertThat(CanonicalJson.sha256(json.readTree("{\"a\":1,\"b\":2}")))
                .isEqualTo(CanonicalJson.sha256(json.readTree("{\"b\":2,\"a\":1}")));
    }

    @Test
    void rejectsFloatsAndUnpairedSurrogatesInKeysAndValues() {
        assertThatThrownBy(() -> CanonicalJson.serialize(json.readTree("{\"x\":1.5}"))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> CanonicalJson.serialize(json.createObjectNode().put("x", "\uD800"))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> CanonicalJson.serialize(json.createObjectNode().put("\uDC00", "x"))).isInstanceOf(IllegalArgumentException.class);
    }
}
