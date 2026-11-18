package com.misterworldtour.sms;

import org.springframework.data.domain.Page;

import java.time.OffsetDateTime;
import java.util.Optional;

/**
 * 문자 발송 기록의 저장과 조회. SMS Notification 이 필요로 하는 인터페이스이며,
 * Data Access 서브시스템의 {@code DataAccessManager} 가 구현한다 (의존성 역전: 인터페이스는 쓰는 쪽이 가진다).
 */
public interface SMSMessageDataAccessAPI {

    /**
     * 발송 기록을 저장하고 번호를 붙인다. 바깥 트랜잭션(신청 저장) 안에서 불러도 실패가 바깥을 되돌리지 않게
     * 저장점을 두고 저장한다. 실패하면 예외.
     */
    long insertSMSMessage(SMSMessage message);

    /** 메모리에서 바뀐 발송 결과를 저장된 기록에 반영한다. resentBy 가 있으면 재발송 횟수도 센다. */
    void saveSMSMessageResult(SMSMessage message, Long resentBy);

    Optional<SMSMessage> findSMSMessage(long id);

    Optional<SMSMessage> findSMSMessageForUpdate(long id);

    Page<SMSMessage> findSMSMessages(int page, int size);

    /** 확인 필요: 실패·결과 불명·stalePendingBefore 보다 오래 멈춘 발송 중 (최근 순). */
    Page<SMSMessage> findProblemSMSMessages(OffsetDateTime stalePendingBefore, int page, int size);
}
