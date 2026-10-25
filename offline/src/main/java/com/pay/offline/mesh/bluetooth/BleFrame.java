package com.pay.offline.mesh.bluetooth;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.UUID;
import java.util.zip.CRC32;

/**
 * One Bluetooth frame: one chunk of one packet.
 *
 * A MeshPacket as JSON is typically several hundred bytes, far
 * larger than a single GATT write, so a packet is split into
 * frames before it is sent over Bluetooth and reassembled on
 * receipt. This is the real fragmentation layer, not a
 * simulation shortcut. The same frame format is used by the
 * Android transport and by the pure Java node, so a frame built
 * on one phone decodes on any other phone running this code.
 *
 * Wire layout, big endian, 24 byte header plus payload:
 * - bytes 0 to 15: packet id, most significant bits then least
 *   significant bits, 8 bytes each.
 * - bytes 16 to 17: total chunk count, unsigned short.
 * - bytes 18 to 19: chunk index, unsigned short, zero based.
 * - bytes 20 to 23: CRC32 of the payload only, unsigned int.
 * - bytes 24 onwards: payload slice, 1 to 104 bytes, so a frame
 *   is at most 128 bytes in total.
 *
 * CRC32 is a transport integrity check, not a security check.
 * Bluetooth already checks frames at the link layer, but a
 * corrupt GATT write or a bug in chunk handling must still be
 * caught here, before a corrupt chunk poisons a reassembly
 * buffer. Security still comes from AES GCM on the server, and
 * from the inner SHA payload hash, after reassembly.
 */
public record BleFrame(
        UUID packetId,
        int totalChunks,
        int chunkIndex,
        byte[] payload
) {

    public static final int HEADER_BYTES = 24;
    public static final int MAX_PAYLOAD_BYTES =
            MeshGattConstants.MAX_FRAME_BYTES - HEADER_BYTES;

    public byte[] encode() {
        ByteBuffer buffer = ByteBuffer.allocate(HEADER_BYTES + payload.length);
        buffer.order(ByteOrder.BIG_ENDIAN);
        buffer.putLong(packetId.getMostSignificantBits());
        buffer.putLong(packetId.getLeastSignificantBits());
        buffer.putShort((short) totalChunks);
        buffer.putShort((short) chunkIndex);
        CRC32 crc = new CRC32();
        crc.update(payload);
        buffer.putInt((int) crc.getValue());
        buffer.put(payload);
        return buffer.array();
    }

    public static BleFrame decode(byte[] frame) {
        if (frame == null || frame.length < HEADER_BYTES + 1
                || frame.length > MeshGattConstants.MAX_FRAME_BYTES) {
            throw new IllegalArgumentException("Bluetooth frame has an invalid length");
        }
        ByteBuffer buffer = ByteBuffer.wrap(frame);
        buffer.order(ByteOrder.BIG_ENDIAN);
        long most = buffer.getLong();
        long least = buffer.getLong();
        int totalChunks = buffer.getShort() & 0xFFFF;
        int chunkIndex = buffer.getShort() & 0xFFFF;
        int expectedCrc = buffer.getInt();
        byte[] payload = new byte[frame.length - HEADER_BYTES];
        buffer.get(payload);
        CRC32 crc = new CRC32();
        crc.update(payload);
        if ((int) crc.getValue() != expectedCrc) {
            throw new IllegalArgumentException("Bluetooth frame failed its CRC check");
        }
        if (totalChunks <= 0 || chunkIndex < 0 || chunkIndex >= totalChunks) {
            throw new IllegalArgumentException("Bluetooth frame has an invalid chunk index");
        }
        return new BleFrame(new UUID(most, least), totalChunks, chunkIndex, payload);
    }
}
