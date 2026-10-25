package com.pay.offline.mesh.repository;

import com.pay.offline.mesh.entity.MeshPacketReceipt;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface MeshPacketReceiptRepository
        extends JpaRepository<MeshPacketReceipt, Long> {

    Optional<MeshPacketReceipt> findByPacketHash(String packetHash);

    boolean existsByPacketHash(String packetHash);
}
