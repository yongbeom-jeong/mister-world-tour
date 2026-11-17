package com.misterworldtour.sms;

import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.OffsetDateTime;
import java.util.Optional;

/** Data Access: 문자 발송 기록. */
interface SmsLogRepository extends JpaRepository<SmsLog, Long> {

    Page<SmsLog> findAllByOrderByCreatedAtDescIdDesc(Pageable pageable);

    /** 확인 필요: 실패·결과 불명·멈춘 발송 중 (최근 순). */
    @Query("""
            select l from SmsLog l
            where l.status in (com.misterworldtour.sms.SmsLog.Status.FAILED, com.misterworldtour.sms.SmsLog.Status.UNKNOWN)
               or (l.status = com.misterworldtour.sms.SmsLog.Status.PENDING and l.updatedAt < :stale)
            order by l.createdAt desc, l.id desc""")
    Page<SmsLog> findProblems(@Param("stale") OffsetDateTime stale, Pageable pageable);

    /** 두 직원이 동시에 재발송해도 한 번만 보내도록 잠근다. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select l from SmsLog l where l.id = :id")
    Optional<SmsLog> findForUpdate(@Param("id") Long id);
}
