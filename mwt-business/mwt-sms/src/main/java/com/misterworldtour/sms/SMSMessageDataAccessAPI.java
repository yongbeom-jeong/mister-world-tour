package com.misterworldtour.sms;

import org.springframework.data.domain.Page;

import java.time.OffsetDateTime;
import java.util.Optional;

/** 문자 발송 기록 저장·조회. Data Access 의 {@code DataAccessManager} 가 구현한다. */
public interface SMSMessageDataAccessAPI {

    /** 저장점을 두고 저장해, 실패해도 바깥 트랜잭션(신청 저장)을 되돌리지 않는다. */
    long insertSMSMessage(SMSMessage message);

    /** resentBy 가 있으면 재발송 횟수도 센다. */
    void saveSMSMessageResult(SMSMessage message, Long resentBy);

    Optional<SMSMessage> findSMSMessage(long id);

    Optional<SMSMessage> findSMSMessageForUpdate(long id);

    Page<SMSMessage> findSMSMessages(int page, int size);

    /** 확인 필요: 실패·결과 불명·stalePendingBefore 보다 오래 멈춘 발송 중 (최근 순). */
    Page<SMSMessage> findProblemSMSMessages(OffsetDateTime stalePendingBefore, int page, int size);
}
