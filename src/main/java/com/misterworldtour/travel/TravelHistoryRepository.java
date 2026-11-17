package com.misterworldtour.travel;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

/** Data Access: 완료 여행 이력. */
interface TravelHistoryRepository extends JpaRepository<TravelHistory, Long> {

    List<TravelHistory> findByMemberId(Long memberId);

    boolean existsByMemberIdAndCompletedTravelId(Long memberId, String completedTravelId);

    /** 이력 화면: 완료일 최근 순, 완료일 없는 기록은 뒤로. */
    @Query("""
            select h from TravelHistory h where h.memberId = :memberId
            order by case when h.completedAt is null then 1 else 0 end, h.completedAt desc, h.id desc""")
    List<TravelHistory> findForMember(@Param("memberId") Long memberId);

    /** 이미 만든 신청 기반 이력의 "회원:식별값" (memberId 가 null 이면 전체). */
    @Query("""
            select concat(h.memberId, ':', h.completedTravelId) from TravelHistory h
            where h.completedTravelId like :prefix and (:memberId is null or h.memberId = :memberId)""")
    List<String> completedKeys(@Param("prefix") String prefix, @Param("memberId") Long memberId);
}
