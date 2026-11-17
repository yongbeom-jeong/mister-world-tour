package com.misterworldtour.travel;

import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

/** Data Access: 여행 신청. */
interface TravelApplicationRepository extends JpaRepository<TravelApplication, Long> {

    long countByCancelledAtIsNull();

    long countByMemberIdAndCancelledAtIsNull(Long memberId);

    boolean existsByProductId(Long productId);

    @EntityGraph(attributePaths = {"product", "travelers"})
    Optional<TravelApplication> findByRequestKey(String requestKey);

    Optional<TravelApplication> findByMemberIdAndRequestKey(Long memberId, String requestKey);

    /** 고객 본인의 신청 1건 (상품·여행자 함께). */
    @EntityGraph(attributePaths = {"product", "travelers"})
    Optional<TravelApplication> findByIdAndMemberId(Long id, Long memberId);

    /** 내 신청: 최근 순 (상품 함께). */
    @EntityGraph(attributePaths = {"product"})
    List<TravelApplication> findByMemberIdOrderBySubmittedAtDescIdDesc(Long memberId);

    /** 상품의 취소하지 않은 신청: [신청 id, 회원 id, 여행자 수]. 출발 확정 때 문자 대상·차감 대상. */
    @Query("""
            select a.id, a.memberId, size(a.travelers) from TravelApplication a
            where a.product.id = :productId and a.cancelledAt is null order by a.id""")
    List<Object[]> activeRowsForProduct(@Param("productId") Long productId);

    /** 신청별 여행자 수 (목록 표시용): [신청 id, 여행자 수]. */
    @Query("select a.id, size(a.travelers) from TravelApplication a where a.memberId = :memberId")
    List<Object[]> travelerCountsForMember(@Param("memberId") Long memberId);
}
