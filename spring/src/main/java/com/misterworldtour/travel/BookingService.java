package com.misterworldtour.travel;

import com.misterworldtour.members.MemberService;
import com.misterworldtour.souvenir.Souvenir;
import com.misterworldtour.souvenir.SouvenirService;
import com.misterworldtour.souvenir.SouvenirService.IssueItem;
import com.misterworldtour.souvenir.SouvenirService.SouvenirShortage;
import com.misterworldtour.travel.Pricing.PriceBreakdown;
import com.misterworldtour.travel.Review.TravelerInput;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeSet;
import java.util.UUID;

/**
 * 고객의 상품 선택·구성(UC4)과 신청(UC5) 업무 처리 (설계 05장 TravelManager, Django 판 travel/booking.py).
 *
 * <ol>
 *   <li>{@link #configureTravelProduct}: 현재 구성 + 변경 항목 → 새 구성과 1인 가격. 실패하면 직전 구성 유지.</li>
 *   <li>{@link #reviewApplication}: 여행자·정원·기념품 재고·단골 할인을 검사하고 금액을 계산한다. 저장하지 않는다.</li>
 *   <li>{@link #submitTravelApplication}: 상품을 잠근 트랜잭션 안에서 모든 조건을 다시 검사해 저장하고 출발 상태를
 *       판정한다. 출발이 확정된 신청의 테마 기념품(품목마다 여행자 1인당 1개)을 같은 트랜잭션에서 차감한다.</li>
 * </ol>
 */
@Service
public class BookingService {

    static final int MAX_NAME = 50;

    /** 구성 변경 결과. */
    public record Configured(TravelConfig config, int unitPrice) {
    }

    /** 신청 등록 결과. recipientMemberIds 는 출발 확정 문자를 받을 회원 (없으면 빈 목록). */
    public record ApplicationResult(long applicationId, boolean honeymoon, DepartureStatus departureStatus,
                                    boolean newlyConfirmed, boolean duplicate, int souvenirsIssued,
                                    List<Long> recipientMemberIds) {
    }

    /** 검토 이후 금액이 바뀌어 다시 확인이 필요함. */
    public static class PriceChangedException extends BookingException {
        public PriceChangedException(String message) {
            super(message);
        }
    }

    /** 내 신청 목록의 한 줄. */
    public record MyApplication(TravelApplication application, int travelerCount) {
    }

    private final TravelProductRepository products;
    private final TravelApplicationRepository applications;
    private final TravelerRepository travelers;
    private final TravelHistoryRepository histories;
    private final MemberService members;
    private final SouvenirService souvenirs;
    private final Clock clock;

    BookingService(TravelProductRepository products, TravelApplicationRepository applications,
                   TravelerRepository travelers, TravelHistoryRepository histories, MemberService members,
                   SouvenirService souvenirs, Clock clock) {
        this.products = products;
        this.applications = applications;
        this.travelers = travelers;
        this.histories = histories;
        this.members = members;
        this.souvenirs = souvenirs;
        this.clock = clock;
    }

    LocalDate today() {
        return LocalDate.now(clock);
    }

    // ---------------------------------------------------------------- 조회 (고객)

    /** 고객 목록: 출발일이 지나지 않은 상품만 (조건이 null 이면 전체). */
    @Transactional(readOnly = true)
    public List<TravelProduct> bookableProducts(Theme theme, TourLevel level) {
        return products.search(today(), theme, level);
    }

    @Transactional(readOnly = true)
    public TravelProduct getBookableProduct(long productId) {
        TravelProduct product = products.findById(productId)
                .orElseThrow(() -> new BookingException("상품을 찾을 수 없습니다. 삭제되었을 수 있습니다."));
        if (!product.getDepartureDate().isAfter(today())) {
            throw new BookingException("출발일이 지난 상품은 신청할 수 없습니다.");
        }
        return product;
    }

    /** 같은 상품에 저장된 실제 여행자 수 (취소하지 않은 모든 신청 합계). */
    @Transactional(readOnly = true)
    public int currentTravelerCount(long productId) {
        return (int) travelers.countActiveForProduct(productId);
    }

    @Transactional(readOnly = true)
    public int remainingSeats(TravelProduct product) {
        if (product.isHoneymoon()) {
            return TravelRules.HONEYMOON_PARTY_SIZE;
        }
        return Math.max(TravelRules.GROUP_CAPACITY - currentTravelerCount(product.getId()), 0);
    }

    /** 상품 테마에 연결된 기념품 이름 (상세 화면 표시용). */
    public List<String> themeSouvenirNames(TravelProduct product) {
        return souvenirs.souvenirNamesForTheme(product.getTheme());
    }

    /** 신청 화면 첫 행(신청 고객 본인)에 보여 줄 계정 성명·연락처. */
    @Transactional(readOnly = true)
    public MemberService.Contact applicantContact(long memberId) {
        return members.getContact(memberId).orElse(new MemberService.Contact("", ""));
    }

    // ---------------------------------------------------------------- UC4 구성 변경

    /** 바뀐 항목만 적용한 새 구성과 1인 가격. 실패 시 ConfigurationException (current 는 그대로). */
    public Configured configureTravelProduct(TravelProduct product, Map<String, ?> current, Map<String, ?> changes) {
        TravelConfig config = ConfigurationRules.applyChanges(product, current, changes);
        return new Configured(config, Pricing.calculateUnitPrice(product, config));
    }

    public int calculateUnitPrice(TravelProduct product, TravelConfig config) {
        return Pricing.calculateUnitPrice(product, config);
    }

    // ---------------------------------------------------------------- UC5 검토

    /** 여행자 입력 검사. 첫 번째 행은 신청 고객 본인(계정 성명·연락처). 허니문은 정확히 2명. */
    List<TravelerInput> cleanTravelers(TravelProduct product, long memberId, List<TravelerInput> rows) {
        MemberService.Contact me = members.getContact(memberId)
                .orElseThrow(() -> new BookingException("회원 정보를 찾을 수 없습니다. 다시 로그인해 주세요."));
        Map<String, String> errors = new LinkedHashMap<>();
        List<TravelerInput> cleaned = new ArrayList<>();
        List<TravelerInput> input = rows.isEmpty() ? List.of(new TravelerInput("", "", true)) : rows;
        for (int i = 0; i < input.size(); i++) {
            String name = strip(input.get(i).name());
            String contactRaw = strip(input.get(i).contact());
            if (i == 0) { // 신청 고객 본인: 계정 정보 사용
                name = me.name();
                contactRaw = me.contact();
            }
            if (name.isEmpty() && contactRaw.isEmpty()) {
                continue; // 비어 있는 행은 무시
            }
            String key = "traveler_" + i;
            if (name.isEmpty()) {
                errors.put(key, (i + 1) + "번째 여행자의 성명을 입력해 주세요.");
                continue;
            }
            if (name.length() > MAX_NAME) {
                errors.put(key, (i + 1) + "번째 여행자의 성명이 너무 깁니다.");
                continue;
            }
            String contact = "";
            if (!contactRaw.isEmpty()) {
                Optional<String> normalized = MemberService.normalizeContact(contactRaw);
                if (normalized.isEmpty()) {
                    errors.put(key, (i + 1) + "번째 여행자의 연락처 형식이 올바르지 않습니다. 예: 010-1234-5678");
                    continue;
                }
                contact = normalized.get();
            }
            cleaned.add(new TravelerInput(name, contact, i == 0));
        }
        if (!errors.isEmpty()) {
            throw new BookingException("여행자 정보를 확인해 주세요.", errors);
        }
        if (product.isHoneymoon() && cleaned.size() != TravelRules.HONEYMOON_PARTY_SIZE) {
            throw new BookingException("허니문 상품은 신청 고객 1명과 동반 여행자 1명, 정확히 2명이어야 합니다.",
                    Map.of("travelers", "동반 여행자 1명의 정보를 입력해 주세요."));
        }
        if (cleaned.isEmpty()) {
            throw new BookingException("여행자를 1명 이상 입력해 주세요.");
        }
        return cleaned;
    }

    /**
     * 이 신청에 필요한 기념품 수 (테마 품목마다).
     * <ul>
     *   <li>허니문, 이미 확정된 상품: 이 신청의 여행자 수 (확정과 함께 차감)</li>
     *   <li>이 신청으로 새로 확정되는 상품: 그때까지의 모든 신청 + 이 신청 (확정 순간 전부 차감)</li>
     *   <li>아직 확정 전: 이 신청의 여행자 수만큼 있는지만 확인 (차감은 확정 때)</li>
     * </ul>
     */
    static int souvenirsNeeded(TravelProduct product, int travelerCount, int currentCount) {
        if (product.isHoneymoon() || product.isConfirmed()) {
            return travelerCount;
        }
        if (currentCount + travelerCount >= TravelRules.GROUP_MIN_DEPARTURE) {
            return currentCount + travelerCount;
        }
        return travelerCount;
    }

    private static String shortageMessage(int travelerCount, String name, int needed, int stock) {
        if (needed > travelerCount) {
            return "이 신청으로 출발이 확정되면 기념품 '%s' %d개가 필요하지만 재고가 %d개뿐이라 지금은 신청을 받을 수 없습니다."
                    .formatted(name, needed, stock);
        }
        return "기념품 '%s' 재고가 부족해 지금은 %d명 신청을 받을 수 없습니다. (남은 재고 %d개)"
                .formatted(name, travelerCount, stock);
    }

    /** 정원과 기념품 재고. 검토와 등록에서 같은 규칙을 쓴다. */
    private void checkBusinessRules(TravelProduct product, int travelerCount, int currentCount) {
        String problem = product.validateApplicationCapacity(currentCount, travelerCount);
        if (problem != null) {
            throw new BookingException(problem, Map.of("travelers", problem));
        }
        List<Souvenir> themeSouvenirs = souvenirs.souvenirsForTheme(product.getTheme());
        if (themeSouvenirs.isEmpty()) {
            throw new BookingException("이 테마의 기념품 정보가 없어 신청할 수 없습니다. 고객센터에 문의해 주세요.");
        }
        int needed = souvenirsNeeded(product, travelerCount, currentCount); // 테마 기념품마다 여행자 1인당 1개
        for (Souvenir s : themeSouvenirs) {
            if (s.getStockQuantity() < needed) {
                throw new BookingException(shortageMessage(travelerCount, s.getName(), needed, s.getStockQuantity()));
            }
        }
    }

    private PriceBreakdown price(TravelProduct product, long memberId, TravelConfig config, int travelerCount) {
        // TODO(완료 이력 이식 때): 다녀온 여행을 이력에 먼저 반영한 뒤 단골 여부 판단 (complete_finished_trips)
        int unit = Pricing.calculateUnitPrice(product, config);
        return Pricing.calculateApplicationPrice(unit, travelerCount, histories.findByMemberId(memberId), today());
    }

    @Transactional(readOnly = true)
    public Review reviewApplication(long memberId, long productId, TravelConfig config, List<TravelerInput> rows) {
        TravelProduct product = getBookableProduct(productId);
        Map<String, String> errors = ConfigurationRules.validate(product, config);
        if (!errors.isEmpty()) {
            throw new BookingException("여행 구성을 다시 확인해 주세요.", errors);
        }
        List<TravelerInput> cleaned = cleanTravelers(product, memberId, rows);
        checkBusinessRules(product, cleaned.size(), currentTravelerCount(productId));
        PriceBreakdown breakdown = price(product, memberId, config, cleaned.size());
        return new Review(UUID.randomUUID().toString().replace("-", ""), productId, memberId, config, cleaned,
                breakdown);
    }

    // ---------------------------------------------------------------- UC5 등록

    /** 같은 요청 키로 저장된 신청이 이번 제출과 고객·상품·구성·여행자까지 같은지. */
    private static boolean sameRequest(TravelApplication existing, long memberId, TravelProduct product, Review review) {
        if (existing.getMemberId() != memberId || !existing.getProduct().getId().equals(product.getId())) {
            return false;
        }
        TravelConfig saved = new TravelConfig(existing.getHotelLevel().name(), existing.getTransport().name(),
                existing.getMeal().name(), TravelConfig.normalizeDrinks(existing.getExtraDrinks()));
        if (!saved.equals(review.config())) {
            return false;
        }
        List<TravelerInput> savedTravelers = existing.getTravelers().stream()
                .map(t -> new TravelerInput(t.getName(), t.getContact(), t.isApplicant())).toList();
        return savedTravelers.equals(review.travelers());
    }

    @Transactional
    public ApplicationResult submitTravelApplication(long memberId, Review review) {
        if (review.memberId() != memberId) {
            throw new BookingException("다른 고객의 신청 내용입니다. 다시 검토해 주세요.");
        }
        // 같은 상품의 신청은 한 번에 하나씩 (정원 동시 초과 방지)
        TravelProduct product = products.findForUpdate(review.productId())
                .orElseThrow(() -> new BookingException("상품을 찾을 수 없습니다. 삭제되었을 수 있습니다."));

        Optional<TravelApplication> existing = applications.findByRequestKey(review.requestKey());
        if (existing.isPresent()) { // 같은 확인 제출의 재시도 → 기존 결과
            TravelApplication e = existing.get();
            if (!sameRequest(e, memberId, product, review)) {
                throw new BookingException("이미 다른 내용으로 처리된 신청 요청입니다.");
            }
            return new ApplicationResult(e.getId(), product.isHoneymoon(), e.currentDepartureStatus(), false, true, 0,
                    List.of());
        }

        if (!product.getDepartureDate().isAfter(today())) {
            throw new BookingException("출발일이 지난 상품은 신청할 수 없습니다.");
        }
        Map<String, String> errors = ConfigurationRules.validate(product, review.config());
        if (!errors.isEmpty()) {
            throw new BookingException("여행 구성을 다시 확인해 주세요.", errors);
        }
        int count = review.travelers().size();
        checkBusinessRules(product, count, currentTravelerCount(product.getId()));
        PriceBreakdown breakdown = price(product, memberId, review.config(), count);
        if (!breakdown.equals(review.breakdown())) {
            throw new PriceChangedException("검토 이후 금액이 바뀌었습니다 (%,d원 → %,d원). 다시 확인해 주세요."
                    .formatted(review.breakdown().finalAmount(), breakdown.finalAmount()));
        }

        TravelConfig c = review.config();
        TravelApplication app = new TravelApplication(memberId, product, HotelLevel.valueOf(c.hotelLevel()),
                TransportOption.valueOf(c.transport()), MealOption.valueOf(c.meal()), c.drinksCsv(),
                breakdown.unitPrice(), breakdown.subtotal(), breakdown.discountAmount(), breakdown.finalAmount(),
                review.requestKey(), product.isHoneymoon() ? DepartureStatus.CONFIRMED : null);
        for (TravelerInput t : review.travelers()) {
            app.addTraveler(t.name(), t.contact(), t.applicant());
        }
        app = applications.saveAndFlush(app);

        boolean newlyConfirmed = false;
        List<Long> recipients;
        List<IssueItem> toIssue;
        if (product.isHoneymoon()) {
            recipients = List.of(memberId); // 허니문: 이 신청 고객만
            toIssue = List.of(new IssueItem(app.getId(), count));
        } else {
            boolean wasConfirmed = product.isConfirmed();
            product.updateDepartureStatus(currentTravelerCount(product.getId()));
            newlyConfirmed = !wasConfirmed && product.isConfirmed();
            if (newlyConfirmed) { // 새로 확정: 이 상품의 모든 신청 고객 (취소 제외, 중복 제거)
                List<Object[]> rows = applications.activeRowsForProduct(product.getId());
                recipients = List.copyOf(new TreeSet<>(rows.stream().map(r -> (Long) r[1]).toList()));
                toIssue = rows.stream().map(r -> new IssueItem((Long) r[0], ((Number) r[2]).intValue())).toList();
            } else if (wasConfirmed) { // 이미 확정된 상품의 추가 신청: 새 신청 고객만
                recipients = List.of(memberId);
                toIssue = List.of(new IssueItem(app.getId(), count));
            } else {
                recipients = List.of();
                toIssue = List.of();
            }
        }

        // 출발 확정된 신청의 기념품 차감. 모자라면 신청 저장까지 모두 취소된다.
        int issued;
        try {
            issued = souvenirs.issueForDeparture(product.getTheme(), toIssue);
        } catch (SouvenirShortage e) {
            throw new BookingException(shortageMessage(count, e.getName(), e.getNeeded(), e.getStock()));
        }
        // TODO(문자 이식 때): 문자 대상과 발송 기록('발송 중')을 같은 트랜잭션에서 만들고, 저장 뒤 발송
        return new ApplicationResult(app.getId(), product.isHoneymoon(), app.currentDepartureStatus(), newlyConfirmed,
                false, issued, recipients);
    }

    // ---------------------------------------------------------------- 내 신청

    /** 같은 요청 키로 이미 저장된 이 고객의 신청 (재제출 안내용). */
    @Transactional(readOnly = true)
    public Optional<Long> findSubmittedApplication(long memberId, String requestKey) {
        if (requestKey == null || requestKey.isBlank()) {
            return Optional.empty();
        }
        return applications.findByMemberIdAndRequestKey(memberId, requestKey).map(TravelApplication::getId);
    }

    /** 고객 본인의 신청 1건 (상품·여행자 함께). 다른 고객의 신청이면 empty. */
    @Transactional(readOnly = true)
    public Optional<TravelApplication> getMyApplication(long memberId, long applicationId) {
        return applications.findByIdAndMemberId(applicationId, memberId);
    }

    /** 취소하지 않은 신청 수 (고객 홈 표시). */
    @Transactional(readOnly = true)
    public long activeApplicationCount(long memberId) {
        return applications.countByMemberIdAndCancelledAtIsNull(memberId);
    }

    @Transactional(readOnly = true)
    public List<MyApplication> myApplications(long memberId) {
        Map<Long, Integer> counts = new LinkedHashMap<>();
        for (Object[] row : applications.travelerCountsForMember(memberId)) {
            counts.put((Long) row[0], ((Number) row[1]).intValue());
        }
        return applications.findByMemberIdOrderBySubmittedAtDescIdDesc(memberId).stream()
                .map(a -> new MyApplication(a, counts.getOrDefault(a.getId(), 0))).toList();
    }

    private static String strip(String s) {
        return s == null ? "" : s.strip();
    }
}
