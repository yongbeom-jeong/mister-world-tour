package com.misterworldtour.travel;

import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

/** Data Access: 여행 상품. 다른 패키지는 TravelService·BookingService 를 거친다. */
interface TravelProductRepository extends JpaRepository<TravelProduct, Long> {

    /** 조건(null 이면 전체)에 맞는 상품, 출발일 가까운 순. after 가 null 이면 날짜 제한 없음. */
    @Query("""
            select p from TravelProduct p
            where (cast(:after as LocalDate) is null or p.departureDate > :after)
              and (:theme is null or p.theme = :theme)
              and (:level is null or p.tourLevel = :level)
            order by p.departureDate, p.id""")
    List<TravelProduct> search(@Param("after") LocalDate after, @Param("theme") Theme theme,
                               @Param("level") TourLevel level);

    /** 같은 상품의 신청·확정·수정을 한 번에 하나씩 (정원 동시 초과 방지). */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select p from TravelProduct p where p.id = :id")
    Optional<TravelProduct> findForUpdate(@Param("id") Long id);

    /** 직원 홈: 최근 등록 상품과 신청 수 (취소 포함 전체, Django 판과 같음). */
    @Query("""
            select p, (select count(a) from TravelApplication a where a.product = p)
            from TravelProduct p order by p.createdAt desc, p.id desc""")
    List<Object[]> recentWithApplicationCount(Pageable pageable);

    long countByDepartureStatus(DepartureStatus status);

    boolean existsByName(String name);

    Optional<TravelProduct> findFirstByName(String name);

    Optional<TravelProduct> findFirstByNameAndThemeAndDepartureDateAndCreatedAtGreaterThanEqualOrderByIdDesc(
            String name, Theme theme, LocalDate departureDate, java.time.OffsetDateTime since);
}
