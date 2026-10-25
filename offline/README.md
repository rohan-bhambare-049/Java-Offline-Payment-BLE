# 🕸️ Java Offline Pay with Bluetooth Mesh

A Spring Boot backend for payments that can travel without internet. 
A sender phone encrypts a payment, the packet hops phone to phone
over Bluetooth Low Energy, and a phone that gets internet back
uploads the packet to this backend. The backend decrypts it,
deduplicates it, and settles it exactly once, into the same
accounts, transactions, and ledger used by the online flow.

This is mesh-routed deferred settlement, not real-time offline
payment. Until the backend settles a packet, it is a payment
instruction in transit, not money moved. The receiver only knows
a payment settled when the backend says so.

## Features

- Online payments: create a payment instruction, then settle it,
  with idempotency on creation.
- Offline mesh payments: encrypted packets, multi-hop forwarding
  with TTL, duplicate suppression on every phone and on the server.
- Hybrid encryption: a fresh AES-256-GCM key per packet, wrapped
  with the server RSA public key using OAEP with SHA-256.
- SHA-256 in two roles: an outer hash over the ciphertext for
  deduplication, and an inner hash over the canonical payment
  fields for field integrity inside the encrypted payload.
- Replay protection: payload creation time inside the encrypted
  payload, a 24 hour maximum age, and a 5 minute clock skew
  allowance.
- Durable idempotency: a unique instruction UUID, a unique
  transaction per instruction, and a `mesh_packet_receipts` table
  with a unique packet hash, so a replay after a restart is
  still caught.
- Real Bluetooth node code, separate from the backend, with
  GATT service definitions, 128 byte CRC-checked frames,
  out-of-order reassembly, and a real Android transport.
- No simulated mesh in the backend. There are no virtual phones
  on the server. The backend is the server, the phone code is
  the phone code.

## Tech stack

- Java 17, Spring Boot 4.1.1, Spring Security, Spring Data JPA
- MySQL 8 (MariaDB was used only as a local test substitute,
  see Testing)
- Maven, with the Maven wrapper included (`mvnw`, `mvnw.cmd`)
- Android Bluetooth Low Energy for the phone side
  (`BluetoothLeAdvertiser`, `BluetoothLeScanner`,
  `BluetoothGatt`, `BluetoothGattServer`)
- No new Maven dependency was added for the mesh feature.
  Cryptography uses the Java standard library, and the packet
  JSON codecs are hand-written, so the same format ports
  directly to Android.

## Project structure

```
offline/
  src/main/java/com/pay/offline/
    controller/          # health, users, accounts, payments
    entity/              # User, Account, PaymentInstruction, Transaction, LedgerEntry
    service/             # online registration, payments, settlement
    security/            # HTTP Basic, stateless, CSRF disabled
    mesh/
      MeshIngestionService.java    # bridge pipeline, in order
      MeshSettlementService.java   # one transaction that moves money and writes the receipt
      MeshPacketClaimService.java  # in-memory atomic claim by packet hash
      MeshPacketFactory.java       # packet creation, as a sender phone does it
      MeshPayloadCodec.java        # JSON for the inner payment payload
      crypto/
        HybridCryptoService.java   # RSA OAEP + AES-256-GCM
        MeshHash.java              # SHA-256 outer and inner hashes
        ServerKeyHolder.java       # persistent or ephemeral server RSA pair
        MeshKeyGenerator.java      # one-time helper to print a fresh key pair
      dto/                         # MeshPacket, MeshPaymentPayload, ingestion result
      entity/                      # MeshPacketReceipt
      repository/                  # MeshPacketReceiptRepository
      controller/
        MeshBridgeController.java  # the only mesh controller in the backend
      bluetooth/                   # pure Java phone node, no Spring, no Android imports
        MeshGattConstants.java     # service UUID, characteristic UUID, MTU, frame size
        MeshTransport.java         # radio interface
        MeshFrameListener.java     # callback for frames arriving over Bluetooth
        BleFrame.java              # frame encoding, decoding, CRC check
        BleReassemblyBuffer.java   # out-of-order reassembly, duplicate chunks ignored
        MeshPacketJsonCodec.java   # JSON for a whole MeshPacket
        MeshNode.java              # receive, reassemble, drop duplicates, store, forward, upload
        MeshBridgeUploader.java    # bridge upload interface
        HttpMeshBridgeUploader.java # real HTTP upload to the bridge endpoint
  android-mesh/
    AndroidBleTransport.java       # real Android BLE implementation of MeshTransport
  src/main/resources/
    application.properties         # database, mesh TTL, mesh freshness, mesh keys
  mesh-schema.sql                  # the one new table, run once before startup
  MESH_README.md                   # full mesh design notes, in the project zip
```

## Prerequisites

- JDK 17 or newer
- MySQL 8 running locally, with a database for this app
- A terminal, and Postman or curl for API testing

## Setup

1. Create the database in MySQL. The default in
   `application.properties` is:

   ```sql
   CREATE DATABASE upi_mesh_db;
   ```

   Change the URL, username, and password in
   `offline/src/main/resources/application.properties` to match
   your own MySQL setup. Never commit a real password or a real
   private key.

2. Run the mesh schema once, against that database. The app uses
   `spring.jpa.hibernate.ddl-auto=validate`, so it will not
   create this table itself, and it will fail to start if the
   table is missing. No existing table is changed.

   ```bash
   mysql -u root -p upi_mesh_db < offline/mesh-schema.sql
   ```

   Or run the same file from MySQL Workbench.

3. Start the backend from the `offline` folder:

   On Windows:

   ```cmd
   mvnw.cmd spring-boot:run
   ```

   On macOS or Linux:

   ```bash
   ./mvnw spring-boot:run
   ```

4. Check health:

   ```
   GET http://localhost:8080/api/v1/health
   ```

   It should return status `UP`.

## API reference

All endpoints except health and registration require HTTP Basic
authentication. Use your registered email as the username.

| Method | Path | What it does |
| --- | --- | --- |
| GET | `/api/v1/health` | Health check, no login needed |
| POST | `/api/v1/users/register` | Register a user, no login needed. Body: `fullName`, `email`, `password` (at least 12 characters). Registration also creates an account with a 16 digit account number and a starting balance of zero. |
| GET | `/api/v1/accounts/me` | Your account number and balance, for the logged in user |
| POST | `/api/v1/payments` | Create a payment instruction. Header `Idempotency-Key` is required. Body: `receiverAccountNumber` (16 digits) and `amount`. The sender is the logged in user. This creates the instruction, it does not move money yet. |
| GET | `/api/v1/payments/{instructionUuid}` | Get a payment instruction |
| POST | `/api/v1/payments/{instructionUuid}/settle` | Settle a payment instruction, sender only |
| GET | `/api/v1/mesh/server-public-key` | Server RSA public key in Base64, X509 format. Phones use this to encrypt. The private key is never exposed. |
| POST | `/api/v1/mesh/bridge/ingest` | The mesh bridge endpoint. A bridge phone posts a MeshPacket here. Optional header `X-Bridge-Node-Id`. |

Bridge response shape:

```json
{
  "outcome": "SETTLED",
  "packetHash": "64 character SHA-256 hex string",
  "instructionUuid": "payment id from the payload",
  "transactionUuid": "transaction id, only on SETTLED",
  "message": "Payment settled successfully"
}
```

Outcome values are `SETTLED`, `DUPLICATE_DROPPED`, `REJECTED`,
`EXPIRED`, and `INVALID`. A duplicate or a rejection still
returns HTTP 200, because the bridge phone needs the outcome
value, and a duplicate is a normal event in a mesh. A malformed
request body returns HTTP 400, and an unauthenticated request
returns HTTP 401.

There is no deposit endpoint in this project. For testing, top
up a sender balance directly in MySQL, for example:

```sql
UPDATE accounts SET balance = 10000.00
WHERE account_number = 'sender account number here';
```

## How a mesh payment flows

```
Sender phone, offline
  builds MeshPaymentPayload
  paymentId, sender account, receiver account, amount, INR, createdAt
  payloadHash = SHA-256(canonical payment fields)
  JSON -> AES-256-GCM encrypt -> RSA OAEP wrap AES key
  MeshPacket { packetId, originDeviceId, createdAt, ttl 5, hopCount 0, ciphertext }

Bluetooth node on sender phone
  MeshPacket JSON -> split into BleFrame chunks, max 128 bytes each
  header: packet id, total chunks, chunk index, CRC32 of payload
  AndroidBleTransport writes each frame to the GATT characteristic
  of every connected peer

Bluetooth node on a relay phone
  receives frames over GATT, CRC checks each frame, reassembles
  in any order, duplicate chunks ignored
  packet id already seen -> drop, do not store, do not forward
  new packet -> store, then forward if TTL is above zero:
    new packet with TTL minus 1, hop count plus 1, ciphertext unchanged
  packet with TTL zero -> store, do not forward, can still upload

Bridge phone, internet back
  MeshNode uploads every stored packet with HttpMeshBridgeUploader
  POST /api/v1/mesh/bridge/ingest, HTTP Basic authentication

Backend pipeline, in order
  1. SHA-256 over decoded ciphertext bytes -> packetHash
  2. Claim packetHash in memory, putIfAbsent
     loser -> DUPLICATE_DROPPED, before any RSA work
  3. Decrypt with server private key, AES GCM verifies the tag
     failure -> INVALID, claim released
  4. Parse payload JSON, recompute inner SHA payload hash
     mismatch -> INVALID, claim released
  5. Freshness: createdAt within 24 hours, not more than
     5 minutes in the future, or the packet is EXPIRED or INVALID
  6. Settlement transaction:
     instruction UUID already exists -> return its outcome
     receipt for packet hash already exists -> DUPLICATE_DROPPED
     accounts missing -> INVALID, no instruction created
     accounts inactive, self payment, non INR, bad amount,
       or insufficient funds -> instruction REJECTED, transaction
       FAILED, receipt REJECTED
     otherwise -> debit sender, credit receiver, two ledger
       entries, transaction SUCCESS, instruction SETTLED,
       receipt SETTLED
```

### Packet and frame formats

MeshPacket, the outer wire format:

- `packetId`: UUID, unique per packet, used by phones for
  duplicate suppression. It is not trusted by the server for
  deduplication, because an intermediate could rewrite it.
- `originDeviceId`: the device that created the packet.
- `createdAt`: epoch millis, outer creation time. The server
  freshness check uses the inner payload creation time, which
  is inside the encrypted payload and cannot be rewritten
  without breaking GCM.
- `ttl`: starts at 5 by default, configurable with
  `mesh.packet.ttl`. Each forward reduces it by 1.
- `hopCount`: starts at 0, increases by 1 per forward.
- `ciphertext`: Base64 of RSA encrypted AES key, GCM IV, and
  AES ciphertext plus tag. No payment field is readable here.

MeshPaymentPayload, the inner clear payment:

- `paymentId`: UUID, the nonce. It becomes the instruction
  UUID on the server. A new payment must use a new paymentId,
  even for the same amount to the same receiver.
- `senderAccountNumber`, `receiverAccountNumber`: 16 digit
  account numbers.
- `amount`: positive, at most two decimal places.
- `currency`: INR only in this project.
- `createdAt`: epoch millis at payment creation, used for
  replay protection.
- `payloadHash`: SHA-256 over paymentId, sender, receiver,
  amount with trailing zeros stripped, currency, and createdAt,
  joined with pipe characters, in that order.

BleFrame, the Bluetooth frame format, in
`mesh/bluetooth/BleFrame.java`:

- 24 byte header, big endian: packet id 16 bytes, total chunk
  count 2 bytes, chunk index 2 bytes, CRC32 of payload 4 bytes.
- Payload slice, 1 to 104 bytes, so a frame is at most
  128 bytes.
- The Android transport requests an MTU of 512 on every
  connection, and drops a peer that stays at the legacy 23 byte
  MTU, because such a peer cannot receive a 128 byte frame.

Bluetooth GATT identity, in
`mesh/bluetooth/MeshGattConstants.java`:

- One service UUID and one packet characteristic UUID, which
  must match on both phones, otherwise discovery never finds
  a peer.
- Characteristic properties: WRITE, WRITE_NO_RESPONSE, and
  NOTIFY, so packets flow in both directions between a pair
  of phones, one as client and one as server.

## Persistent server keys

By default the server generates a fresh RSA key pair on every
start. That is fine for a demo, but packets still travelling
in the mesh when the server restarts will not decrypt
afterwards, because the old private key is gone.

To make keys persistent:

1. Build the project, then run, from the `offline` folder:

   ```bash
   ./mvnw compile
   java -cp target/classes com.pay.offline.mesh.crypto.MeshKeyGenerator
   ```

2. Copy the two printed Base64 values into
   `application.properties` as `mesh.crypto.publicKeyBase64`
   and `mesh.crypto.privateKeyBase64`.
3. Restart, then check GET `/api/v1/mesh/server-public-key`.
   The public key should be the same as before the restart.
4. Never put the private key value in the Android app, in
   chat, or in source control. The `.gitignore` in this project
   covers a `mesh-keys` folder and a private key file name,
   but it cannot protect a value pasted into a properties
   file that is already tracked. Keep a production private
   key in a secret store, not in the properties file, before
   any real use.

## Testing

### Backend unit tests

From the `offline` folder, with a JDK installed:

```bash
./mvnw test -Dtest='com.pay.offline.mesh.**'
```

That runs only the mesh tests, which need no database and no
Bluetooth radio. They cover crypto round trips and tamper
rejection, SHA-256 vectors, payload and packet JSON round
trips, TTL forwarding, claim service concurrency (20 threads
claiming one hash produce exactly one winner), ingestion
valid, tampered, and expired cases, frame CRC failure and
out-of-order reassembly, MeshNode forwarding and duplicate
handling, and HTTP bridge upload against a local JDK HTTP
server.

Test results recorded for this revision, 25 October 2026:
backend compile BUILD SUCCESS, mesh tests 30 out of 30
passed, full project suite 53 out of 56 passed, with the
only 3 failures being the pre-existing database integration
tests, which need a running MySQL database.

One mesh test uses a local JDK HTTP server on localhost. In
the test VM used for that run, Java connected to localhost
through an IPv6 mapped address that an IPv4-only server
reset, so that test needed an extra JVM flag to force IPv4.
If you hit a connection reset on a localhost test in a
similar VM, run the mesh tests as:

```bash
./mvnw test -Dtest='com.pay.offline.mesh.**' -DargLine="-Djava.net.preferIPv4Stack=true"
```

That flag is a test environment workaround only. It is not
part of the production code.

### Testing the bridge without a phone: PacketBuilder

PacketBuilder is a separate, standalone test tool, delivered
separately from the main project. It is not part of the
backend, it is not part of the Android app, and it does not
need the backend source code to run. It is included in this
repository guide because it is the fastest way to prove the
bridge endpoint works before any phone is built.

It is a single Java file, `tools/PacketBuilder.java`, with no
project dependencies and no third party dependencies, only
the Java standard library, JDK 17 or newer.

Compile it:

```bash
javac tools/PacketBuilder.java
```

Build a packet, using the fetch mode against a running
backend:

```bash
java -cp tools PacketBuilder --fetch http://localhost:8080 sender@example.com SenderPass1234 7155445402273003 7999922411051701 250.00
```

Or, if you already have the public key from
GET `/api/v1/mesh/server-public-key`:

```bash
java -cp tools PacketBuilder <publicKeyBase64> 7155445402273003 7999922411051701 250.00
```

Useful options, in any order after the required arguments:

- `--output <file>` - output file, default `packet.json`
- `--origin <deviceId>` - origin device id, default `phone-sender`
- `--ttl <number>` - packet TTL, default `5`

The tool prints the payment id, packet id, and packet hash,
and writes one JSON packet file. That file is the request
body for POST `/api/v1/mesh/bridge/ingest`, with HTTP Basic
authentication and the optional header `X-Bridge-Node-Id`.

Expected results, in order:

1. First delivery: `SETTLED`, sender balance falls by the
   amount, receiver balance rises by the amount, one
   transaction, two ledger rows, one `SETTLED` receipt row.
2. Same file again: `DUPLICATE_DROPPED`, with the same
   instruction id, balances unchanged.
3. Same file with one ciphertext character flipped:
   `INVALID`, balances unchanged, no new receipt row.

PacketBuilder needs only the server public key. It never
needs, asks for, or prints the server private key. A packet
is valid for 24 hours from creation, so build a fresh packet
if an old file returns `EXPIRED`. Test only with accounts
you control, against a backend you control.

End-to-end result recorded for this revision, against a
local MariaDB 10.11 database, because no MySQL server was
present in the test VM, with the mesh table created only
from `mesh-schema.sql` and the app in validate mode: a
genuine encrypted packet for 250.00 settled once (sender
10000.00 to 9750.00, receiver 0.00 to 250.00), the same
packet again returned `DUPLICATE_DROPPED` with balances
unchanged, and a tampered ciphertext returned `INVALID`
with no money moved. A MariaDB dialect override was used
for that test run only, because the original pessimistic
lock query generates a form MariaDB rejects; that does not
apply on a real MySQL database.

### Testing on Android

This section is the Android testing guide, and it is also
the place to start when you build the phone app. The
backend alone cannot prove Bluetooth over the air. The
Android files in this project are the thing to attach and
test on Android, and they are:

- `offline/src/main/java/com/pay/offline/mesh/bluetooth/`
  - the pure Java node: GATT constants, transport interface,
    frame codec, reassembly buffer, packet JSON codec, node
    logic, and HTTP bridge uploader
- `offline/android-mesh/AndroidBleTransport.java`
  - the real Android BLE implementation of `MeshTransport`

To use them in an Android app:

1. Copy those files into the Android app module, in the
   same package, `com.pay.offline.mesh.bluetooth`, plus
   the DTO and crypto classes they depend on:
   `MeshPacket`, `MeshPaymentPayload`, `MeshIngestionResult`,
   `MeshIngestionOutcome`, `MeshHash`, `HybridCryptoService`,
   `MeshPayloadCodec`, and `MeshPacketFactory`, or a Kotlin
   port of the same packet creation sequence, using the
   platform RSA OAEP with SHA-256 and AES GCM equivalents.
2. Fetch the server public key once, from
   GET `/api/v1/mesh/server-public-key`, and cache it on
   the phone. The phone must never receive the server
   private key.
3. Create a `MeshNode` with a device id, an
   `AndroidBleTransport`, an `HttpMeshBridgeUploader` built
   with the backend base URL, the bridge node id, and the
   logged in user credentials, and the current internet
   flag. Call `node.start()` when the mesh service starts,
   and `node.stop()` when it stops.
4. When the user pays offline, build the packet with the
   same sequence as `MeshPacketFactory`, then call
   `node.sendPacket()`. The node fragments the packet and
   the transport writes the frames over GATT.
5. When internet returns, set the node internet flag to
   true and call `node.uploadAllStored()`. Drop packets
   the server reports as settled, duplicate dropped,
   rejected, expired, or invalid. Keep packets whose
   upload throws, for a later retry and for forwarding.
6. Run the transport from a foreground service with a
   persistent notification, and request `BLUETOOTH_SCAN`,
   `BLUETOOTH_ADVERTISE`, and `BLUETOOTH_CONNECT` at
   runtime on Android 12 and later. Without that,
   background Bluetooth is throttled and the mesh will
   only work while the app is open. That limit must be
   stated to users, not hidden.
7. Never let a phone talk directly to MySQL. The phone
   talks only to the backend endpoints above, over HTTPS
   in production.

Two-phone test, once the app builds:

1. Install the app on two real Android phones, with
   Bluetooth on, and keep the app in the foreground on
   both phones for the first test.
2. On phone A, offline, create a payment to phone B's
   account. Confirm the packet is stored on phone A.
3. Bring phone B near phone A and confirm the packet
   arrives, is stored, and is forwarded with TTL reduced
   by 1 and hop count increased by 1.
4. Give phone B internet, trigger upload, and confirm
   the backend returns `SETTLED`, and both account
   balances move exactly once.
5. Upload the same packet again from phone B and confirm
   `DUPLICATE_DROPPED` and no second debit.
6. Test with the app in the background and record the
   result honestly: background Bluetooth is heavily
   throttled on modern Android, and a mesh that works
   with two phones open in the foreground is not yet a
   mesh that works in a pocket all day.

The Android radio code in this project has not been
compiled or tested over the air yet, because the test
environment used for the backend had no Android SDK and
no Bluetooth adapter. It must be compiled in Android
Studio and tested on two real Android phones before any
claim is made about over-the-air behaviour, even though
its protocol code, framing, reassembly, and upload logic
are unit tested on the JVM.

## Honest limitations

1. An offline sender cannot prove it has the funds. When a
   sender shows a receiver "payment sent" offline, that is
   an instruction in transit until the backend settles it.
   If the balance is too low when the packet arrives,
   settlement is `REJECTED` and the receiver has no
   recourse in this design. Real offline payment systems
   solve this with a pre-funded wallet, which is outside
   the scope of this project.
2. A malicious sender can double spend offline. With a
   balance of 500, it can send 500 to one person in one
   place and 500 to another person elsewhere. Whichever
   packet reaches the backend first wins, and the other
   is `REJECTED`. This is the same root cause as point 1,
   and no deduplication scheme fixes it without a
   pre-funded wallet or a trusted device wallet.
3. Radio reality on Android. Background Bluetooth Low
   Energy is heavily throttled since Android 8, iOS
   peripheral mode is locked down, and two strangers'
   phones reliably forming a GATT connection while the
   apps are not actively open is genuinely difficult.
   The node and transport code here are real, but they
   do not remove those platform limits.
4. The bridge header is not device authentication. Any
   logged in user can upload any packet, which is correct
   for a mesh, because a bridge is supposed to carry
   strangers' packets, but it also means the server cannot
   prove which physical phone uploaded a packet.
   Production would need device attestation or mutual
   TLS for bridge phones, plus per-bridge rate limits
   and per-sender velocity checks, none of which are in
   this project.
5. Ephemeral keys on restart, unless you configure
   persistent keys as described above. Even with
   persistent keys, key rotation and revocation are not
   handled here. Rotating the server key invalidates
   packets still in flight under the old key, so rotation
   needs a grace period with two accepted keys, which is
   future work.
6. Clock trust. Freshness depends on the sender phone
   clock being roughly correct, within the 5 minute skew
   allowance. A phone with a badly wrong clock will
   produce packets that are rejected as too old or too
   far in the future. That is a deliberate trade-off:
   without a trusted timestamp inside the encrypted
   payload, replay protection would be weaker.

## License

MIT License. See `LICENSE` for the full text.
