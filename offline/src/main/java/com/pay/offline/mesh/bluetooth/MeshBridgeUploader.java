package com.pay.offline.mesh.bluetooth;

import com.pay.offline.mesh.dto.MeshIngestionResult;
import com.pay.offline.mesh.dto.MeshPacket;

/**
 * Uploads one packet to the backend bridge endpoint.
 *
 * The production implementation is HttpMeshBridgeUploader, which
 * posts the packet as JSON over HTTPS with HTTP Basic
 * authentication. A test implementation can return a canned
 * result instead, which is how MeshNode upload behaviour is
 * tested without a server.
 *
 * An uploader must throw an exception on a transport failure,
 * such as no network or a non 200 response, so the node knows
 * the upload did not happen and can keep the packet for a later
 * retry and for forwarding. It must not return a settled result
 * for a packet that was not actually accepted by the server.
 */
public interface MeshBridgeUploader {

    MeshIngestionResult upload(MeshPacket packet);
}
