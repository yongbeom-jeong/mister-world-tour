package com.misterworldtour.souvenir;

import java.util.List;

/**
 * Souvenir Management 가 User Interface 에 제공하는 기념품·재고 조회와 입고 준비·등록 (설계 6.1 SouvenirManagementAPI).
 * 입고는 로그인한 직원만 할 수 있고, 준비(PrepareStockReceipt) → 등록(RecordSouvenirStockReceipt) 두 단계다.
 */
public interface SouvenirManagementAPI {

    /** 입고 결과 상태 (7가지). RESULT_UNKNOWN 은 서버가 아니라 화면 스크립트가 응답 불명일 때 판정한다. */
    enum StockReceiptStatus {
        SUCCESS, INVALID_INPUT, PERMISSION_DENIED, NOT_FOUND, CONFLICT, SAVE_FAILED, RESULT_UNKNOWN
    }

    /** 입고 준비 결과: 요청 키와 확인한 품목·수량. */
    record StockReceiptPreparationResult(String requestKey, long souvenirId, String souvenirName, int quantity) {
    }

    /**
     * 입고 등록 결과. currentSouvenir 는 입고 뒤 다시 조회한 품목 (재조회에 실패하면 null 이고 stockRefreshSucceeded 가 거짓).
     * alreadyRecorded 는 같은 요청 키로 이미 반영된 입고였는지.
     */
    record StockReceiptResponse(StockReceiptStatus status, String message, StockReceipt receipt,
                                Souvenir currentSouvenir, boolean stockRefreshSucceeded, boolean alreadyRecorded) {
        static StockReceiptResponse of(StockReceiptStatus status, String message) {
            return new StockReceiptResponse(status, message, null, null, false, false);
        }
    }

    /** 입고 준비 실패. */
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

    /** 등록된 기념품 목록과 현재 재고 (사용 중인 품목, 테마·이름 순). */
    List<Souvenir> loadSouvenirs();

    /** 직원 권한·품목·수량(1 이상의 정수)을 확인하고 요청 키와 최초 요청 시점을 정한다. 저장·재고 증가는 하지 않는다. */
    StockReceiptPreparationResult prepareStockReceipt(String souvenirId, String quantity);

    /** 준비한 입고를 저장하고 재고를 늘린다. 같은 요청 키의 재시도는 재고를 다시 늘리지 않고 기존 결과를 돌려준다. */
    StockReceiptResponse recordSouvenirStockReceipt(String souvenirId, String quantity, String requestKey);
}
