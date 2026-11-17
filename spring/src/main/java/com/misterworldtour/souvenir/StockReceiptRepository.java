package com.misterworldtour.souvenir;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

/** Data Access: 기념품 입고 내역. */
interface StockReceiptRepository extends JpaRepository<StockReceipt, Long> {

    Optional<StockReceipt> findByRequestKey(String requestKey);

    boolean existsByRequestKey(String requestKey);

    boolean existsBySouvenirId(Long souvenirId);
}
