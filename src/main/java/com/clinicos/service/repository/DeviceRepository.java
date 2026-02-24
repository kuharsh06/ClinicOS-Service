package com.clinicos.service.repository;

import com.clinicos.service.entity.Device;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface DeviceRepository extends JpaRepository<Device, Integer> {

    Optional<Device> findByUuid(String uuid);

    Optional<Device> findByDeviceId(String deviceId);

    List<Device> findByUserId(Integer userId);

    boolean existsByDeviceId(String deviceId);
}
