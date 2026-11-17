package com.misterworldtour.souvenir;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.io.Serializable;
import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.Optional;
import java.util.UUID;

/**
 * UC8 기념품 입고 (설계 06장, Django 판 souvenir/services.py 의 입고 부분).
 *
 * <p>입고는 준비(prepare) → 등록(record) 두 단계다.
 * <ul>
 *   <li>준비: 입력을 검사하고 요청 키와 최초 요청 시점을 정해 사용자별 서버 문맥(세션)에 보관한다. 저장하지 않는다.</li>
 *   <li>등록: 같은 키로 이미 저장된 입고가 있으면 그 결과를 돌려주고 재고를 다시 늘리지 않는다. 없으면 준비한 내용과
 *       일치하는지 확인하고, 입고 내역 저장과 재고 증가를 한 트랜잭션으로 반영한다.</li>
 * </ul>
 */
@Service
public class StockReceiptService {

    /** 한 번에 입고할 수 있는 최대 수량 (임시 정책: 오입력 방지). */
    public static final int MAX_QUANTITY = 10_000;
    /** 세션에 보관할 준비 문맥 수 (여러 탭 대비, 오래된 것부터 정리). */
    public static final int MAX_PENDING_KEYS = 20;

    /** 입고 결과 (설계 06장 §5, 7가지). RESULT_UNKNOWN 은 서버가 아니라 화면 스크립트가 판정한다. */
    public enum Status {
        SUCCESS, INVALID_INPUT, PERMISSION_DENIED, NOT_FOUND, CONFLICT, SAVE_FAILED, RESULT_UNKNOWN
    }

    /** 세션에 두는 준비 문맥. */
    public record Pending(long employeeId, long souvenirId, int quantity, OffsetDateTime receivedAt)
            implements Serializable {
    }

    /** 준비 결과. */
    public record Prepared(String requestKey, long souvenirId, String souvenirName, int quantity) {
    }

    /** 등록 결과. */
    public record Response(Status status, String message, StockReceipt receipt, Souvenir currentSouvenir,
                           boolean stockRefreshSucceeded, boolean alreadyRecorded) {
        static Response of(Status status, String message) {
            return new Response(status, message, null, null, false, false);
        }
    }

    public static class ReceiptException extends RuntimeException {
        private final Status status;

        public ReceiptException(Status status, String message) {
            super(message);
            this.status = status;
        }

        public Status getStatus() {
            return status;
        }
    }

    private final SouvenirRepository souvenirs;
    private final StockReceiptRepository receipts;
    private final TransactionTemplate tx;

    StockReceiptService(SouvenirRepository souvenirs, StockReceiptRepository receipts,
                        PlatformTransactionManager transactionManager) {
        this.souvenirs = souvenirs;
        this.receipts = receipts;
        this.tx = new TransactionTemplate(transactionManager);
    }

    static int validateQuantity(String quantity) {
        String text = quantity == null ? "" : quantity.strip();
        if (text.isEmpty() || !text.chars().allMatch(c -> c >= '0' && c <= '9')) { // 빈 값, 소수, 음수, 문자 거부
            throw new ReceiptException(Status.INVALID_INPUT, "입고 수량은 1 이상의 정수로 입력해 주세요.");
        }
        long q;
        try {
            q = Long.parseLong(text);
        } catch (NumberFormatException e) {
            q = Long.MAX_VALUE;
        }
        if (q < 1) {
            throw new ReceiptException(Status.INVALID_INPUT, "입고 수량은 1개 이상이어야 합니다.");
        }
        if (q > MAX_QUANTITY) {
            throw new ReceiptException(Status.INVALID_INPUT, "한 번에 %,d개까지 입고할 수 있습니다.".formatted(MAX_QUANTITY));
        }
        return (int) q;
    }

    private Souvenir findSouvenir(String souvenirId) {
        try {
            return souvenirs.findByIdAndActiveTrue(Long.parseLong(souvenirId == null ? "" : souvenirId.strip()))
                    .orElseThrow(() -> new ReceiptException(Status.NOT_FOUND, "등록된 기념품을 찾을 수 없습니다."));
        } catch (NumberFormatException e) {
            throw new ReceiptException(Status.NOT_FOUND, "등록된 기념품을 찾을 수 없습니다.");
        }
    }

    /** 입고 준비. pending 은 사용자 세션의 준비 문맥 저장소이며 이 메서드가 갱신한다. */
    public Prepared prepare(LinkedHashMap<String, Pending> pending, long employeeId, String souvenirId, String quantity) {
        int q = validateQuantity(quantity);
        Souvenir s = findSouvenir(souvenirId);
        String key = UUID.randomUUID().toString().replace("-", "");
        pending.put(key, new Pending(employeeId, s.getId(), q, OffsetDateTime.now()));
        while (pending.size() > MAX_PENDING_KEYS) {
            pending.remove(pending.keySet().iterator().next());
        }
        return new Prepared(key, s.getId(), s.getName(), q);
    }

    public Response record(LinkedHashMap<String, Pending> pending, long employeeId, String souvenirId, String quantity,
                           String requestKey) {
        int q;
        long sid;
        try {
            q = validateQuantity(quantity);
            sid = Long.parseLong(souvenirId == null ? "" : souvenirId.strip());
        } catch (NumberFormatException e) {
            return Response.of(Status.INVALID_INPUT, "입고 품목이 올바르지 않습니다.");
        } catch (ReceiptException e) {
            return Response.of(e.getStatus(), e.getMessage());
        }
        if (requestKey == null || requestKey.isBlank()) {
            return Response.of(Status.INVALID_INPUT, "입고 요청 키가 없습니다. 다시 시도해 주세요.");
        }

        // 1) 같은 키로 이미 저장된 입고가 있는가 (재시도)
        Optional<StockReceipt> existing = receipts.findByRequestKey(requestKey);
        if (existing.isPresent()) {
            return existingResult(existing.get(), employeeId, sid, q, pending, requestKey);
        }

        // 2) 준비한 내용과 일치하는가
        Pending ctx = pending.get(requestKey);
        if (ctx == null || ctx.employeeId() != employeeId || ctx.souvenirId() != sid || ctx.quantity() != q) {
            return Response.of(Status.CONFLICT, "준비한 입고 내용과 다릅니다. 품목과 수량을 확인한 뒤 다시 입고해 주세요.");
        }

        // 3) 입고 내역 저장 + 재고 증가를 한 트랜잭션으로
        StockReceipt saved;
        try {
            saved = tx.execute(status -> {
                if (souvenirs.increaseIfActive(sid, q) == 0) {
                    return null;
                }
                int after = souvenirs.currentStock(sid);
                Souvenir s = souvenirs.getReferenceById(sid);
                return receipts.saveAndFlush(new StockReceipt(s, employeeId, q, ctx.receivedAt(), requestKey, after));
            });
        } catch (DataIntegrityViolationException e) {
            // 동시에 같은 키가 저장된 경우: 위 트랜잭션은 취소됐고, 먼저 저장된 결과를 확인한다
            Optional<StockReceipt> first = receipts.findByRequestKey(requestKey);
            if (first.isPresent()) {
                return existingResult(first.get(), employeeId, sid, q, pending, requestKey);
            }
            return Response.of(Status.SAVE_FAILED, "입고를 저장하지 못했습니다. 다시 시도해 주세요.");
        }
        if (saved == null) {
            return Response.of(Status.NOT_FOUND, "등록된 기념품을 찾을 수 없습니다.");
        }
        pending.remove(requestKey); // 확인된 입고는 제출 문맥 종료
        return success(saved, "입고가 완료되었습니다.", false);
    }

    private Response existingResult(StockReceipt existing, long employeeId, long sid, int q,
                                    LinkedHashMap<String, Pending> pending, String key) {
        if (!existing.matchesRequest(employeeId, sid, q)) {
            return Response.of(Status.CONFLICT, "이미 다른 내용으로 처리된 입고 요청입니다.");
        }
        pending.remove(key);
        return success(existing, "이미 처리된 입고입니다. 재고를 다시 늘리지 않았습니다.", true);
    }

    private Response success(StockReceipt receipt, String message, boolean already) {
        try {
            Souvenir current = souvenirs.findById(receipt.getSouvenir().getId()).orElseThrow();
            return new Response(Status.SUCCESS, message, receipt, current, true, already);
        } catch (RuntimeException e) { // 입고는 성공했지만 현재 재고 재조회 실패 → 성공은 유지
            return new Response(Status.SUCCESS, message + " 현재 재고는 다시 조회해 주세요.", receipt, null, false, already);
        }
    }
}
