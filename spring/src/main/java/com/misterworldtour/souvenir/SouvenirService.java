package com.misterworldtour.souvenir;

import com.misterworldtour.travel.Theme;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Souvenir Management 서비스 (설계 06장 SouvenirManager, Django 판 souvenir/services.py).
 *
 * <p>Travel Management 는 테마 기념품 조회와 출발 확정 차감({@link #issueForDeparture})을 이 서비스로 한다.
 */
@Service
public class SouvenirService {

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

    /** 차감할 신청 하나: 신청 id 와 여행자 수. */
    public record IssueItem(long applicationId, int travelerCount) {
    }

    private final SouvenirRepository souvenirs;
    private final StockIssueRepository issues;
    private final StockReceiptRepository receipts;

    SouvenirService(SouvenirRepository souvenirs, StockIssueRepository issues, StockReceiptRepository receipts) {
        this.souvenirs = souvenirs;
        this.issues = issues;
        this.receipts = receipts;
    }

    // ---------------------------------------------------------------- 조회

    /** 사용 중인 품목 (테마·이름 순). */
    @Transactional(readOnly = true)
    public List<Souvenir> loadSouvenirs() {
        return souvenirs.findByActiveTrueOrderByThemeAscNameAsc();
    }

    @Transactional(readOnly = true)
    public List<Souvenir> souvenirsForTheme(Theme theme) {
        return souvenirs.findByThemeAndActiveTrueOrderByIdAsc(theme);
    }

    @Transactional(readOnly = true)
    public List<String> souvenirNamesForTheme(Theme theme) {
        return souvenirsForTheme(theme).stream().map(Souvenir::getName).toList();
    }

    /** 숨긴 품목까지 포함한 전체 품목 (차감 내역 필터용). */
    @Transactional(readOnly = true)
    public List<Souvenir> loadAllSouvenirs() {
        return souvenirs.findAllByOrderByThemeAscNameAsc();
    }

    @Transactional(readOnly = true)
    public boolean themeHasSouvenir(Theme theme) {
        return souvenirs.existsByThemeAndActiveTrue(theme);
    }

    @Transactional(readOnly = true)
    public Souvenir getSouvenir(long souvenirId) {
        return souvenirs.findByIdAndActiveTrue(souvenirId).orElseThrow(SouvenirNotFound::new);
    }

    @Transactional(readOnly = true)
    public long receiptCount(long souvenirId) {
        return receipts.countBySouvenirId(souvenirId);
    }

    @Transactional(readOnly = true)
    public long issueCount(long souvenirId) {
        return issues.countBySouvenirId(souvenirId);
    }

    /** 출발 확정 차감 내역 (최근 순). souvenirId 가 null 이면 전체. */
    @Transactional(readOnly = true)
    public Page<StockIssue> loadIssues(Long souvenirId, int page, int size) {
        return issues.search(souvenirId, PageRequest.of(Math.max(page, 0), size));
    }

    // ---------------------------------------------------------------- 품목 등록·수정·삭제 (직원)

    public static final int MAX_NAME = 50;

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

    private String validateInput(String name, Theme theme, Long excludeId) {
        String n = name == null ? "" : name.strip();
        Map<String, String> errors = new LinkedHashMap<>();
        if (n.isEmpty()) {
            errors.put("name", "품목 이름을 입력해 주세요.");
        } else if (n.length() > MAX_NAME) {
            errors.put("name", "품목 이름은 " + MAX_NAME + "자 이내로 입력해 주세요.");
        } else {
            souvenirs.findByNameKey(Souvenir.keyOf(n))
                    .filter(same -> !same.getId().equals(excludeId))
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
     * 새 기념품 품목을 테마에 연결해 재고 0개로 등록한다. 수량은 입고(UC8)로 늘린다.
     * 등록 즉시 그 테마 상품의 신청 검사·출발 확정 차감 대상이 된다 (테마 품목마다 여행자 1인당 1개).
     */
    @Transactional
    public Souvenir registerSouvenir(String name, Theme theme) {
        String n = validateInput(name, theme, null);
        try {
            return souvenirs.saveAndFlush(new Souvenir(n, theme));
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
        Souvenir s = souvenirs.findActiveForUpdate(souvenirId).orElseThrow(SouvenirNotFound::new);
        s.rename(validateInput(name, theme, s.getId()), theme);
        try {
            souvenirs.flush();
        } catch (DataIntegrityViolationException e) {
            throw new SouvenirRegistrationException(Map.of("name", "이미 등록된 품목 이름입니다."));
        }
        return s;
    }

    /** 품목 삭제. 내역이 없으면 실제로 지우고 true, 입고·차감 내역이 있으면 숨기고(내역 보존) false. */
    @Transactional
    public boolean deleteSouvenir(long souvenirId) {
        Souvenir s = souvenirs.findActiveForUpdate(souvenirId).orElseThrow(SouvenirNotFound::new);
        if (receipts.existsBySouvenirId(souvenirId) || issues.existsBySouvenirId(souvenirId)) {
            s.hide();
            return false;
        }
        souvenirs.delete(s);
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
            Set<Long> done = issues.issuedApplicationIds(souvenir.getId(), appIds);
            List<IssueItem> todo = items.stream()
                    .filter(i -> !done.contains(i.applicationId()) && i.travelerCount() > 0).toList();
            int needed = todo.stream().mapToInt(IssueItem::travelerCount).sum();
            if (needed == 0) {
                continue;
            }
            int updated = souvenirs.decreaseIfEnough(souvenir.getId(), needed);
            int stock = souvenirs.currentStock(souvenir.getId());
            if (updated == 0) {
                throw new SouvenirShortage(souvenir.getName(), needed, stock);
            }
            int remaining = stock + needed;
            for (IssueItem item : todo) {
                remaining -= item.travelerCount();
                issues.save(new StockIssue(souvenir, item.applicationId(), item.travelerCount(), remaining));
            }
            total += needed;
        }
        return total;
    }

    /** 신청별 차감 수 (품목 합계). 테스트·화면 확인용. */
    @Transactional(readOnly = true)
    public int issuedFor(long applicationId) {
        return issues.findByApplicationId(applicationId).stream().mapToInt(StockIssue::getQuantity).sum();
    }

    // ---------------------------------------------------------------- 데모 데이터

    /**
     * 데모 데이터용 초기 재고 (입고 내역으로 기록). 같은 요청 키는 한 번만 반영된다.
     * 직원 화면의 입고는 준비 → 등록 두 단계로 한다 (기념품 화면 이식 때).
     */
    @Transactional
    public boolean seedStock(String souvenirName, int quantity, long employeeId, String requestKey) {
        Souvenir s = souvenirs.findByNameKey(Souvenir.keyOf(souvenirName)).orElse(null);
        if (s == null || !s.isActive() || receipts.existsByRequestKey(requestKey)) {
            return false;
        }
        souvenirs.increaseIfActive(s.getId(), quantity);
        int after = souvenirs.currentStock(s.getId());
        receipts.save(new StockReceipt(s, employeeId, quantity, OffsetDateTime.now(), requestKey, after));
        return true;
    }

    /** 품목별 현재 재고 (이름 → 재고). 테스트·화면 확인용. */
    @Transactional(readOnly = true)
    public Map<String, Integer> stockByName() {
        return souvenirs.findAll().stream()
                .collect(java.util.stream.Collectors.toMap(Souvenir::getName, s -> souvenirs.currentStock(s.getId())));
    }
}
