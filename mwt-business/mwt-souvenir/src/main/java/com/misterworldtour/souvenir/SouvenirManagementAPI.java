package com.misterworldtour.souvenir;

import com.misterworldtour.travel.Theme;

import org.springframework.data.domain.Page;

import java.util.List;
import java.util.Map;

/**
 * 기념품·재고 조회와 입고 (설계 6.1 SouvenirManagementAPI).
 * 입고는 직원만, 준비(PrepareStockReceipt) → 등록(RecordSouvenirStockReceipt) 두 단계다.
 */
public interface SouvenirManagementAPI {

    class SouvenirRegistrationException extends RuntimeException {
        private final transient Map<String, String> errors;

        public SouvenirRegistrationException(Map<String, String> errors) {
            super(errors.toString());
            this.errors = errors;
        }

        public Map<String, String> getErrors() {
            return errors;
        }
    }

    class SouvenirNotFound extends RuntimeException {
    }

    /** RESULT_UNKNOWN 은 서버가 아니라 화면 스크립트가 응답 불명일 때 판정한다. */
    enum StockReceiptStatus {
        SUCCESS, INVALID_INPUT, PERMISSION_DENIED, NOT_FOUND, CONFLICT, SAVE_FAILED, RESULT_UNKNOWN
    }

    record StockReceiptPreparationResult(String requestKey, long souvenirId, String souvenirName, int quantity) {
    }

    /** currentSouvenir 는 입고 뒤 재조회한 품목 (실패하면 null). alreadyRecorded 는 같은 키로 이미 반영된 입고였는지. */
    record StockReceiptResponse(StockReceiptStatus status, String message, StockReceipt receipt,
                                Souvenir currentSouvenir, boolean stockRefreshSucceeded, boolean alreadyRecorded) {
        static StockReceiptResponse of(StockReceiptStatus status, String message) {
            return new StockReceiptResponse(status, message, null, null, false, false);
        }
    }

    class StockReceiptException extends RuntimeException {
        private final StockReceiptStatus status;

        public StockReceiptException(StockReceiptStatus status, String message) {
            super(message);
            this.status = status;
        }

        public StockReceiptStatus getStatus() {
            return status;
        }
    }

    List<Souvenir> loadSouvenirs();

    /** 권한·품목·수량을 확인하고 요청 키와 최초 요청 시점을 정한다. 저장은 하지 않는다. */
    StockReceiptPreparationResult prepareStockReceipt(String souvenirId, String quantity);

    /** 같은 요청 키의 재시도는 재고를 다시 늘리지 않고 기존 결과를 돌려준다. */
    StockReceiptResponse recordSouvenirStockReceipt(String souvenirId, String quantity, String requestKey);

    /** 숨긴 품목 포함. */
    List<Souvenir> loadAllSouvenirs();

    boolean themeHasSouvenir(Theme theme);

    Souvenir getSouvenir(long souvenirId);

    long receiptCount(long souvenirId);

    long issueCount(long souvenirId);

    Page<StockIssue> loadIssues(Long souvenirId, int page, int size);

    Souvenir registerSouvenir(String name, Theme theme);

    Souvenir updateSouvenir(long souvenirId, String name, Theme theme);

    /** 내역이 없으면 삭제(true), 있으면 숨김(false). */
    boolean deleteSouvenir(long souvenirId);
}
