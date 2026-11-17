package com.misterworldtour.sms;

import com.misterworldtour.sms.SMSMessage.Recipient;
import com.misterworldtour.sms.SMSMessage.Status;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * SMS Notification 이 Travel Management 에 제공하는 출발 확정 문자 발송 (설계 9.1 SMSNotificationAPI).
 * 출발 확정 여부와 수신 대상은 Travel Management 가 정해 넘긴다.
 */
public interface SMSNotificationAPI {

    String SMS_PREFIX = "[미스터월드투어]";
    /** 단문 한도 (영문·숫자·공백 1byte, 한글 2byte). */
    int SMS_MAX_BYTES = 90;

    /** 출발 안내 정보: 상품 번호·이름·출발일. */
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

    /** 대상별 최종 결과와 성공·실패·결과 불명 건수. invalid 는 연락처 등 정보가 없어 보내지 못한 대상. */
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

    /** 고객별 문자를 만들어 보내고(재시도 포함) 결과를 기록해 돌려준다. */
    SMSBatchResult sendDepartureConfirmation(List<Recipient> recipients, DepartureNotice notice);
}
