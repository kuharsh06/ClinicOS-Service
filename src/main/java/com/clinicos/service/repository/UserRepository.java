package com.clinicos.service.repository;

import com.clinicos.service.entity.User;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface UserRepository extends JpaRepository<User, Integer> {

    Optional<User> findByUuid(String uuid);

    Optional<User> findByCountryCodeAndPhone(String countryCode, String phone);

    Optional<User> findByPhone(String phone);

    boolean existsByCountryCodeAndPhone(String countryCode, String phone);
}
