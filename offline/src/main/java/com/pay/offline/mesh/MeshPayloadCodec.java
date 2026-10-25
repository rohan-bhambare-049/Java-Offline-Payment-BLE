package com.pay.offline.mesh;

import com.pay.offline.mesh.dto.MeshPaymentPayload;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * JSON conversion for the encrypted payload only.
 *
 * This codec is hand written on purpose, for two reasons. First,
 * it keeps the payload format independent of the JSON library
 * version bundled with Spring Boot, so the same format can be
 * ported to an Android app without a JSON dependency decision.
 * Second, the payload shape is fixed and small: six strings and
 * numbers plus one hash, so a small parser for that exact shape
 * is easier to audit than a general purpose mapper.
 *
 * Serialisation writes fields in a fixed order, with the amount
 * as a JSON number. Parsing accepts the amount as a number or as
 * a quoted string, so a phone app that serialises the amount as
 * a string is still accepted. String values are escaped on write
 * and unescaped on read, including backslash, quote, and the
 * common control characters.
 */
@Component
public class MeshPayloadCodec {

    public String toJson(MeshPaymentPayload payload) {
        StringBuilder json = new StringBuilder();
        json.append("{\"paymentId\":");
        appendString(json, payload.paymentId().toString());
        json.append(",\"senderAccountNumber\":");
        appendString(json, payload.senderAccountNumber());
        json.append(",\"receiverAccountNumber\":");
        appendString(json, payload.receiverAccountNumber());
        json.append(",\"amount\":");
        json.append(payload.amount().toPlainString());
        json.append(",\"currency\":");
        appendString(json, payload.currency());
        json.append(",\"createdAt\":");
        json.append(payload.createdAt());
        json.append(",\"payloadHash\":");
        appendString(json, payload.payloadHash());
        json.append("}");
        return json.toString();
    }

    public MeshPaymentPayload fromJson(String json) {
        if (json == null || json.isBlank()) {
            throw new IllegalArgumentException("Mesh payload is empty");
        }
        try {
            return new MeshPaymentPayload(
                    UUID.fromString(readString(json, "paymentId")),
                    readString(json, "senderAccountNumber"),
                    readString(json, "receiverAccountNumber"),
                    readDecimal(json, "amount"),
                    readString(json, "currency"),
                    readLong(json, "createdAt"),
                    readString(json, "payloadHash")
            );
        } catch (IllegalArgumentException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw new IllegalArgumentException("Mesh payload is not valid JSON", exception);
        }
    }

    private void appendString(StringBuilder json, String value) {
        json.append('"');
        for (int i = 0; i < value.length(); i++) {
            char ch = value.charAt(i);
            switch (ch) {
                case '"' -> json.append("\\\"");
                case '\\' -> json.append("\\\\");
                case '\n' -> json.append("\\n");
                case '\r' -> json.append("\\r");
                case '\t' -> json.append("\\t");
                default -> {
                    if (ch < 0x20) {
                        json.append(String.format("\\u%04x", (int) ch));
                    } else {
                        json.append(ch);
                    }
                }
            }
        }
        json.append('"');
    }

    private int valueStart(String json, String field) {
        int keyIndex = json.indexOf("\"" + field + "\"");
        if (keyIndex < 0) {
            throw new IllegalArgumentException("Missing field: " + field);
        }
        int colonIndex = json.indexOf(':', keyIndex + field.length() + 2);
        if (colonIndex < 0) {
            throw new IllegalArgumentException("Missing value for field: " + field);
        }
        int start = colonIndex + 1;
        while (start < json.length() && Character.isWhitespace(json.charAt(start))) {
            start++;
        }
        return start;
    }

    private String readString(String json, String field) {
        int start = valueStart(json, field);
        if (start >= json.length() || json.charAt(start) != '"') {
            throw new IllegalArgumentException("Field is not a string: " + field);
        }
        StringBuilder value = new StringBuilder();
        for (int i = start + 1; i < json.length(); i++) {
            char ch = json.charAt(i);
            if (ch == '"') {
                return value.toString();
            }
            if (ch == '\\' && i + 1 < json.length()) {
                char next = json.charAt(++i);
                switch (next) {
                    case '"' -> value.append('"');
                    case '\\' -> value.append('\\');
                    case '/' -> value.append('/');
                    case 'n' -> value.append('\n');
                    case 'r' -> value.append('\r');
                    case 't' -> value.append('\t');
                    case 'b' -> value.append('\b');
                    case 'f' -> value.append('\f');
                    case 'u' -> {
                        if (i + 4 >= json.length()) {
                            throw new IllegalArgumentException("Bad unicode escape in field: " + field);
                        }
                        value.append((char) Integer.parseInt(json.substring(i + 1, i + 5), 16));
                        i += 4;
                    }
                    default -> throw new IllegalArgumentException("Bad escape in field: " + field);
                }
            } else {
                value.append(ch);
            }
        }
        throw new IllegalArgumentException("Unterminated string for field: " + field);
    }

    private String readRawValue(String json, String field) {
        int start = valueStart(json, field);
        if (start < json.length() && json.charAt(start) == '"') {
            return readString(json, field);
        }
        int end = start;
        while (end < json.length() && json.charAt(end) != ',' && json.charAt(end) != '}') {
            end++;
        }
        return json.substring(start, end).trim();
    }

    private BigDecimal readDecimal(String json, String field) {
        return new BigDecimal(readRawValue(json, field));
    }

    private long readLong(String json, String field) {
        return Long.parseLong(readRawValue(json, field));
    }
}
