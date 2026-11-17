package com.misterworldtour.travel;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** Data Access: 여행자. */
interface TravelerRepository extends JpaRepository<Traveler, Long> {

    /** 같은 상품에 저장된 실제 여행자 수 (취소하지 않은 모든 신청 합계). */
    @Query("""
            select count(t) from Traveler t
            where t.application.product.id = :productId and t.application.cancelledAt is null""")
    long countActiveForProduct(@Param("productId") Long productId);
}
