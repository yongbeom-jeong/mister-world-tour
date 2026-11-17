package com.misterworldtour.sms;

import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Savepoint;
import java.sql.Statement;
import java.time.OffsetDateTime;

/**
 * 발송 기록 '발송 중' 행 만들기. 신청 저장 트랜잭션 안에서 불러도 실패가 신청을 되돌리지 않게
 * 같은 연결에 저장점(savepoint)을 두고 쓴다 (Django 판 transaction.atomic() 저장점과 같음).
 * JPA(Hibernate)는 저장점을 지원하지 않아 이 한 곳만 JDBC 로 쓴다.
 */
@Component
class SmsLogWriter {

    private static final String INSERT = """
            INSERT INTO sms_log (member_id, recipient_name, contact, product_id, product_name, message, status,
                                 attempt_count, last_error, provider_ref, resend_count, created_at, updated_at)
            VALUES (?, ?, ?, ?, ?, ?, ?, 0, ?, '', 0, ?, ?)""";

    private final JdbcTemplate jdbc;

    SmsLogWriter(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** 새 기록의 id. 실패하면 예외 (바깥 트랜잭션은 저장점까지만 되돌려 계속 쓸 수 있다). */
    long insertStart(Long memberId, String name, String contact, Long productId, String productName, String message,
                     SmsLog.Status status, String lastError) {
        Long id = jdbc.execute((ConnectionCallback<Long>) con -> {
            boolean inTransaction = !con.getAutoCommit();
            Savepoint sp = inTransaction ? con.setSavepoint() : null;
            try {
                long newId = insert(con, memberId, name, contact, productId, productName, message, status, lastError);
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
            throw new IllegalStateException("발송 기록 id 를 받지 못함");
        }
        return id;
    }

    private static long insert(Connection con, Long memberId, String name, String contact, Long productId,
                               String productName, String message, SmsLog.Status status, String lastError)
            throws SQLException {
        OffsetDateTime now = OffsetDateTime.now();
        try (PreparedStatement ps = con.prepareStatement(INSERT, Statement.RETURN_GENERATED_KEYS)) {
            ps.setObject(1, memberId);
            ps.setString(2, SmsLog.cut(name, 50));
            ps.setString(3, SmsLog.cut(contact, 20));
            ps.setObject(4, productId);
            ps.setString(5, SmsLog.cut(productName, 100));
            ps.setString(6, message == null ? "" : message);
            ps.setString(7, status.name());
            ps.setString(8, lastError == null ? "" : lastError);
            ps.setObject(9, now);
            ps.setObject(10, now);
            ps.executeUpdate();
            try (ResultSet keys = ps.getGeneratedKeys()) {
                if (!keys.next()) {
                    throw new SQLException("생성된 id 없음");
                }
                return keys.getLong(1);
            }
        }
    }
}
