package com.misterworldtour.souvenir;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.Set;

/** Data Access: 출발 확정 차감 내역. */
interface StockIssueRepository extends JpaRepository<StockIssue, Long> {

    @Query("select i.applicationId from StockIssue i where i.souvenir.id = :souvenirId and i.applicationId in :applicationIds")
    Set<Long> issuedApplicationIds(@Param("souvenirId") Long souvenirId,
                                   @Param("applicationIds") Collection<Long> applicationIds);

    boolean existsBySouvenirId(Long souvenirId);

    long countBySouvenirId(Long souvenirId);

    /** 차감 내역 (최근 순). souvenirId 가 null 이면 전체. */
    @EntityGraph(attributePaths = "souvenir")
    @Query("select i from StockIssue i where (:souvenirId is null or i.souvenir.id = :souvenirId) "
            + "order by i.issuedAt desc, i.id desc")
    Page<StockIssue> search(@Param("souvenirId") Long souvenirId, Pageable pageable);

    List<StockIssue> findByApplicationId(Long applicationId);
}
