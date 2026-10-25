package com.pay.offline.mesh.bluetooth;

import java.util.UUID;

/**
 * The real Bluetooth Low Energy identity of this mesh.
 *
 * Both phones must use exactly these UUIDs, otherwise they will
 * never discover each other. The service UUID is advertised by a
 * phone that is ready to receive packets. The characteristic UUID
 * is the single writable characteristic that carries packet
 * frames. A phone writes a frame to the characteristic of a peer
 * it is connected to, and a phone receives frames when a peer
 * writes to its own characteristic.
 *
 * The characteristic must be configured on Android with WRITE
 * and WRITE_NO_RESPONSE and NOTIFY properties. WRITE carries a
 * frame from a client to a server. NOTIFY carries a frame from a
 * server back to a connected client. Without both directions, a
 * pair of phones can only pass packets one way.
 *
 * MTU: the Android transport requests an MTU of 512 on every
 * connection. A frame is at most 128 bytes, so it fits even if
 * MTU negotiation falls back to a smaller value, as long as the
 * negotiated MTU is at least 131 bytes. If a peer refuses MTU
 * negotiation and stays at the legacy 23 byte MTU, that peer
 * cannot receive frames and the connection is dropped by the
 * transport. That is a real radio limit, stated here instead of
 * being hidden behind a frame size that pretends otherwise.
 */
public final class MeshGattConstants {

    public static final UUID SERVICE_UUID =
            UUID.fromString("9c4e1f2a-7b3d-4a8e-9f1c-2d5e6a7b8c9d0");

    public static final UUID PACKET_CHARACTERISTIC_UUID =
            UUID.fromString("9c4e1f2a-7b3d-4a8e-9f1c-2d5e6a7b8c9d1");

    public static final int REQUESTED_MTU = 512;

    public static final int MAX_FRAME_BYTES = 128;

    private MeshGattConstants() {
    }
}
