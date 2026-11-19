package com.misterworldtour.dataaccess;

import com.misterworldtour.sms.SMSMessage;

import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import jakarta.persistence.PersistenceContext;
import jakarta.persistence.TypedQuery;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Savepoint;
import java.sql.Statement;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

/**
 * 문자 발송 기록(SMSMessage) 저장·조회 (설계 7.1 외 추가).
 * 신청 저장 트랜잭션 안에서 저장점(savepoint)을 두고 JDBC 로 저장한다 (JPA 는 저장점 미지원).
 */
@Repository
class SMSMessageRepository {

    private static final String INSERT = """
            INSERT INTO sms_log (member_id, recipient_name, contact, product_id, product_name, message, status,
                                 attempt_count, last_error, provider_ref, resend_count, created_at, updated_at)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 0, ?, ?)""";

    @PersistenceContext
    private EntityManager em;

    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;

    SMSMessageRepository(JdbcTemplate jdbc, PlatformTransactionManager transactionManager) {
        this.jdbc = jdbc;
        this.tx = new TransactionTemplate(transactionManager);
    }

    /** 실패하면 예외. 바깥 트랜잭션은 저장점까지만 되돌린다. */
    long insert(SMSMessage message) {
        Long id = jdbc.execute((ConnectionCallback<Long>) con -> {
            boolean inTransaction = !con.getAutoCommit();
            Savepoint sp = inTransaction ? con.setSavepoint() : null;
            try {
                long newId = insert(con, message);
                if (sp != null) {
                    con.releaseSavepoint(sp);
                }
                return newId;
            } catch (SQLException | RuntimeException e) {
                if (sp != null) {
                    con.rollback(sp);
                }
                throw e;
            }
        });
        if (id == null) {
            throw new IllegalStateException("발송 기록 번호를 받지 못함");
        }
        message.assignId(id);
        return id;
    }

    private static long insert(Connection con, SMSMessage m) throws SQLException {
        OffsetDateTime now = OffsetDateTime.now();
        try (PreparedStatement ps = con.prepareStatement(INSERT, Statement.RETURN_GENERATED_KEYS)) {
            ps.setObject(1, m.getMemberId());
            ps.setString(2, SMSMessage.cut(m.getRecipientName(), 50));
            ps.setString(3, SMSMessage.cut(m.getContact(), 20));
            ps.setObject(4, m.getProductId());
            ps.setString(5, SMSMessage.cut(m.getProductName(), 100));
            ps.setString(6, m.getMessage() == null ? "" : m.getMessage());
            ps.setString(7, m.getStatus().name());
            ps.setInt(8, m.getAttemptCount());
            ps.setString(9, m.getLastError() == null ? "" : m.getLastError());
            ps.setString(10, SMSMessage.cut(m.getServiceRequestId(), 100));
            ps.setObject(11, now);
            ps.setObject(12, now);
            ps.executeUpdate();
            try (ResultSet keys = ps.getGeneratedKeys()) {
                if (!keys.next()) {
                    throw new SQLException("생성된 번호 없음");
                }
                return keys.getLong(1);
            }
        }
    }

    void saveResult(SMSMessage message, Long resentBy) {
        tx.executeWithoutResult(s -> {
            SMSMessage saved = em.find(SMSMessage.class, message.getId());
            if (saved != null) {
                saved.copyResultFrom(message);
                if (resentBy != null) {
                    saved.countResend(resentBy);
                }
            }
        });
    }

    Optional<SMSMessage> find(long id) {
        return Optional.ofNullable(em.find(SMSMessage.class, id));
    }

    /** 두 직원이 동시에 재발송해도 한 번만 보내도록 잠근다. */
    Optional<SMSMessage> findForUpdate(long id) {
        return em.createQuery("select m from SMSMessage m where m.id = :id", SMSMessage.class)
                .setParameter("id", id).setLockMode(LockModeType.PESSIMISTIC_WRITE).getResultStream().findFirst();
    }

    Page<SMSMessage> findAll(int page, int size) {
        return page(em.createQuery("select m from SMSMessage m order by m.createdAt desc, m.id desc", SMSMessage.class),
                em.createQuery("select count(m) from SMSMessage m", Long.class), page, size);
    }

    Page<SMSMessage> findProblems(OffsetDateTime stale, int page, int size) {
        String where = " " + """
                where m.status in (com.misterworldtour.sms.SMSMessage.Status.FAILURE,
                                   com.misterworldtour.sms.SMSMessage.Status.UNKNOWN)
                   or (m.status = com.misterworldtour.sms.SMSMessage.Status.PENDING and m.updatedAt < :stale)""";
        return page(em.createQuery("select m from SMSMessage m" + where + " order by m.createdAt desc, m.id desc",
                        SMSMessage.class).setParameter("stale", stale),
                em.createQuery("select count(m) from SMSMessage m" + where, Long.class).setParameter("stale", stale),
                page, size);
    }

    private static Page<SMSMessage> page(TypedQuery<SMSMessage> query, TypedQuery<Long> count, int page, int size) {
        int p = Math.max(page, 0);
        List<SMSMessage> content = query.setFirstResult(p * size).setMaxResults(size).getResultList();
        return new PageImpl<>(content, PageRequest.of(p, size), count.getSingleResult());
    }
}
