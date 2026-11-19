package com.misterworldtour.travel;

import com.misterworldtour.members.LoginSession;
import com.misterworldtour.members.MemberManagementAPI;
import com.misterworldtour.sms.SMSMessage.Recipient;
import com.misterworldtour.sms.SMSNotificationAPI.DepartureNotice;
import com.misterworldtour.sms.SMSNotificationAPI.DeparturePlan;
import com.misterworldtour.sms.SMSNotificationAPI.SMSBatchResult;
import com.misterworldtour.sms.SMSNotificationAPI;
import com.misterworldtour.travel.SouvenirStockAPI.IssueItem;
import com.misterworldtour.travel.SouvenirStockAPI.SouvenirShortage;
import com.misterworldtour.travel.SouvenirStockAPI.ThemeSouvenir;
import com.misterworldtour.travel.TravelApplication.TravelerInput;
import com.misterworldtour.travel.TravelDataAccessAPI.ProductFilter;
import com.misterworldtour.travel.TravelManagementAPI.ApplicationResult;
import com.misterworldtour.travel.TravelManagementAPI.ApplicationReviewResult;
import com.misterworldtour.travel.TravelManagementAPI.ConfigurationResult;
import com.misterworldtour.travel.TravelManagementAPI.DefaultConfigurationResult;
import com.misterworldtour.travel.TravelManagementAPI.ImageChange;
import com.misterworldtour.travel.TravelManagementAPI.ProductInput;
import com.misterworldtour.travel.TravelPriceCalculator.PriceBreakdown;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;

/**
 * 여행 관리 업무 처리 (설계 5.1 TravelManager).
 * 신청은 상품을 잠근 트랜잭션에서 정원·재고·금액을 다시 확인해 저장하고, 출발이 확정되면 기념품을 차감한다.
 */
@Service
public class TravelManager implements TravelManagementAPI {

    /** 신청에서 만든 이력의 completedTravelId 앞부분 (고객·상품마다 1건). */
    public static final String HISTORY_ID_PREFIX = "product-";
    private static final Logger log = LoggerFactory.getLogger(TravelManager.class);

    private final TravelDataAccessAPI dataAccess;
    private final MemberManagementAPI members;
    private final SouvenirStockAPI souvenirs;
    private final SMSNotificationAPI sms;
    private final ProductImages images;
    private final Clock clock;

    TravelManager(TravelDataAccessAPI dataAccess, MemberManagementAPI members, SouvenirStockAPI souvenirs,
                  SMSNotificationAPI sms, ProductImages images, Clock clock) {
        this.dataAccess = dataAccess;
        this.members = members;
        this.souvenirs = souvenirs;
        this.sms = sms;
        this.images = images;
        this.clock = clock;
    }

    LocalDate today() {
        return LocalDate.now(clock);
    }

    private static boolean staffSession() {
        return LoginSession.current().filter(LoginSession::isStaffMember).isPresent();
    }

    /** 로그인한 고객의 회원 번호. 고객이 아니면 BookingException. */
    private static long customerId() {
        return LoginSession.current().filter(LoginSession::isCustomer).map(LoginSession::getMemberId)
                .orElseThrow(() -> new BookingException("고객으로 로그인해 주세요."));
    }

    @Override
    @Transactional
    public List<TravelHistory> viewPreviousTravelHistory() {
        return viewPreviousTravelHistory(customerId());
    }

    /** 이전 여행 이력: 완료일 최근 순, 완료일 없는 기록은 뒤로. 끝난 여행을 먼저 이력에 반영한다. */
    @Transactional
    public List<TravelHistory> viewPreviousTravelHistory(long memberId) {
        completeFinishedTrips(memberId, today());
        return dataAccess.findTravelHistories(memberId);
    }

    @Override
    @Transactional(readOnly = true)
    public List<TravelProduct> loadProducts(Theme theme, TourLevel level) {
        return dataAccess.findProducts(new ProductFilter(theme, level, staffSession() ? null : today()));
    }

    @Override
    @Transactional(readOnly = true)
    public TravelProduct selectProduct(long productId) {
        TravelProduct product = dataAccess.findProduct(productId).orElseThrow(ProductNotFoundException::new);
        if (!staffSession() && !product.getDepartureDate().isAfter(today())) {
            throw new BookingException("출발일이 지난 상품은 신청할 수 없습니다.");
        }
        return product;
    }

    /** 같은 상품에 저장된 실제 여행자 수 (취소하지 않은 모든 신청 합계). */
    @Transactional(readOnly = true)
    public int currentTravelerCount(long productId) {
        return dataAccess.countTravelers(productId);
    }

    @Transactional(readOnly = true)
    public int remainingSeats(TravelProduct product) {
        if (product.isHoneymoon()) {
            return TravelRules.HONEYMOON_PARTY_SIZE;
        }
        return Math.max(TravelRules.GROUP_CAPACITY - currentTravelerCount(product.getProductId()), 0);
    }

    /** 상품 테마에 연결된 기념품 이름. */
    public List<String> themeSouvenirNames(TravelProduct product) {
        return souvenirs.souvenirNamesForTheme(product.getTheme());
    }

    /** 신청 화면 첫 행(신청 고객 본인)에 보여 줄 계정 성명·연락처. */
    @Transactional(readOnly = true)
    public MemberManagementAPI.Contact applicantContact(long memberId) {
        return members.getContact(memberId).orElse(new MemberManagementAPI.Contact("", ""));
    }

    @Override
    @Transactional(readOnly = true)
    public DefaultConfigurationResult loadDefaultConfiguration(Theme theme, TourLevel level) {
        if (theme == null || level == null) {
            throw new IllegalArgumentException("유효하지 않은 테마 또는 등급입니다.");
        }
        List<String> names = souvenirs.souvenirNamesForTheme(theme);
        TravelConfiguration.BaseOptions allowed = TravelConfiguration.baseOptionChoices(theme, level);
        Map<String, List<List<String>>> choices = new LinkedHashMap<>();
        choices.put(TravelConfiguration.HOTEL,
                allowed.hotels().stream().map(v -> List.of(v.name(), v.getLabel())).toList());
        choices.put(TravelConfiguration.MEAL,
                allowed.meals().stream().map(v -> List.of(v.name(), v.getLabel())).toList());
        choices.put(TravelConfiguration.TRANSPORT,
                allowed.transports().stream().map(v -> List.of(v.name(), v.getLabel())).toList());
        return new DefaultConfigurationResult(theme.getDefaultService(),
                names.isEmpty() ? "(등록된 기념품 없음)" : String.join(", ", names),
                theme.getDefaultTransport().getLabel(), level.getDefaultHotel().getLabel(),
                level.getDefaultMeal().getLabel(), choices);
    }

    @Override
    @Transactional(readOnly = true)
    public ConfigurationResult configureTravelProduct(long productId, TravelConfiguration configuration,
                                                      Map<String, ?> optionChanges) {
        return configureTravelProduct(selectProduct(productId), configuration, optionChanges);
    }

    /** 바뀐 항목만 적용한 새 구성과 1인 가격. 실패 시 ConfigurationException (전달받은 구성은 그대로). */
    public ConfigurationResult configureTravelProduct(TravelProduct product, TravelConfiguration configuration,
                                                      Map<String, ?> optionChanges) {
        TravelConfiguration base = configuration == null ? product.getDefaultConfiguration() : configuration;
        TravelConfiguration changed = base.applyChanges(optionChanges);
        Map<String, String> errors = changed.validateConfiguration(product);
        if (!errors.isEmpty()) {
            throw new ConfigurationException(errors);
        }
        return new ConfigurationResult(changed, TravelPriceCalculator.calculateUnitPrice(product, changed));
    }

    public int calculateUnitPrice(TravelProduct product, TravelConfiguration configuration) {
        return TravelPriceCalculator.calculateUnitPrice(product, configuration);
    }

    /**
     * 이 신청에 필요한 기념품 수 (품목마다). 이 신청으로 새로 확정되는 상품은 그때까지의 신청 여행자까지 포함하고,
     * 그 외는 이 신청의 여행자 수.
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
    private void checkCapacityAndSouvenirs(TravelProduct product, int travelerCount, int currentCount) {
        String problem = product.validateApplicationCapacity(currentCount, travelerCount);
        if (problem != null) {
            throw new BookingException(problem, Map.of("travelers", problem));
        }
        List<ThemeSouvenir> themeSouvenirs = souvenirs.themeSouvenirs(product.getTheme());
        if (themeSouvenirs.isEmpty()) {
            throw new BookingException("이 테마의 기념품 정보가 없어 신청할 수 없습니다. 고객센터에 문의해 주세요.");
        }
        int needed = souvenirsNeeded(product, travelerCount, currentCount);
        for (ThemeSouvenir s : themeSouvenirs) {
            if (s.stockQuantity() < needed) {
                throw new BookingException(shortageMessage(travelerCount, s.souvenirName(), needed,
                        s.stockQuantity()));
            }
        }
    }

    private void checkConfiguration(TravelProduct product, TravelConfiguration configuration) {
        Map<String, String> errors = configuration.validateConfiguration(product);
        if (!errors.isEmpty()) {
            throw new BookingException("여행 구성을 다시 확인해 주세요.", errors);
        }
    }

    /** 1인 가격과 전체 신청 금액. 다녀온 여행을 이력에 반영한 뒤 단골 여부를 판단한다. */
    private PriceBreakdown price(TravelProduct product, long memberId, TravelConfiguration configuration,
                                 int travelerCount) {
        completeFinishedTrips(memberId, today());
        int unitPrice = TravelPriceCalculator.calculateUnitPrice(product, configuration);
        return TravelPriceCalculator.calculateApplicationPrice(unitPrice, travelerCount,
                dataAccess.findTravelHistories(memberId), today());
    }

    /** 첫 행을 신청 고객 본인(계정 성명·연락처)으로 바꾼 여행자 입력. */
    private List<TravelerInput> withApplicant(long memberId, List<TravelerInput> rows) {
        MemberManagementAPI.Contact me = members.getContact(memberId)
                .orElseThrow(() -> new BookingException("회원 정보를 찾을 수 없습니다. 다시 로그인해 주세요."));
        List<TravelerInput> out = new ArrayList<>();
        out.add(new TravelerInput(me.name(), me.contact(), true));
        for (int i = 1; i < rows.size(); i++) {
            out.add(rows.get(i));
        }
        return out;
    }

    @Override
    @Transactional
    public ApplicationReviewResult reviewApplication(long productId, TravelConfiguration configuration,
                                                     List<TravelerInput> travelers) {
        return reviewApplication(customerId(), productId, configuration, travelers);
    }

    @Transactional
    public ApplicationReviewResult reviewApplication(long memberId, long productId, TravelConfiguration configuration,
                                                     List<TravelerInput> travelers) {
        TravelProduct product = selectProduct(productId);
        checkConfiguration(product, configuration);
        List<TravelerInput> cleaned = TravelApplication.validateTravelers(product.getTheme(),
                withApplicant(memberId, travelers));
        checkCapacityAndSouvenirs(product, cleaned.size(), currentTravelerCount(productId));
        PriceBreakdown price = price(product, memberId, configuration, cleaned.size());
        return new ApplicationReviewResult(UUID.randomUUID().toString().replace("-", ""), productId, memberId,
                configuration, cleaned, price);
    }

    @Override
    public ApplicationResult submitTravelApplication(long productId, TravelConfiguration configuration,
                                                     List<TravelerInput> travelers, String requestKey,
                                                     PriceBreakdown reviewedPrice) {
        return submitTravelApplication(customerId(), productId, configuration, travelers, requestKey, reviewedPrice);
    }

    /** 검토 결과 그대로 신청한다 (검토한 고객이어야 함). */
    public ApplicationResult submitTravelApplication(long memberId, ApplicationReviewResult review) {
        if (review.memberId() != memberId) {
            throw new BookingException("다른 고객의 신청 내용입니다. 다시 검토해 주세요.");
        }
        return submitTravelApplication(memberId, review.productId(), review.configuration(), review.travelers(),
                review.requestKey(), review.price());
    }

    /** 저장 트랜잭션의 결과: 화면에 돌려줄 결과와, 저장 뒤 보낼 문자 묶음. */
    private record Saved(ApplicationResult result, DeparturePlan plan, List<Recipient> recipients) {
    }

    /**
     * 신청 등록. 상품을 잠근 트랜잭션에서 저장·출발 판정·기념품 차감·문자 기록 준비를 하고, 끝난 뒤 문자를 보낸다.
     * 문자 실패는 저장된 신청·출발 상태를 되돌리지 않는다 (기록은 Pending 으로 남아 직원이 재발송).
     */
    public ApplicationResult submitTravelApplication(long memberId, long productId, TravelConfiguration configuration,
                                                     List<TravelerInput> travelers, String requestKey,
                                                     PriceBreakdown reviewedPrice) {
        Saved saved = dataAccess.runTravelTransaction(productId, locked -> save(memberId,
                locked.orElseThrow(ProductNotFoundException::new), configuration, travelers, requestKey, reviewedPrice));
        ApplicationResult result = saved.result();
        if (saved.plan() == null) {
            return result;
        }
        SMSBatchResult smsResult;
        try {
            smsResult = sms.sendDepartureConfirmation(saved.plan());
        } catch (RuntimeException e) { // 문자 모듈 자체 오류도 신청 성공을 바꾸지 않음
            log.error("문자 요청 실패 application={}", result.applicationId(), e);
            smsResult = SMSBatchResult.allInvalid(saved.recipients());
        }
        return result.withSms(smsResult);
    }

    /** 같은 요청 키로 저장된 신청이 이번 제출과 고객·상품·구성·여행자까지 같은지. */
    private static boolean sameRequest(TravelApplication existing, long memberId, TravelProduct product,
                                       TravelConfiguration configuration, List<TravelerInput> travelers) {
        if (existing.getMemberId() != memberId || !existing.getProductId().equals(product.getProductId())) {
            return false;
        }
        if (!existing.getConfiguration().equals(configuration)) {
            return false;
        }
        List<TravelerInput> savedTravelers = existing.getTravelers().stream()
                .map(t -> new TravelerInput(t.getName(), t.getContact(), t.isApplicant())).toList();
        return savedTravelers.equals(travelers);
    }

    private Saved save(long memberId, TravelProduct product, TravelConfiguration configuration,
                       List<TravelerInput> travelers, String requestKey, PriceBreakdown reviewedPrice) {
        Optional<TravelApplication> existing = dataAccess.findApplicationByRequestKey(requestKey);
        if (existing.isPresent()) { // 같은 확인 제출의 재시도 → 기존 결과
            TravelApplication e = existing.get();
            if (!sameRequest(e, memberId, product, configuration, travelers)) {
                throw new BookingException("이미 다른 내용으로 처리된 신청 요청입니다.");
            }
            return new Saved(new ApplicationResult(e.getApplicationId(), product.isHoneymoon(),
                    e.currentDepartureStatus(), false, true, 0, List.of(), null), null, List.of());
        }

        if (!product.getDepartureDate().isAfter(today())) {
            throw new BookingException("출발일이 지난 상품은 신청할 수 없습니다.");
        }
        checkConfiguration(product, configuration);
        List<TravelerInput> cleaned = TravelApplication.validateTravelers(product.getTheme(),
                withApplicant(memberId, travelers));
        int count = cleaned.size();
        checkCapacityAndSouvenirs(product, count, currentTravelerCount(product.getProductId()));
        PriceBreakdown price = price(product, memberId, configuration, count);
        if (!price.equals(reviewedPrice)) {
            throw new PriceChangedException("검토 이후 금액이 바뀌었습니다 (%,d원 → %,d원). 다시 확인해 주세요."
                    .formatted(reviewedPrice == null ? 0 : reviewedPrice.finalAmount(), price.finalAmount()));
        }

        TravelApplication app = new TravelApplication(memberId, product, configuration, price, requestKey);
        if (product.isHoneymoon()) {
            app.confirmHoneymoonDeparture();
        }
        app = dataAccess.saveApplication(app, cleaned.stream()
                .map(t -> new Traveler(t.name(), t.contact(), t.applicant())).toList());

        boolean newlyConfirmed = false;
        List<Long> recipients;
        List<IssueItem> toIssue;
        if (product.isHoneymoon()) {
            recipients = List.of(memberId);
            toIssue = List.of(new IssueItem(app.getApplicationId(), count));
        } else {
            boolean wasConfirmed = product.isConfirmed();
            DepartureStatus status = product.updateDepartureStatus(dataAccess.countTravelers(product.getProductId()));
            dataAccess.saveDepartureStatus(product.getProductId(), status);
            newlyConfirmed = !wasConfirmed && product.isConfirmed();
            if (newlyConfirmed) { // 새로 확정: 이 상품의 모든 신청 고객 (취소 제외, 중복 제거)
                List<TravelApplication> all = dataAccess.findApplications(product.getProductId());
                recipients = List.copyOf(new TreeSet<>(all.stream().map(TravelApplication::getMemberId).toList()));
                toIssue = all.stream().map(a -> new IssueItem(a.getApplicationId(), a.getTravelerCount())).toList();
            } else if (wasConfirmed) { // 이미 확정된 상품의 추가 신청: 새 신청 고객만
                recipients = List.of(memberId);
                toIssue = List.of(new IssueItem(app.getApplicationId(), count));
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
        // 문자 대상과 발송 기록(Pending)을 신청과 같은 트랜잭션에서 만든다 (보내기 전 중단 대비).
        // 실제 발송은 저장이 끝난 뒤에 한다. 기록 준비에 실패해도 신청은 저장한다.
        List<Recipient> smsRecipients = new ArrayList<>();
        DeparturePlan plan = null;
        if (!recipients.isEmpty()) {
            for (Long mid : new TreeSet<>(recipients)) {
                members.getContact(mid).ifPresent(info ->
                        smsRecipients.add(new Recipient(mid, info.name(), info.contact())));
            }
            DepartureNotice notice = new DepartureNotice(product.getProductId(), product.getProductName(),
                    product.getDepartureDate());
            try {
                plan = sms.prepareDepartureConfirmation(smsRecipients, notice);
            } catch (RuntimeException e) {
                log.error("문자 발송 기록 준비 실패 application={}", app.getApplicationId(), e);
                plan = sms.planWithoutLogs(smsRecipients, notice);
            }
        }
        return new Saved(new ApplicationResult(app.getApplicationId(), product.isHoneymoon(),
                app.currentDepartureStatus(), newlyConfirmed, false, issued, recipients, null), plan, smsRecipients);
    }

    /** 취소할 수 없는 이유. 취소할 수 있으면 null. */
    public String cancelBlockReason(TravelApplication app) {
        if (app.isCancelled()) {
            return "이미 취소한 신청입니다.";
        }
        if (app.currentDepartureStatus() == DepartureStatus.CONFIRMED) {
            return "출발이 확정된 신청은 취소할 수 없습니다.";
        }
        if (!app.getProduct().getDepartureDate().isAfter(today())) {
            return "출발일이 지난 신청은 취소할 수 없습니다.";
        }
        return null;
    }

    /**
     * 출발 확정 전 신청을 취소한다. 확정 판정과 겹치지 않게 상품을 잠근 뒤 다시 검사한다.
     */
    public void cancelApplication(long memberId, long applicationId) {
        long productId = dataAccess.findApplicationProductId(memberId, applicationId)
                .orElseThrow(() -> new BookingException("신청을 찾을 수 없습니다."));
        dataAccess.runTravelTransaction(productId, locked -> {
            TravelApplication fresh = dataAccess.findApplication(applicationId).orElseThrow();
            String reason = cancelBlockReason(fresh);
            if (reason != null) {
                throw new BookingException(reason);
            }
            fresh.cancel();
            return null;
        });
        log.info("신청 취소 application={} member={}", applicationId, memberId);
    }

    /** 같은 요청 키로 이미 저장된 이 고객의 신청 (재제출 안내용). */
    @Transactional(readOnly = true)
    public Optional<Long> findSubmittedApplication(long memberId, String requestKey) {
        if (requestKey == null || requestKey.isBlank()) {
            return Optional.empty();
        }
        return dataAccess.findMemberApplicationByRequestKey(memberId, requestKey)
                .map(TravelApplication::getApplicationId);
    }

    /** 고객 본인의 신청 1건 (상품·여행자 함께). 다른 고객의 신청이면 empty. */
    @Transactional(readOnly = true)
    public Optional<TravelApplication> getMyApplication(long memberId, long applicationId) {
        return dataAccess.findMemberApplication(memberId, applicationId);
    }

    /** 취소하지 않은 신청 수. */
    @Transactional(readOnly = true)
    public long activeApplicationCount(long memberId) {
        return dataAccess.countMemberApplications(memberId);
    }

    @Transactional(readOnly = true)
    public List<MyApplication> myApplications(long memberId) {
        Map<Long, Integer> counts = dataAccess.countTravelersByApplication(memberId);
        return dataAccess.findMemberApplications(memberId).stream()
                .map(a -> new MyApplication(a, counts.getOrDefault(a.getApplicationId(), 0))).toList();
    }

    @Transactional(readOnly = true)
    public DashboardCounts staffDashboardCounts() {
        return new DashboardCounts(dataAccess.countProducts(), dataAccess.countApplications(),
                dataAccess.countProductsByDepartureStatus(DepartureStatus.CONFIRMED));
    }

    @Transactional(readOnly = true)
    public List<RecentProduct> recentProducts(int limit) {
        return dataAccess.findRecentProducts(limit).stream()
                .map(r -> new RecentProduct(r.product(), r.applicationCount())).toList();
    }

    @Transactional(readOnly = true)
    public boolean hasApplications(long productId) {
        return dataAccess.hasApplications(productId);
    }

    @Transactional(readOnly = true)
    public Optional<TravelProduct> findProductByName(String name) {
        return dataAccess.findProductByName(name);
    }

    @Transactional(readOnly = true)
    public Map<Long, ApplicationSummary> applicationSummaries(Collection<Long> ids) {
        Map<Long, ApplicationSummary> map = new HashMap<>();
        for (TravelApplication a : dataAccess.findApplicationsByIds(ids)) {
            map.put(a.getApplicationId(), new ApplicationSummary(a.getApplicationId(), a.getMemberId(),
                    a.getProduct().getProductName(), a.getProduct().getDepartureDate()));
        }
        return map;
    }

    /** 입력값을 상품에 넣고 검증한다 (ValidateProduct, ValidateConfiguration). */
    private void fill(TravelProduct product, ProductInput in) {
        product.update(in.productName(), in.destination(), in.description(), in.theme(), in.level(), in.basePrice(),
                in.travelPeriod(), in.departureDate());
        Map<String, String> errors = new LinkedHashMap<>(product.validateProduct(today()));
        if (in.theme() != null && in.level() != null) {
            TravelConfiguration base = new TravelConfiguration(in.theme(), in.level());
            TravelConfiguration chosen = base.applyChanges(Map.of(
                    TravelConfiguration.HOTEL, in.hotel() != null ? in.hotel().name() : base.hotelLevel(),
                    TravelConfiguration.MEAL, in.meal() != null ? in.meal().name() : base.meal(),
                    TravelConfiguration.TRANSPORT, in.transport() != null ? in.transport().name() : base.transport()));
            Map<String, String> configErrors = chosen.validateConfiguration(in.theme(), in.level());
            configErrors.forEach(errors::putIfAbsent);
            if (configErrors.isEmpty()) {
                product.setDefaultConfiguration(chosen);
            }
        }
        if (!errors.isEmpty()) {
            throw new ProductValidationException(errors);
        }
    }

    /** 사진 변경. 실패 시 ProductImages.InvalidImageException. */
    private void applyImage(TravelProduct product, ImageChange change) {
        if (change == null || (!change.delete() && change.upload() == null)) {
            return;
        }
        product.setImage(change.delete() ? null : images.store(change.upload()));
    }

    /** UC6 상품 등록. 기본 구성·출발 상태는 시스템이 결정한다. */
    @Override
    @Transactional
    public TravelProduct registerTourProduct(ProductInput input, ImageChange image) {
        TravelProduct product = new TravelProduct(input.productName(), input.destination(), input.description(),
                input.theme(), input.level(), input.basePrice(), input.travelPeriod(), input.departureDate());
        fill(product, input);
        applyImage(product, image);
        return dataAccess.saveProduct(product);
    }

    /** 데모 데이터용 등록 (검사는 등급 제한만). */
    @Transactional
    public TravelProduct registerTourProduct(TravelProduct product) {
        String error = product.validateProduct(null).get("tour_level");
        if (error != null) {
            throw new IllegalArgumentException(error);
        }
        return dataAccess.saveProduct(product);
    }

    /** since 이후 같은 입력(이름·테마·출발일)으로 저장된 상품. 등록 중 오류 뒤 저장 여부 확인용. */
    @Transactional(readOnly = true)
    public Optional<TravelProduct> findRegisteredSince(ProductInput input, OffsetDateTime since) {
        return dataAccess.findProductRegisteredSince(input.productName(), input.theme(), input.departureDate(), since);
    }

    /** UC7 상품 수정. 같은 상품을 잠근 상태에서 신청 여부를 확인한 뒤 변경한다. */
    @Override
    public TravelProduct modifyTourProduct(long productId, ProductInput input, ImageChange image) {
        return dataAccess.runTravelTransaction(productId, locked -> {
            TravelProduct product = locked.orElseThrow(ProductNotFoundException::new);
            if (dataAccess.hasApplications(productId)) {
                throw new ProductHasApplicationsException();
            }
            fill(product, input);
            applyImage(product, image);
            return dataAccess.updateProduct(product);
        });
    }

    /** UC7 상품 삭제. 삭제한 상품 이름을 돌려준다. */
    @Override
    public String deleteTourProduct(long productId) {
        return dataAccess.runTravelTransaction(productId, locked -> {
            TravelProduct product = locked.orElseThrow(ProductNotFoundException::new);
            if (dataAccess.hasApplications(productId)) {
                throw new ProductHasApplicationsException();
            }
            String name = product.getProductName();
            dataAccess.deleteProduct(productId);
            return name;
        });
    }

    /** 상품 대표 사진 경로를 바꾼다 (데모 데이터). */
    @Transactional
    public void setProductImage(long productId, String imagePath) {
        dataAccess.findProduct(productId).ifPresent(p -> p.setImage(imagePath));
    }

    @Transactional
    public int completeFinishedTrips(Long memberId) {
        return completeFinishedTrips(memberId, today());
    }

    /**
     * 출발 확정된 신청 중 여행 마지막 날이 지난 것을 완료 이력으로 만들고, 만든 이력 수를 돌려준다.
     * 고객·상품마다 1건(가격은 신청 금액 합계)이라 여러 번 호출해도 중복되지 않는다.
     */
    @Transactional
    public int completeFinishedTrips(Long memberId, LocalDate today) {
        List<TravelApplication> apps = dataAccess.findConfirmedApplicationsDepartedBefore(today, memberId);
        Set<String> done = dataAccess.findCompletedTravelKeys(HISTORY_ID_PREFIX, memberId);
        Map<String, TravelHistory> trips = new LinkedHashMap<>(); // "회원:식별값" → 다녀온 여행 1건
        Map<String, Integer> prices = new HashMap<>();
        for (TravelApplication a : apps) {
            TravelProduct p = a.getProduct();
            String travelId = HISTORY_ID_PREFIX + p.getProductId();
            String key = a.getMemberId() + ":" + travelId;
            LocalDate end = p.getTripEndDate();
            if (done.contains(key) || !end.isBefore(today)) {
                continue;
            }
            trips.computeIfAbsent(key, k -> new TravelHistory(a.getMemberId(), travelId, p.getProductName(),
                    p.getTourLevel(), p.getTravelPeriod(), 0, true, end));
            prices.merge(key, a.getFinalAmount(), Integer::sum);
        }
        int created = 0;
        for (Map.Entry<String, TravelHistory> e : trips.entrySet()) {
            TravelHistory h = e.getValue();
            dataAccess.saveTravelHistory(new TravelHistory(h.getMemberId(), h.getCompletedTravelId(),
                    h.getProductName(), h.getTourLevel(), h.getTravelPeriod(), prices.get(e.getKey()), true,
                    h.getCompletedAt()));
            created++;
        }
        if (created > 0) {
            log.info("완료 여행 이력 {}건 생성 member={}", created, memberId != null ? memberId : "전체");
        }
        return created;
    }

    /** 데모 데이터용 완료 이력. 같은 (회원, 식별값)이 있으면 만들지 않는다. 만들었으면 true. */
    @Transactional
    public boolean seedHistory(long memberId, String completedTravelId, String productName, TourLevel level,
                               int travelPeriod, int price, LocalDate completedAt) {
        if (dataAccess.existsTravelHistory(memberId, completedTravelId)) {
            return false;
        }
        dataAccess.saveTravelHistory(new TravelHistory(memberId, completedTravelId, productName, level, travelPeriod,
                price, completedAt != null, completedAt));
        return true;
    }
}
