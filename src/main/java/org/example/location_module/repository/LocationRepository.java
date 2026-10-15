package org.example.location_module.repository;

import org.example.location_module.entity.Location;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface LocationRepository
        extends JpaRepository<Location, Long> {

    Optional<Location> findByCode(String code);
}
