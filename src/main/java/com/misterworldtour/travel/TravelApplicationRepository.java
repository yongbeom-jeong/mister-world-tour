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

    /**
     * 출발이 확정된(허니문은 신청 상태, 그 외는 상품 상태) 취소하지 않은 신청 중 출발일이 today 이전인 것.
     * memberId 가 null 이면 전체.
     */
    @Query("""
            select a from TravelApplication a join fetch a.product p
            where a.cancelledAt is null and p.departureDate < :today
              and (:memberId is null or a.memberId = :memberId)
              and ((p.theme = com.misterworldtour.travel.Theme.HONEYMOON
                    and a.departureStatus = com.misterworldtour.travel.DepartureStatus.CONFIRMED)
                or (p.theme <> com.misterworldtour.travel.Theme.HONEYMOON
                    and p.departureStatus = com.misterworldtour.travel.DepartureStatus.CONFIRMED))
            order by a.id""")
    List<TravelApplication> findConfirmedDepartedBefore(@Param("today") java.time.LocalDate today,
                                                        @Param("memberId") Long memberId);

    @Query("select a.product.id from TravelApplication a where a.id = :id and a.memberId = :memberId")
    Optional<Long> productIdOf(@Param("id") Long id, @Param("memberId") Long memberId);

    @EntityGraph(attributePaths = {"product"})
    @Query("select a from TravelApplication a where a.id in :ids")
    List<TravelApplication> findAllWithProductByIdIn(@Param("ids") java.util.Collection<Long> ids);

    /** 신청별 여행자 수 (목록 표시용): [신청 id, 여행자 수]. */
    @Query("select a.id, size(a.travelers) from TravelApplication a where a.memberId = :memberId")
    List<Object[]> travelerCountsForMember(@Param("memberId") Long memberId);
}
