package com.misterworldtour.souvenir;

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

    List<StockIssue> findBySouvenirIdOrderByIssuedAtDescIdDesc(Long souvenirId);

    List<StockIssue> findAllByOrderByIssuedAtDescIdDesc();

    List<StockIssue> findByApplicationId(Long applicationId);
}
