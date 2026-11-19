package com.misterworldtour.sms;

import java.time.Duration;

/**
 * 외부 문자 서비스 클라이언트 (설계 9.1 SMSServiceClient).
 * 실패는 SendException(재시도), 보내기 전 거부는 RejectedException(재시도 안 함),
 * 보냈는지 알 수 없으면 UnknownResultException(중복 문자 방지로 재시도 안 함).
 */
public interface SMSServiceClient {

    /** 성공하면 서비스 요청 식별값(없으면 빈 문자열). */
    String sendSMS(String contact, String message);

    /** 외부 호출 1회의 최대 대기 (묶음 시간 한도 계산용). */
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
