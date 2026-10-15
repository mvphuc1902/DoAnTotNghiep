package org.example.items_module.repository;
import org.example.items_module.entity.Items;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface ItemRepository extends JpaRepository<Items, Long> {

    Optional<Items> findByItemNo(String itemNo);
}
