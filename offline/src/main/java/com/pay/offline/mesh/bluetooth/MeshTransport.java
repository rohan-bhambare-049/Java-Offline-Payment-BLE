package com.pay.offline.mesh.bluetooth;

/**
 * The radio boundary of a mesh node.
 *
 * A MeshNode never talks to Bluetooth APIs directly. It talks
 * only to this interface. That is what makes the node real and
 * testable at the same time: the node logic, fragmentation,
 * reassembly, duplicate suppression, TTL forwarding, and bridge
 * upload are pure Java and run on any JVM, while the radio
 * itself is supplied by the platform implementation.
 *
 * On Android, AndroidBleTransport in the android-mesh folder
 * implements this interface with BluetoothLeAdvertiser,
 * BluetoothLeScanner, BluetoothGatt, and BluetoothGattServer.
 * In a unit test, a fake implementation records frames and
 * injects frames from a peer, which is how the node is tested
 * without a radio. A fake transport in a test only replaces
 * the radio driver, the same way a test replaces a database
 * with a mock. The node logic itself is the real code under test.
 *
 * Contract for an implementation:
 * - start(listener) must begin advertising and scanning, and
 *   must deliver every complete frame received over GATT to
 *   listener.onFrameReceived, with the peer Bluetooth address
 *   as the peer id.
 * - sendFrame(frame) must write the frame to every currently
 *   connected peer, both as a GATT client write and as a GATT
 *   server notification, so packets flow in both directions.
 * - stop() must stop advertising and scanning and close every
 *   GATT connection and server.
 * - Frames are at most MeshGattConstants.MAX_FRAME_BYTES long.
 *   An implementation must not split a frame further and must
 *   not join frames. One sendFrame call is one GATT write.
 */
public interface MeshTransport {

    void start(MeshFrameListener listener);

    void stop();

    void sendFrame(byte[] frame);
}
