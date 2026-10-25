package com.pay.offline.mesh.bluetooth;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCallback;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattServer;
import android.bluetooth.BluetoothGattServerCallback;
import android.bluetooth.BluetoothGattService;
import android.bluetooth.BluetoothManager;
import android.bluetooth.BluetoothProfile;
import android.bluetooth.le.AdvertiseCallback;
import android.bluetooth.le.AdvertiseData;
import android.bluetooth.le.AdvertiseSettings;
import android.bluetooth.le.BluetoothLeAdvertiser;
import android.bluetooth.le.BluetoothLeScanner;
import android.bluetooth.le.ScanCallback;
import android.bluetooth.le.ScanFilter;
import android.bluetooth.le.ScanResult;
import android.bluetooth.le.ScanSettings;
import android.content.Context;
import android.os.ParcelUuid;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Real Bluetooth Low Energy transport for Android.
 *
 * THIS FILE IS NOT COMPILED BY THE SPRING BOOT MAVEN BUILD.
 * It imports android.bluetooth classes, which do not exist on a
 * JVM. It belongs in the Android phone app, in the same package
 * as the pure Java mesh classes, so it can implement
 * MeshTransport directly. Copy this file, together with
 * MeshTransport, MeshFrameListener, MeshGattConstants, BleFrame,
 * BleReassemblyBuffer, MeshPacketJsonCodec, MeshNode, and
 * HttpMeshBridgeUploader, into the Android app module, then
 * build the app in Android Studio.
 *
 * How it works, in plain terms:
 * - As a GATT server, the phone advertises the mesh service UUID
 *   and offers one characteristic. A peer that connects can
 *   write a frame to that characteristic, and the write is
 *   delivered to the MeshNode through onFrameReceived.
 * - As a GATT client, the phone scans for the same service UUID.
 *   When a peer is found, it connects, requests an MTU of 512,
 *   discovers services, and keeps the connection open so
 *   sendFrame can write frames to that peer.
 * - sendFrame writes a frame to every connected peer, both as
 *   a client write to the peer GATT server, and as a server
 *   notification to every device connected to this phone GATT
 *   server. Both directions are needed, because in a mesh any
 *   phone can be the server for one peer and the client for
 *   another at the same time.
 *
 * Requirements the caller must meet, or this transport will
 * fail at runtime and the failure must be surfaced to the user,
 * not swallowed:
 * - Bluetooth must be on, and the app must hold BLUETOOTH_SCAN,
 *   BLUETOOTH_ADVERTISE, and BLUETOOTH_CONNECT permissions on
 *   Android 12 and later, granted at runtime, plus location
 *   permission on older Android versions where scanning needs
 *   it.
 * - For background operation, the app must run this transport
 *   from a foreground service with a persistent notification,
 *   because Android throttles background Bluetooth scanning and
 *   advertising heavily since Android 8, and may stop it
 *   entirely when the app is killed. A mesh that only works
 *   while the app is open is still a real mesh, but it must
 *   be described that way to users.
 * - A peer that stays at the legacy 23 byte MTU cannot receive
 *   128 byte frames. This implementation drops such a peer
 *   after MTU negotiation, rather than silently truncating
 *   frames, because a truncated frame would fail its CRC on
 *   receipt and waste radio time.
 *
 * This class uses the pre Android 13 GATT APIs, setValue plus
 * writeCharacteristic and notifyCharacteristicChanged, so it
 * compiles on a wide range of API levels. Those calls are
 * deprecated on Android 13 and later, but they still work, and
 * the newer byte array overloads can replace them when the app
 * targets Android 13 as its minimum.
 */
@SuppressWarnings("deprecation")
public class AndroidBleTransport implements MeshTransport {

    private final Context context;
    private final BluetoothManager bluetoothManager;
    private final BluetoothAdapter adapter;

    private MeshFrameListener listener;
    private BluetoothLeAdvertiser advertiser;
    private BluetoothLeScanner scanner;
    private BluetoothGattServer gattServer;
    private BluetoothGattCharacteristic packetCharacteristic;

    private final ConcurrentHashMap<String, BluetoothGatt> clientConnections =
            new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, BluetoothDevice> serverConnections =
            new ConcurrentHashMap<>();

    public AndroidBleTransport(Context context) {
        this.context = context.getApplicationContext();
        this.bluetoothManager = (BluetoothManager) context.getSystemService(Context.BLUETOOTH_SERVICE);
        this.adapter = bluetoothManager != null ? bluetoothManager.getAdapter() : null;
    }

    @Override
    public void start(MeshFrameListener listener) {
        this.listener = listener;
        if (adapter == null || !adapter.isEnabled()) {
            throw new IllegalStateException("Bluetooth is not available or is switched off");
        }
        startGattServer();
        startAdvertising();
        startScanning();
    }

    @Override
    public void stop() {
        try {
            if (advertiser != null) {
                advertiser.stopAdvertising(advertiseCallback);
            }
        } catch (RuntimeException ignored) {
            // Stopping an already stopped advertiser must not crash the app.
        }
        try {
            if (scanner != null) {
                scanner.stopScan(scanCallback);
            }
        } catch (RuntimeException ignored) {
            // Same reasoning as above, for the scanner.
        }
        for (BluetoothGatt gatt : new ArrayList<>(clientConnections.values())) {
            try {
                gatt.disconnect();
                gatt.close();
            } catch (RuntimeException ignored) {
                // Closing a dead connection must not crash the app.
            }
        }
        clientConnections.clear();
        serverConnections.clear();
        if (gattServer != null) {
            try {
                gattServer.close();
            } catch (RuntimeException ignored) {
                // Closing a dead server must not crash the app.
            }
            gattServer = null;
        }
    }

    @Override
    public void sendFrame(byte[] frame) {
        if (frame == null || frame.length == 0) {
            return;
        }
        for (BluetoothGatt gatt : new ArrayList<>(clientConnections.values())) {
            try {
                BluetoothGattService service = gatt.getService(MeshGattConstants.SERVICE_UUID);
                if (service == null) {
                    continue;
                }
                BluetoothGattCharacteristic characteristic =
                        service.getCharacteristic(MeshGattConstants.PACKET_CHARACTERISTIC_UUID);
                if (characteristic == null) {
                    continue;
                }
                characteristic.setValue(frame);
                characteristic.setWriteType(BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE);
                gatt.writeCharacteristic(characteristic);
            } catch (RuntimeException ignored) {
                // A dead peer must not stop frames to other peers.
            }
        }
        if (gattServer != null && packetCharacteristic != null) {
            for (BluetoothDevice device : new ArrayList<>(serverConnections.values())) {
                try {
                    packetCharacteristic.setValue(frame);
                    gattServer.notifyCharacteristicChanged(device, packetCharacteristic, false);
                } catch (RuntimeException ignored) {
                    // A dead peer must not stop frames to other peers.
                }
            }
        }
    }

    private void startGattServer() {
        BluetoothGattService service = new BluetoothGattService(
                MeshGattConstants.SERVICE_UUID,
                BluetoothGattService.SERVICE_TYPE_PRIMARY
        );
        packetCharacteristic = new BluetoothGattCharacteristic(
                MeshGattConstants.PACKET_CHARACTERISTIC_UUID,
                BluetoothGattCharacteristic.PROPERTY_WRITE
                        | BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE
                        | BluetoothGattCharacteristic.PROPERTY_NOTIFY,
                BluetoothGattCharacteristic.PERMISSION_WRITE
        );
        service.addCharacteristic(packetCharacteristic);
        gattServer = bluetoothManager.openGattServer(context, gattServerCallback);
        if (gattServer == null) {
            throw new IllegalStateException("Bluetooth GATT server could not be opened");
        }
        gattServer.addService(service);
    }

    private void startAdvertising() {
        advertiser = adapter.getBluetoothLeAdvertiser();
        if (advertiser == null) {
            throw new IllegalStateException("Bluetooth LE advertising is not supported on this device");
        }
        AdvertiseSettings settings = new AdvertiseSettings.Builder()
                .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY)
                .setConnectable(true)
                .setTimeout(0)
                .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_HIGH)
                .build();
        AdvertiseData data = new AdvertiseData.Builder()
                .setIncludeDeviceName(false)
                .addServiceUuid(new ParcelUuid(MeshGattConstants.SERVICE_UUID))
                .build();
        advertiser.startAdvertising(settings, data, advertiseCallback);
    }

    private void startScanning() {
        scanner = adapter.getBluetoothLeScanner();
        if (scanner == null) {
            throw new IllegalStateException("Bluetooth LE scanning is not supported on this device");
        }
        List<ScanFilter> filters = new ArrayList<>();
        filters.add(new ScanFilter.Builder()
                .setServiceUuid(new ParcelUuid(MeshGattConstants.SERVICE_UUID))
                .build());
        ScanSettings settings = new ScanSettings.Builder()
                .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
                .build();
        scanner.startScan(filters, settings, scanCallback);
    }

    private final AdvertiseCallback advertiseCallback = new AdvertiseCallback() {
    };

    private final ScanCallback scanCallback = new ScanCallback() {
        @Override
        public void onScanResult(int callbackType, ScanResult result) {
            BluetoothDevice device = result.getDevice();
            if (device == null || device.getAddress() == null) {
                return;
            }
            if (clientConnections.containsKey(device.getAddress())) {
                return;
            }
            try {
                BluetoothGatt gatt = device.connectGatt(context, false, gattCallback);
                if (gatt != null) {
                    clientConnections.put(device.getAddress(), gatt);
                }
            } catch (RuntimeException ignored) {
                // A peer that cannot be connected to is simply skipped.
            }
        }
    };

    private final BluetoothGattCallback gattCallback = new BluetoothGattCallback() {
        @Override
        public void onConnectionStateChange(BluetoothGatt gatt, int status, int newState) {
            String address = gatt.getDevice() != null ? gatt.getDevice().getAddress() : null;
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                try {
                    gatt.requestMtu(MeshGattConstants.REQUESTED_MTU);
                } catch (RuntimeException ignored) {
                    // MTU request failure is handled in onMtuChanged or by discovery.
                }
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                if (address != null) {
                    clientConnections.remove(address);
                }
                try {
                    gatt.close();
                } catch (RuntimeException ignored) {
                    // Closing a dead connection must not crash the app.
                }
            }
        }

        @Override
        public void onMtuChanged(BluetoothGatt gatt, int mtu, int status) {
            if (mtu < MeshGattConstants.MAX_FRAME_BYTES + 3) {
                // Legacy MTU, this peer cannot receive frames. Drop it.
                try {
                    gatt.disconnect();
                } catch (RuntimeException ignored) {
                    // Disconnect failure is harmless here.
                }
                return;
            }
            try {
                gatt.discoverServices();
            } catch (RuntimeException ignored) {
                // Discovery failure leaves the peer unusable, and it will be dropped on disconnect.
            }
        }

        @Override
        public void onServicesDiscovered(BluetoothGatt gatt, int status) {
            // Services are looked up lazily in sendFrame, so there is nothing else to do here.
            // Keeping this callback present is required for discovery to complete.
        }

        @Override
        public void onCharacteristicChanged(BluetoothGatt gatt, BluetoothGattCharacteristic characteristic) {
            if (listener == null) {
                return;
            }
            byte[] value = characteristic.getValue();
            if (value != null && value.length > 0) {
                String address = gatt.getDevice() != null ? gatt.getDevice().getAddress() : "unknown";
                listener.onFrameReceived(value, address);
            }
        }
    };

    private final BluetoothGattServerCallback gattServerCallback = new BluetoothGattServerCallback() {
        @Override
        public void onConnectionStateChange(BluetoothDevice device, int status, int newState) {
            if (device == null || device.getAddress() == null) {
                return;
            }
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                serverConnections.put(device.getAddress(), device);
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                serverConnections.remove(device.getAddress());
            }
        }

        @Override
        public void onCharacteristicWriteRequest(
                BluetoothDevice device,
                int requestId,
                BluetoothGattCharacteristic characteristic,
                boolean preparedWrite,
                boolean responseNeeded,
                int offset,
                byte[] value
        ) {
            if (gattServer != null && responseNeeded) {
                gattServer.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, offset, value);
            }
            if (listener != null && value != null && value.length > 0
                    && MeshGattConstants.PACKET_CHARACTERISTIC_UUID.equals(characteristic.getUuid())) {
                String address = device != null && device.getAddress() != null
                        ? device.getAddress()
                        : "unknown";
                listener.onFrameReceived(value, address);
            }
        }
    };
}
