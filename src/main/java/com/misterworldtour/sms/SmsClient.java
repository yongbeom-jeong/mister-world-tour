package com.misterworldtour.sms;

import java.time.Duration;

/**
 * 외부 문자 서비스 클라이언트 (설계 09장 SMSServiceClient). {@code send} 만 있으면 교체할 수 있다.
 *
 * <ul>
 *   <li>{@link SendException}: 발송 실패가 명확함 (재시도 가능)</li>
 *   <li>{@link RejectedException}: 보내기 전에 거부됨 (허용 목록 밖 번호 등, 다시 보내도 같으므로 재시도 안 함)</li>
 *   <li>{@link UnknownResultException}: 보냈는지 알 수 없음 (중복 문자 방지를 위해 재시도 안 함)</li>
 * </ul>
 */
public interface SmsClient {

    /** 문자 1통을 보낸다. 업체 접수 번호를 돌려줄 수 있다 (없으면 빈 문자열). */
    String send(String contact, String message);

    /** 외부 호출 1회의 최대 대기 시간 (발송 묶음 시간 한도 계산용). 가짜 클라이언트는 0. */
    default Duration timeout() {
        return Duration.ZERO;
    }

    class SendException extends RuntimeException {
        public SendException(String message) {
            super(message);
        }

        public SendException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    class RejectedException extends SendException {
        public RejectedException(String message) {
            super(message);
        }
    }

    class UnknownResultException extends RuntimeException {
        public UnknownResultException(String message) {
            super(message);
        }

        public UnknownResultException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
