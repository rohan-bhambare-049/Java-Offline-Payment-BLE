# Testing on Android

This is the Android testing guide for Java Offline Pay with
Bluetooth Mesh. It is the companion to the main README, and
it describes the exact files to attach to an Android app,
how to wire them up, and how to test them on two real
phones.

The backend alone cannot prove Bluetooth over the air.
These files are the thing to test on Android.

## Files to attach to the Android app

From the main project, copy these files into the Android
app module, in the same package,
`com.pay.offline.mesh.bluetooth`:

- `offline/src/main/java/com/pay/offline/mesh/bluetooth/MeshGattConstants.java`
- `offline/src/main/java/com/pay/offline/mesh/bluetooth/MeshTransport.java`
- `offline/src/main/java/com/pay/offline/mesh/bluetooth/MeshFrameListener.java`
- `offline/src/main/java/com/pay/offline/mesh/bluetooth/BleFrame.java`
- `offline/src/main/java/com/pay/offline/mesh/bluetooth/BleReassemblyBuffer.java`
- `offline/src/main/java/com/pay/offline/mesh/bluetooth/MeshPacketJsonCodec.java`
- `offline/src/main/java/com/pay/offline/mesh/bluetooth/MeshNode.java`
- `offline/src/main/java/com/pay/offline/mesh/bluetooth/MeshBridgeUploader.java`
- `offline/src/main/java/com/pay/offline/mesh/bluetooth/HttpMeshBridgeUploader.java`
- `offline/android-mesh/AndroidBleTransport.java`

Also copy, or port to Kotlin with the same field order and
the same hash and crypto layout, these classes:

- `offline/src/main/java/com/pay/offline/mesh/dto/MeshPacket.java`
- `offline/src/main/java/com/pay/offline/mesh/dto/MeshPaymentPayload.java`
- `offline/src/main/java/com/pay/offline/mesh/dto/MeshIngestionResult.java`
- `offline/src/main/java/com/pay/offline/mesh/dto/MeshIngestionOutcome.java`
- `offline/src/main/java/com/pay/offline/mesh/crypto/MeshHash.java`
- `offline/src/main/java/com/pay/offline/mesh/crypto/HybridCryptoService.java`
- `offline/src/main/java/com/pay/offline/mesh/MeshPayloadCodec.java`
- `offline/src/main/java/com/pay/offline/mesh/MeshPacketFactory.java`

`AndroidBleTransport.java` is not compiled by Maven, because
Android classes do not exist on a JVM. It is compiled by
Android Studio as part of the phone app, together with the
pure Java node files above.

## Wiring it up

```java
// Fetch once, while logged in, and cache on the phone.
// GET /api/v1/mesh/server-public-key
// The phone must never receive the server private key.

AndroidBleTransport transport = new AndroidBleTransport(context);
HttpMeshBridgeUploader uploader = new HttpMeshBridgeUploader(
        "https://your-backend-host",
        "phone-bridge-1",
        userEmail,
        userPassword
);
MeshNode node = new MeshNode(
        "phone-1",
        transport,
        uploader,
        hasInternetNow
);
node.start(); // when the mesh service starts
// node.sendPacket(packet); // when the user pays offline
// node.setHasInternet(true); node.uploadAllStored(); // when internet returns
// node.stop(); // when the mesh service stops
```

Replace `https://your-backend-host` with your real backend
URL, over HTTPS in production. Never let a phone talk
directly to MySQL.

## Permissions and foreground service

- Android 12 and later: request `BLUETOOTH_SCAN`,
  `BLUETOOTH_ADVERTISE`, and `BLUETOOTH_CONNECT` at runtime.
- Older Android: location permission is needed for scanning.
- Run the transport from a foreground service with a
  persistent notification. Without that, background
  Bluetooth is throttled and the mesh will only work while
  the app is open. State that limit to users, do not hide it.
- The transport requests MTU 512 and drops a peer that
  stays at the legacy 23 byte MTU, because such a peer
  cannot receive a 128 byte frame.

## Two-phone test checklist

1. Install the app on two real Android phones, Bluetooth
   on, app in the foreground on both phones for the first
   test.
2. On phone A, offline, create a payment to phone B's
   account. Confirm the packet is stored on phone A.
3. Bring phone B near phone A. Confirm the packet arrives,
   is stored, and is forwarded with TTL reduced by 1 and
   hop count increased by 1.
4. Give phone B internet, trigger upload, and confirm the
   backend returns `SETTLED`, and both account balances
   move exactly once.
5. Upload the same packet again from phone B. Confirm
   `DUPLICATE_DROPPED` and no second debit.
6. Test with the app in the background and record the
   result honestly. Background Bluetooth is heavily
   throttled on modern Android, and a mesh that works
   with two phones open in the foreground is not yet a
   mesh that works in a pocket all day.

## Before any over-the-air claim

The Android radio code has not been compiled or tested
over the air in the backend test environment, because that
environment had no Android SDK and no Bluetooth adapter.
Compile it in Android Studio and test it on two real
Android phones before claiming over-the-air behaviour,
even though its framing, reassembly, duplicate handling,
and upload logic are unit tested on the JVM.

## Fast backend test first, before phones

Before the two-phone test, prove the backend with
PacketBuilder, the standalone tool delivered separately:

```bash
javac tools/PacketBuilder.java
java -cp tools PacketBuilder --fetch http://localhost:8080 sender@example.com SenderPass1234 <senderAccount> <receiverAccount> 250.00
```

Post the resulting `packet.json` to
POST `/api/v1/mesh/bridge/ingest` with HTTP Basic
authentication. First delivery should be `SETTLED`,
second delivery of the same file should be
`DUPLICATE_DROPPED`, and a file with one ciphertext
character flipped should be `INVALID`.
