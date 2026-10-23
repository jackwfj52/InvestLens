package org.jack.backend.common.json;

import tools.jackson.databind.JsonNode;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Comparator;
import java.util.HexFormat;

/** Exact UTF-8 serialization from G01; domain collection ordering happens before serialization. */
public final class CanonicalJson {
    public static final String ALGORITHM = "investlens-canonical-json-v1";
    private CanonicalJson() { }

    public static String sha256(JsonNode value) {
        try {
            return "sha256:" + HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(serialize(value).getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    public static String serialize(JsonNode value) {
        StringBuilder out = new StringBuilder();
        append(value, out);
        return out.toString();
    }

    private static void append(JsonNode value, StringBuilder out) {
        if (value.isObject()) {
            out.append('{');
            boolean first = true;
            for (var entry : value.properties().stream()
                    .sorted(Comparator.comparing(java.util.Map.Entry::getKey, CanonicalJson::compareCodePoints)).toList()) {
                if (!first) out.append(',');
                first = false;
                quote(entry.getKey(), out);
                out.append(':');
                append(entry.getValue(), out);
            }
            out.append('}');
        } else if (value.isArray()) {
            out.append('[');
            for (int i = 0; i < value.size(); i++) {
                if (i > 0) out.append(',');
                append(value.get(i), out);
            }
            out.append(']');
        } else if (value.isString()) {
            quote(value.asString(), out);
        } else if (value.isIntegralNumber()) {
            out.append(value.bigIntegerValue());
        } else if (value.isBoolean() || value.isNull()) {
            out.append(value.toString());
        } else {
            throw new IllegalArgumentException("Canonical JSON forbids floating point and non-JSON values");
        }
    }

    private static int compareCodePoints(String left, String right) {
        int a = 0, b = 0;
        while (a < left.length() && b < right.length()) {
            int x = left.codePointAt(a), y = right.codePointAt(b);
            if (x != y) return Integer.compare(x, y);
            a += Character.charCount(x);
            b += Character.charCount(y);
        }
        return Integer.compare(left.length() - a, right.length() - b);
    }

    private static void quote(String text, StringBuilder out) {
        out.append('"');
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            switch (c) {
                case '"' -> out.append("\\\"");
                case '\\' -> out.append("\\\\");
                case '\b' -> out.append("\\b");
                case '\t' -> out.append("\\t");
                case '\n' -> out.append("\\n");
                case '\f' -> out.append("\\f");
                case '\r' -> out.append("\\r");
                default -> {
                    if (c < 0x20) {
                        out.append("\\u00").append(Character.forDigit(c >>> 4, 16))
                                .append(Character.forDigit(c & 15, 16));
                    } else if (Character.isHighSurrogate(c)) {
                        if (i + 1 >= text.length() || !Character.isLowSurrogate(text.charAt(i + 1))) {
                            throw new IllegalArgumentException("Unpaired Unicode surrogate");
                        }
                        out.append(c).append(text.charAt(++i));
                    } else if (Character.isLowSurrogate(c)) {
                        throw new IllegalArgumentException("Unpaired Unicode surrogate");
                    } else out.append(c);
                }
            }
        }
        out.append('"');
    }
}
