package com.misterworldtour.travel;

import org.springframework.data.jpa.repository.JpaRepository;

/** Data Access: 여행 신청. */
interface TravelApplicationRepository extends JpaRepository<TravelApplication, Long> {

    long countByCancelledAtIsNull();

    long countByMemberIdAndCancelledAtIsNull(Long memberId);

    boolean existsByProductId(Long productId);
}
