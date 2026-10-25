package com.pay.offline.mesh.bluetooth;

import com.pay.offline.mesh.dto.MeshPacket;

import java.util.UUID;

/**
 * JSON conversion for a whole MeshPacket, for Bluetooth transport.
 *
 * This is separate from MeshPayloadCodec, which converts the
 * inner payment payload before encryption. This codec converts
 * the outer packet, packet id, origin, times, TTL, hop count,
 * and ciphertext, which is what actually travels over Bluetooth.
 * It is hand written for the same reason as the payload codec:
 * no JSON library dependency, and the same code ports directly
 * to an Android app.
 *
 * The ciphertext is a Base64 string, so it needs no special
 * escaping beyond the usual quote and backslash handling, but
 * the origin device id is a free string, so it is escaped
 * properly on write and unescaped on read.
 */
public class MeshPacketJsonCodec {

    public String toJson(MeshPacket packet) {
        StringBuilder json = new StringBuilder();
        json.append("{\"packetId\":");
        appendString(json, packet.packetId().toString());
        json.append(",\"originDeviceId\":");
        appendString(json, packet.originDeviceId());
        json.append(",\"createdAt\":");
        json.append(packet.createdAt());
        json.append(",\"ttl\":");
        json.append(packet.ttl());
        json.append(",\"hopCount\":");
        json.append(packet.hopCount());
        json.append(",\"ciphertext\":");
        appendString(json, packet.ciphertext());
        json.append("}");
        return json.toString();
    }

    public MeshPacket fromJson(String json) {
        if (json == null || json.isBlank()) {
            throw new IllegalArgumentException("Mesh packet JSON is empty");
        }
        try {
            return new MeshPacket(
                    UUID.fromString(readString(json, "packetId")),
                    readString(json, "originDeviceId"),
                    readLong(json, "createdAt"),
                    (int) readLong(json, "ttl"),
                    (int) readLong(json, "hopCount"),
                    readString(json, "ciphertext")
            );
        } catch (IllegalArgumentException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw new IllegalArgumentException("Mesh packet JSON is not valid", exception);
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

    private long readLong(String json, String field) {
        int start = valueStart(json, field);
        int end = start;
        while (end < json.length() && json.charAt(end) != ',' && json.charAt(end) != '}') {
            end++;
        }
        return Long.parseLong(json.substring(start, end).trim());
    }
}
