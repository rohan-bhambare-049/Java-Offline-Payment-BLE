package com.pay.offline.mesh.bluetooth;

/**
 * Callback for frames arriving over Bluetooth.
 *
 * peerAddress is the Bluetooth address of the sending phone, as
 * reported by the Android stack. It is used only for logging and
 * debugging. It is never used for duplicate suppression or for
 * settlement, because a Bluetooth address can change with
 * address randomisation. Duplicate suppression uses the packet
 * id, and server deduplication uses the ciphertext hash.
 */
public interface MeshFrameListener {

    void onFrameReceived(byte[] frame, String peerAddress);
}
