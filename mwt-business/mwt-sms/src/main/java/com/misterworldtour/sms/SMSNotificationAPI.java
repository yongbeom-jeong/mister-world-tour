package com.misterworldtour.sms;

import com.misterworldtour.sms.SMSMessage.Recipient;
import com.misterworldtour.sms.SMSMessage.Status;

import org.springframework.data.domain.Page;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/** 출발 확정 문자 발송 (설계 9.1 SMSNotificationAPI). 수신 대상은 Travel Management 가 정해 넘긴다. */
public interface SMSNotificationAPI {

    /** 보내기 전에 발송 기록을 만들어 둔 문자 묶음. */
    record DeparturePlan(DepartureNotice notice, List<SMSMessage> messages, List<Recipient> invalid) {
    }

    class ResendException extends RuntimeException {
        public ResendException(String message) {
            super(message);
        }
    }

    String SMS_PREFIX = "[미스터월드투어]";
    /** 단문 한도 (영문·숫자·공백 1byte, 한글 2byte). */
    int SMS_MAX_BYTES = 90;

    record DepartureNotice(Long productId, String productName, LocalDate departureDate) {

        /** 단문(90byte) 안에 들어가는 안내 문자. 길면 이름 → 상품 이름 순으로 줄인다. */
        public String message(Recipient r) {
            String when = departureDate.getMonthValue() + "월 " + departureDate.getDayOfMonth() + "일";
            String full = SMS_PREFIX + " " + r.name() + "님, " + productName + " " + when + " 출발 확정되었습니다.";
            if (smsBytes(full) <= SMS_MAX_BYTES) {
                return full;
            }
            String tail = " " + when + " 출발 확정";
            String shortText = SMS_PREFIX + " " + productName + tail;
            if (smsBytes(shortText) <= SMS_MAX_BYTES) {
                return shortText;
            }
            int budget = SMS_MAX_BYTES - smsBytes(SMS_PREFIX + " .." + tail);
            StringBuilder product = new StringBuilder();
            for (int cp : productName.codePoints().toArray()) {
                String next = product + new String(Character.toChars(cp));
                if (smsBytes(next) > budget) {
                    break;
                }
                product.appendCodePoint(cp);
            }
            return SMS_PREFIX + " " + product + ".." + tail;
        }
    }

    static int smsBytes(String text) {
        return text.codePoints().map(c -> c < 128 ? 1 : 2).sum();
    }

    /** invalid 는 연락처 등이 없어 보내지 못한 대상. */
    record SMSBatchResult(List<SMSMessage> messages, List<Recipient> invalid) {

        public static SMSBatchResult allInvalid(List<Recipient> recipients) {
            return new SMSBatchResult(List.of(), List.copyOf(recipients));
        }

        int count(Status s) {
            return (int) messages.stream().filter(m -> m.getStatus() == s).count();
        }

        public int success() {
            return count(Status.SUCCESS);
        }

        public int failed() {
            return count(Status.FAILURE) + invalid.size();
        }

        public int unknown() {
            return count(Status.UNKNOWN);
        }

        public int total() {
            return messages.size() + invalid.size();
        }

        public String summary() {
            if (total() == 0) {
                return "보낼 문자가 없습니다.";
            }
            List<String> parts = new ArrayList<>();
            parts.add("성공 " + success() + "건");
            if (failed() > 0) {
                parts.add("실패 " + failed() + "건");
            }
            if (unknown() > 0) {
                parts.add("결과 확인 필요 " + unknown() + "건");
            }
            return "출발 확정 문자: " + String.join(", ", parts);
        }
    }

    /** 설계 SendDepartureConfirmation. 재시도 포함. */
    SMSBatchResult sendDepartureConfirmation(List<Recipient> recipients, DepartureNotice notice);

    /** 신청 저장과 같은 트랜잭션에서 발송 기록을 '발송 중'으로 먼저 만든다. */
    DeparturePlan prepareDepartureConfirmation(List<Recipient> recipients, DepartureNotice notice);

    /** 발송 기록 없이 문자 묶음만 만든다 (기록 저장에 실패했을 때). */
    DeparturePlan planWithoutLogs(List<Recipient> recipients, DepartureNotice notice);

    /** 준비한 묶음을 보낸다 (신청 저장 뒤). */
    SMSBatchResult sendDepartureConfirmation(DeparturePlan plan);

    Page<SMSMessage> loadMessages(boolean problemsOnly, int page);

    long problemCount();

    /** 실패·결과 불명·멈춘 기록을 다시 보낸다. */
    SMSMessage resendMessage(long messageId, long employeeId, boolean confirmDuplicate);
}
