package com.misterworldtour.travel;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

/** Data Access: 완료 여행 이력. */
interface TravelHistoryRepository extends JpaRepository<TravelHistory, Long> {

    List<TravelHistory> findByMemberId(Long memberId);

    boolean existsByMemberIdAndCompletedTravelId(Long memberId, String completedTravelId);
}
