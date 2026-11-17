package com.misterworldtour.souvenir;

import com.misterworldtour.dataaccess.DataAccessAPI;
import com.misterworldtour.members.LoginSession;
import com.misterworldtour.souvenir.SouvenirManagementAPI.StockReceiptException;
import com.misterworldtour.souvenir.SouvenirManagementAPI.StockReceiptPreparationResult;
import com.misterworldtour.souvenir.SouvenirManagementAPI.StockReceiptResponse;
import com.misterworldtour.souvenir.SouvenirManagementAPI.StockReceiptStatus;
import com.misterworldtour.travel.Theme;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * 기념품·재고 조회와 입고 준비·등록 (설계 6.1 SouvenirManager). 저장·조회는 {@link DataAccessAPI} 에 요청한다.
 *
 * <ul>
 *   <li>입고 준비: 직원 권한·품목·수량을 확인하고 요청 키와 최초 요청 시점을 서버에 보관한다. 저장하지 않는다.</li>
 *   <li>입고 등록: 같은 키로 이미 저장된 입고가 있으면 그 결과를 돌려주고 재고를 다시 늘리지 않는다. 없으면 준비한 내용과
 *       일치하는지 확인하고, 입고 내역 저장과 재고 증가를 함께 반영한 뒤 현재 재고를 다시 조회한다.</li>
 * </ul>
 * 설계 이후 추가: 직원의 품목 등록·수정·삭제, 출발 확정 시 테마 기념품 차감(Travel Management 가 호출).
 */
@Service
public class SouvenirManager implements SouvenirManagementAPI {

    /** 한 번에 입고할 수 있는 최대 수량 (임시 정책: 오입력 방지). */
    public static final int MAX_QUANTITY = 10_000;
    /** 직원마다 보관할 입고 준비 정보 수 (여러 탭 대비, 오래된 것부터 정리). */
    public static final int MAX_PENDING_KEYS = 20;
    public static final int MAX_NAME = 50;

    /** 서버에 보관하는 입고 준비 정보. */
    record PendingReceipt(long employeeId, long souvenirId, int quantity, OffsetDateTime receivedAt) {
    }

    /** 출발 확정에 필요한 기념품이 모자람. 호출자 트랜잭션이 취소되어 앞의 차감도 되돌아간다. */
    public static class SouvenirShortage extends RuntimeException {
        private final String name;
        private final int needed;
        private final int stock;

        public SouvenirShortage(String name, int needed, int stock) {
            super(name + ": needed=" + needed + " stock=" + stock);
            this.name = name;
            this.needed = needed;
            this.stock = stock;
        }

        public String getName() {
            return name;
        }

        public int getNeeded() {
            return needed;
        }

        public int getStock() {
            return stock;
        }
    }

    /** 차감할 신청 하나: 신청 번호와 여행자 수. */
    public record IssueItem(long applicationId, int travelerCount) {
    }

    /** 품목 입력 오류. errors 는 name·theme 별 안내. */
    public static class SouvenirRegistrationException extends RuntimeException {
        private final transient Map<String, String> errors;

        public SouvenirRegistrationException(Map<String, String> errors) {
            super(errors.toString());
            this.errors = errors;
        }

        public Map<String, String> getErrors() {
            return errors;
        }
    }

    public static class SouvenirNotFound extends RuntimeException {
    }

    private final DataAccessAPI dataAccess;
    private final TransactionTemplate tx;
    /** 요청 키 → 입고 준비 정보 (오래된 순). */
    private final LinkedHashMap<String, PendingReceipt> pending = new LinkedHashMap<>();

    SouvenirManager(DataAccessAPI dataAccess, PlatformTransactionManager transactionManager) {
        this.dataAccess = dataAccess;
        this.tx = new TransactionTemplate(transactionManager);
    }

    // ---------------------------------------------------------------- LoadSouvenirs

    @Override
    @Transactional(readOnly = true)
    public List<Souvenir> loadSouvenirs() {
        return dataAccess.findSouvenirs();
    }

    // ---------------------------------------------------------------- PrepareStockReceipt

    private static long currentEmployeeId() {
        return LoginSession.current().filter(LoginSession::isStaffMember).map(LoginSession::getMemberId)
                .orElseThrow(() -> new StockReceiptException(StockReceiptStatus.PERMISSION_DENIED,
                        "로그인이 만료되었거나 권한이 없습니다. 다시 로그인해 주세요."));
    }

    @Override
    public StockReceiptPreparationResult prepareStockReceipt(String souvenirId, String quantity) {
        return prepareStockReceipt(currentEmployeeId(), souvenirId, quantity);
    }

    StockReceiptPreparationResult prepareStockReceipt(long employeeId, String souvenirId, String quantity) {
        int q = validateQuantity(quantity);
        Souvenir s = findSouvenirForReceipt(souvenirId);
        String key = UUID.randomUUID().toString().replace("-", "");
        synchronized (pending) {
            pending.put(key, new PendingReceipt(employeeId, s.getSouvenirId(), q, OffsetDateTime.now()));
            List<String> mine = pending.entrySet().stream().filter(e -> e.getValue().employeeId() == employeeId)
                    .map(Map.Entry::getKey).toList();
            for (int i = 0; i < mine.size() - MAX_PENDING_KEYS; i++) {
                pending.remove(mine.get(i));
            }
        }
        return new StockReceiptPreparationResult(key, s.getSouvenirId(), s.getSouvenirName(), q);
    }

    static int validateQuantity(String quantity) {
        String text = quantity == null ? "" : quantity.strip();
        if (text.isEmpty() || !text.chars().allMatch(c -> c >= '0' && c <= '9')) { // 빈 값, 소수, 음수, 문자 거부
            throw new StockReceiptException(StockReceiptStatus.INVALID_INPUT, "입고 수량은 1 이상의 정수로 입력해 주세요.");
        }
        long q;
        try {
            q = Long.parseLong(text);
        } catch (NumberFormatException e) {
            q = Long.MAX_VALUE;
        }
        if (q < 1) {
            throw new StockReceiptException(StockReceiptStatus.INVALID_INPUT, "입고 수량은 1개 이상이어야 합니다.");
        }
        if (q > MAX_QUANTITY) {
            throw new StockReceiptException(StockReceiptStatus.INVALID_INPUT,
                    "한 번에 %,d개까지 입고할 수 있습니다.".formatted(MAX_QUANTITY));
        }
        return (int) q;
    }

    private Souvenir findSouvenirForReceipt(String souvenirId) {
        try {
            return dataAccess.findSouvenir(Long.parseLong(souvenirId == null ? "" : souvenirId.strip()))
                    .orElseThrow(() -> new StockReceiptException(StockReceiptStatus.NOT_FOUND, "등록된 기념품을 찾을 수 없습니다."));
        } catch (NumberFormatException e) {
            throw new StockReceiptException(StockReceiptStatus.NOT_FOUND, "등록된 기념품을 찾을 수 없습니다.");
        }
    }

    // ---------------------------------------------------------------- RecordSouvenirStockReceipt

    @Override
    public StockReceiptResponse recordSouvenirStockReceipt(String souvenirId, String quantity, String requestKey) {
        long employeeId;
        try {
            employeeId = currentEmployeeId();
        } catch (StockReceiptException e) {
            return StockReceiptResponse.of(e.getStatus(), e.getMessage());
        }
        return recordSouvenirStockReceipt(employeeId, souvenirId, quantity, requestKey);
    }

    StockReceiptResponse recordSouvenirStockReceipt(long employeeId, String souvenirId, String quantity,
                                                    String requestKey) {
        int q;
        long sid;
        try {
            q = validateQuantity(quantity);
            sid = Long.parseLong(souvenirId == null ? "" : souvenirId.strip());
        } catch (NumberFormatException e) {
            return StockReceiptResponse.of(StockReceiptStatus.INVALID_INPUT, "입고 품목이 올바르지 않습니다.");
        } catch (StockReceiptException e) {
            return StockReceiptResponse.of(e.getStatus(), e.getMessage());
        }
        if (requestKey == null || requestKey.isBlank()) {
            return StockReceiptResponse.of(StockReceiptStatus.INVALID_INPUT, "입고 요청 키가 없습니다. 다시 시도해 주세요.");
        }

        // 1) 같은 키로 이미 저장된 입고가 있는가 (재시도)
        Optional<StockReceipt> existing = dataAccess.findStockReceiptByRequestKey(requestKey);
        if (existing.isPresent()) {
            return existingResult(existing.get(), employeeId, sid, q, requestKey);
        }

        // 2) 서버에 보관한 준비 정보와 일치하는가
        PendingReceipt ctx;
        synchronized (pending) {
            ctx = pending.get(requestKey);
        }
        if (ctx == null || ctx.employeeId() != employeeId || ctx.souvenirId() != sid || ctx.quantity() != q) {
            return StockReceiptResponse.of(StockReceiptStatus.CONFLICT,
                    "준비한 입고 내용과 다릅니다. 품목과 수량을 확인한 뒤 다시 입고해 주세요.");
        }

        // 3) 입고 자료를 검증하고 입고 내역 저장 + 재고 증가를 함께 반영
        Optional<StockReceipt> saved;
        try {
            saved = tx.execute(status -> {
                Optional<Souvenir> souvenir = dataAccess.findSouvenir(sid);
                if (souvenir.isEmpty()) {
                    return Optional.empty();
                }
                StockReceipt receipt = new StockReceipt(souvenir.get(), employeeId, q, ctx.receivedAt(), requestKey);
                String problem = receipt.validateReceipt();
                if (problem != null) {
                    throw new StockReceiptException(StockReceiptStatus.INVALID_INPUT, problem);
                }
                return dataAccess.saveStockReceipt(receipt);
            });
        } catch (StockReceiptException e) {
            return StockReceiptResponse.of(e.getStatus(), e.getMessage());
        } catch (DataIntegrityViolationException e) {
            // 동시에 같은 키가 저장된 경우: 위 트랜잭션은 취소됐고, 먼저 저장된 결과를 확인한다
            Optional<StockReceipt> first = dataAccess.findStockReceiptByRequestKey(requestKey);
            if (first.isPresent()) {
                return existingResult(first.get(), employeeId, sid, q, requestKey);
            }
            return StockReceiptResponse.of(StockReceiptStatus.SAVE_FAILED, "입고를 저장하지 못했습니다. 다시 시도해 주세요.");
        }
        if (saved == null || saved.isEmpty()) {
            return StockReceiptResponse.of(StockReceiptStatus.NOT_FOUND, "등록된 기념품을 찾을 수 없습니다.");
        }
        forgetPending(requestKey); // 확인된 입고는 준비 정보 정리
        return success(saved.get(), "입고가 완료되었습니다.", false);
    }

    private StockReceiptResponse existingResult(StockReceipt existing, long employeeId, long sid, int q, String key) {
        if (!existing.matchesRequest(employeeId, sid, q)) {
            return StockReceiptResponse.of(StockReceiptStatus.CONFLICT, "이미 다른 내용으로 처리된 입고 요청입니다.");
        }
        forgetPending(key);
        return success(existing, "이미 처리된 입고입니다. 재고를 다시 늘리지 않았습니다.", true);
    }

    private void forgetPending(String key) {
        synchronized (pending) {
            pending.remove(key);
        }
    }

    /** 입고 성공 후 현재 재고를 다시 조회한다. 재조회가 실패해도 확인된 입고 성공은 유지한다. */
    private StockReceiptResponse success(StockReceipt receipt, String message, boolean already) {
        try {
            Souvenir current = dataAccess.findSouvenir(receipt.getSouvenirId()).orElseThrow();
            return new StockReceiptResponse(StockReceiptStatus.SUCCESS, message, receipt, current, true, already);
        } catch (RuntimeException e) {
            return new StockReceiptResponse(StockReceiptStatus.SUCCESS, message + " 현재 재고는 다시 조회해 주세요.",
                    receipt, null, false, already);
        }
    }

    // ---------------------------------------------------------------- 조회 (화면·다른 서브시스템)

    @Transactional(readOnly = true)
    public List<Souvenir> souvenirsForTheme(Theme theme) {
        return dataAccess.findSouvenirsByTheme(theme);
    }

    @Transactional(readOnly = true)
    public List<String> souvenirNamesForTheme(Theme theme) {
        return souvenirsForTheme(theme).stream().map(Souvenir::getSouvenirName).toList();
    }

    /** 숨긴 품목까지 포함한 전체 품목 (차감 내역 필터용). */
    @Transactional(readOnly = true)
    public List<Souvenir> loadAllSouvenirs() {
        return dataAccess.findAllSouvenirs();
    }

    @Transactional(readOnly = true)
    public boolean themeHasSouvenir(Theme theme) {
        return !dataAccess.findSouvenirsByTheme(theme).isEmpty();
    }

    @Transactional(readOnly = true)
    public Souvenir getSouvenir(long souvenirId) {
        return dataAccess.findSouvenir(souvenirId).orElseThrow(SouvenirNotFound::new);
    }

    @Transactional(readOnly = true)
    public long receiptCount(long souvenirId) {
        return dataAccess.countStockReceipts(souvenirId);
    }

    @Transactional(readOnly = true)
    public long issueCount(long souvenirId) {
        return dataAccess.countStockIssues(souvenirId);
    }

    /** 출발 확정 차감 내역 (최근 순). souvenirId 가 null 이면 전체. */
    @Transactional(readOnly = true)
    public Page<StockIssue> loadIssues(Long souvenirId, int page, int size) {
        return dataAccess.findStockIssues(souvenirId, page, size);
    }

    // ---------------------------------------------------------------- 품목 등록·수정·삭제 (직원)

    private String validateInput(String name, Theme theme, Long excludeId) {
        String n = name == null ? "" : name.strip();
        Map<String, String> errors = new LinkedHashMap<>();
        if (n.isEmpty()) {
            errors.put("name", "품목 이름을 입력해 주세요.");
        } else if (n.length() > MAX_NAME) {
            errors.put("name", "품목 이름은 " + MAX_NAME + "자 이내로 입력해 주세요.");
        } else {
            dataAccess.findSouvenirByName(n)
                    .filter(same -> !same.getSouvenirId().equals(excludeId))
                    .ifPresent(same -> errors.put("name", same.isActive() ? "이미 등록된 품목 이름입니다."
                            : "삭제한 품목 중 같은 이름이 있습니다 (내역 보존용). 다른 이름을 써 주세요."));
        }
        if (theme == null) {
            errors.put("theme", "연결할 테마를 선택해 주세요.");
        }
        if (!errors.isEmpty()) {
            throw new SouvenirRegistrationException(errors);
        }
        return n;
    }

    /**
     * 새 기념품 품목을 테마에 연결해 재고 0개로 등록한다. 수량은 입고로 늘린다.
     * 등록 즉시 그 테마 상품의 신청 검사·출발 확정 차감 대상이 된다 (테마 품목마다 여행자 1인당 1개).
     */
    @Transactional
    public Souvenir registerSouvenir(String name, Theme theme) {
        String n = validateInput(name, theme, null);
        try {
            return dataAccess.saveSouvenir(new Souvenir(n, theme));
        } catch (DataIntegrityViolationException e) { // 동시에 같은 이름이 등록된 경우
            throw new SouvenirRegistrationException(Map.of("name", "이미 등록된 품목 이름입니다."));
        }
    }

    /**
     * 이름·연결 테마 수정. 재고는 입고와 출발 확정 차감으로만 바뀐다.
     * 테마를 바꾸면 이후 출발 확정부터 새 테마 상품에서 차감된다 (이미 차감한 내역은 그대로).
     */
    @Transactional
    public Souvenir updateSouvenir(long souvenirId, String name, Theme theme) {
        Souvenir s = dataAccess.findSouvenirForUpdate(souvenirId).orElseThrow(SouvenirNotFound::new);
        s.rename(validateInput(name, theme, s.getSouvenirId()), theme);
        try {
            return dataAccess.saveSouvenir(s);
        } catch (DataIntegrityViolationException e) {
            throw new SouvenirRegistrationException(Map.of("name", "이미 등록된 품목 이름입니다."));
        }
    }

    /** 품목 삭제. 내역이 없으면 실제로 지우고 true, 입고·차감 내역이 있으면 숨기고(내역 보존) false. */
    @Transactional
    public boolean deleteSouvenir(long souvenirId) {
        Souvenir s = dataAccess.findSouvenirForUpdate(souvenirId).orElseThrow(SouvenirNotFound::new);
        if (dataAccess.countStockReceipts(souvenirId) > 0 || dataAccess.countStockIssues(souvenirId) > 0) {
            s.hide();
            dataAccess.saveSouvenir(s);
            return false;
        }
        dataAccess.deleteSouvenir(s);
        return true;
    }

    // ---------------------------------------------------------------- 출발 확정 차감 (Travel Management 가 호출)

    /**
     * 출발 확정된 신청들의 테마 기념품을 차감한다.
     *
     * <p>테마에 연결된 품목마다 여행자 1인당 1개. 호출자의 트랜잭션 안에서 실행하며, 한 품목이라도 모자라면
     * {@link SouvenirShortage} 를 낸다. (품목, 신청)마다 한 번만 차감하고, 재고 감소는 '재고 >= 필요 수량'
     * 조건부 UPDATE 라 음수가 되지 않는다. 반환값은 차감한 총 개수 (모든 품목 합계).
     */
    @Transactional
    public int issueForDeparture(Theme theme, List<IssueItem> items) {
        if (items.isEmpty()) {
            return 0;
        }
        List<Long> appIds = items.stream().map(IssueItem::applicationId).toList();
        int total = 0;
        for (Souvenir souvenir : souvenirsForTheme(theme)) {
            Set<Long> done = dataAccess.findIssuedApplicationIds(souvenir.getSouvenirId(), appIds);
            List<IssueItem> todo = items.stream()
                    .filter(i -> !done.contains(i.applicationId()) && i.travelerCount() > 0).toList();
            int needed = todo.stream().mapToInt(IssueItem::travelerCount).sum();
            if (needed == 0) {
                continue;
            }
            boolean decreased = dataAccess.decreaseStock(souvenir.getSouvenirId(), needed);
            int stock = dataAccess.findStockQuantity(souvenir.getSouvenirId());
            if (!decreased) {
                throw new SouvenirShortage(souvenir.getSouvenirName(), needed, stock);
            }
            int remaining = stock + needed;
            for (IssueItem item : todo) {
                remaining -= item.travelerCount();
                dataAccess.saveStockIssue(new StockIssue(souvenir, item.applicationId(), item.travelerCount(), remaining));
            }
            total += needed;
        }
        return total;
    }

    // ---------------------------------------------------------------- 데모 데이터

    /** 데모 데이터용 초기 재고 (입고 내역으로 기록). 같은 요청 키는 한 번만 반영된다. */
    @Transactional
    public boolean seedStock(String souvenirName, int quantity, long employeeId, String requestKey) {
        Souvenir s = dataAccess.findSouvenirByName(souvenirName).orElse(null);
        if (s == null || !s.isActive() || dataAccess.findStockReceiptByRequestKey(requestKey).isPresent()) {
            return false;
        }
        return dataAccess.saveStockReceipt(new StockReceipt(s, employeeId, quantity, OffsetDateTime.now(), requestKey))
                .isPresent();
    }
}
