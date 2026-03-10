package com.clinicos.service.repository;

import com.clinicos.service.entity.TestPhone;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface TestPhoneRepository extends JpaRepository<TestPhone, String> {

    boolean existsByPhoneAndCountryCode(String phone, String countryCode);
}
