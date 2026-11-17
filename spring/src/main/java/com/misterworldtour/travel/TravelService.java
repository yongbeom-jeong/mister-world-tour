package com.misterworldtour.travel;

import com.misterworldtour.souvenir.SouvenirService;
import com.misterworldtour.travel.ConfigurationRules.BaseOptions;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Travel Management 서비스: 직원 상품 관리·통계·완료 이력 (설계 05장 TravelManager, Django 판 travel/services.py).
 * 컨트롤러는 권한 확인과 입력 형식 검사만 하고 업무 처리는 이 서비스를 호출한다.
 */
@Service
public class TravelService {

    /** 신청에서 만든 이력의 completedTravelId 앞부분 (고객·상품마다 1건). */
    public static final String HISTORY_ID_PREFIX = "product-";
    private static final Logger log = LoggerFactory.getLogger(TravelService.class);

    /** 직원 홈 통계. */
    public record DashboardCounts(long products, long applications, long confirmed) {
    }

    /** 직원 홈: 최근 등록 상품과 신청 수. */
    public record RecentProduct(TravelProduct product, long applicationCount) {
    }

    /** 다른 패키지 화면(기념품 차감 내역 등)에 보여 줄 신청 요약. */
    public record ApplicationSummary(long id, long memberId, String productName, LocalDate departureDate) {
    }

    /** 직원이 입력하는 상품 정보. 기본 구성(hotel·meal·transport)은 비우면(null) 테마·등급 기본값. */
    public record ProductInput(String name, String destination, String description, Theme theme, TourLevel level,
                               int basePrice, int travelDays, LocalDate departureDate, HotelLevel hotel,
                               MealOption meal, TransportOption transport) {
    }

    /** 사진 변경 요청: 그대로 / 삭제(테마 기본 사진 사용) / 새 사진. */
    public record ImageChange(boolean delete, byte[] upload) {
        public static final ImageChange KEEP = new ImageChange(false, null);
        public static final ImageChange DELETE = new ImageChange(true, null);

        public static ImageChange replace(byte[] bytes) {
            return new ImageChange(false, bytes);
        }
    }

    /** 테마·등급의 기본 구성과 직원이 고를 수 있는 값 (등록·수정 화면용). choices 는 [값, 이름] 목록(첫 항목이 기본값). */
    public record DefaultConfiguration(String themeService, String souvenir, String transport, String hotelLevel,
                                       String meal, Map<String, List<List<String>>> choices) {
    }

    public static class ProductNotFoundException extends RuntimeException {
    }

    /** 신청이 한 건이라도 있는 상품은 수정·삭제할 수 없다. */
    public static class ProductHasApplicationsException extends RuntimeException {
    }

    /** 상품 입력 오류. errors 는 항목별 안내. */
    public static class ProductValidationException extends RuntimeException {
        private final transient Map<String, String> errors;

        public ProductValidationException(Map<String, String> errors) {
            super(errors.toString());
            this.errors = errors;
        }

        public Map<String, String> getErrors() {
            return errors;
        }
    }

    private final TravelProductRepository products;
    private final TravelApplicationRepository applications;
    private final TravelHistoryRepository histories;
    private final SouvenirService souvenirs;
    private final ProductImages images;
    private final Clock clock;

    TravelService(TravelProductRepository products, TravelApplicationRepository applications,
                  TravelHistoryRepository histories, SouvenirService souvenirs, ProductImages images, Clock clock) {
        this.products = products;
        this.applications = applications;
        this.histories = histories;
        this.souvenirs = souvenirs;
        this.images = images;
        this.clock = clock;
    }

    LocalDate today() {
        return LocalDate.now(clock);
    }

    // ---------------------------------------------------------------- 조회

    /** 직원 홈 통계: 상품 수, 취소 제외 신청 수, 출발 확정 상품 수. */
    @Transactional(readOnly = true)
    public DashboardCounts staffDashboardCounts() {
        return new DashboardCounts(products.count(), applications.countByCancelledAtIsNull(),
                products.countByDepartureStatus(DepartureStatus.CONFIRMED));
    }

    @Transactional(readOnly = true)
    public List<RecentProduct> recentProducts(int limit) {
        return products.recentWithApplicationCount(PageRequest.of(0, limit)).stream()
                .map(row -> new RecentProduct((TravelProduct) row[0], (Long) row[1]))
                .toList();
    }

    /** 상품 목록. 조건을 생략(null)하면 전체 (직원 관리 화면). */
    @Transactional(readOnly = true)
    public List<TravelProduct> loadProducts(Theme theme, TourLevel level) {
        return products.search(null, theme, level);
    }

    @Transactional(readOnly = true)
    public TravelProduct selectProduct(long productId) {
        return products.findById(productId).orElseThrow(ProductNotFoundException::new);
    }

    @Transactional(readOnly = true)
    public boolean hasApplications(long productId) {
        return applications.existsByProductId(productId);
    }

    @Transactional(readOnly = true)
    public Optional<TravelProduct> findByName(String name) {
        return products.findFirstByName(name);
    }

    @Transactional(readOnly = true)
    public Map<Long, ApplicationSummary> applicationSummaries(Collection<Long> ids) {
        Map<Long, ApplicationSummary> map = new HashMap<>();
        if (ids.isEmpty()) {
            return map;
        }
        for (TravelApplication a : applications.findAllWithProductByIdIn(ids)) {
            map.put(a.getId(), new ApplicationSummary(a.getId(), a.getMemberId(), a.getProduct().getName(),
                    a.getProduct().getDepartureDate()));
        }
        return map;
    }

    /** 테마·등급의 기본 구성과 직원이 고를 수 있는 값. */
    @Transactional(readOnly = true)
    public DefaultConfiguration loadDefaultConfiguration(Theme theme, TourLevel level) {
        if (theme == null || level == null) {
            throw new IllegalArgumentException("유효하지 않은 테마 또는 등급입니다.");
        }
        List<String> names = souvenirs.souvenirNamesForTheme(theme);
        BaseOptions allowed = ConfigurationRules.baseOptionChoices(theme, level);
        Map<String, List<List<String>>> choices = new LinkedHashMap<>();
        choices.put(TravelConfig.HOTEL, allowed.hotels().stream().map(v -> List.of(v.name(), v.getLabel())).toList());
        choices.put(TravelConfig.MEAL, allowed.meals().stream().map(v -> List.of(v.name(), v.getLabel())).toList());
        choices.put(TravelConfig.TRANSPORT, allowed.transports().stream().map(v -> List.of(v.name(), v.getLabel())).toList());
        return new DefaultConfiguration(theme.getDefaultService(),
                names.isEmpty() ? "(등록된 기념품 없음)" : String.join(", ", names),
                theme.getDefaultTransport().getLabel(), level.getDefaultHotel().getLabel(),
                level.getDefaultMeal().getLabel(), choices);
    }

    // ---------------------------------------------------------------- 직원 상품 관리 (UC6, UC7)

    /** 입력값을 상품에 넣는다. 기본 구성·출발 상태는 시스템이 다시 정하고, 직원이 고른 기본 구성을 반영한다. */
    private static void fill(TravelProduct product, ProductInput in) {
        product.update(in.name(), in.destination(), in.description(), in.theme(), in.level(), in.basePrice(),
                in.travelDays(), in.departureDate());
        Map<String, String> errors = new LinkedHashMap<>(ConfigurationRules.baseConfigurationErrors(in.theme(),
                in.level(), in.hotel(), in.meal(), in.transport()));
        String levelError = product.levelError();
        if (levelError != null) {
            errors.put("tour_level", levelError);
        }
        if (!errors.isEmpty()) {
            throw new ProductValidationException(errors);
        }
        product.setBaseConfiguration(in.hotel() != null ? in.hotel() : product.getHotelLevel(),
                in.meal() != null ? in.meal() : product.getMeal(),
                in.transport() != null ? in.transport() : product.getTransport());
    }

    /** 사진 변경. 실패 시 ProductImages.InvalidImageException. */
    private void applyImage(TravelProduct product, ImageChange change) {
        if (change == null || (!change.delete() && change.upload() == null)) {
            return;
        }
        product.setImage(change.delete() ? null : images.store(change.upload()));
    }

    /** UC6 상품 등록. 기본 구성·기념품·출발 상태는 시스템이 결정한다. */
    @Transactional
    public TravelProduct registerTourProduct(ProductInput input, ImageChange image) {
        TravelProduct product = new TravelProduct(input.name(), input.destination(), input.description(),
                input.theme(), input.level(), input.basePrice(), input.travelDays(), input.departureDate());
        fill(product, input);
        applyImage(product, image);
        return products.saveAndFlush(product);
    }

    /** 데모 데이터·내부용 등록 (입력 검사는 등급 제한만). */
    @Transactional
    public TravelProduct registerTourProduct(TravelProduct product) {
        String error = product.levelError();
        if (error != null) {
            throw new IllegalArgumentException(error);
        }
        return products.save(product);
    }

    /** since 이후 같은 입력(이름·테마·출발일)으로 저장된 상품. 등록 중 오류 뒤 저장 여부 확인용. */
    @Transactional(readOnly = true)
    public Optional<TravelProduct> findRegisteredSince(ProductInput input, OffsetDateTime since) {
        return products.findFirstByNameAndThemeAndDepartureDateAndCreatedAtGreaterThanEqualOrderByIdDesc(
                input.name(), input.theme(), input.departureDate(), since);
    }

    /** UC7 상품 수정. 같은 상품을 잠근 상태에서 신청 존재 여부를 확인한 뒤 변경한다. */
    @Transactional
    public TravelProduct modifyTourProduct(long productId, ProductInput input, ImageChange image) {
        TravelProduct product = products.findForUpdate(productId).orElseThrow(ProductNotFoundException::new);
        if (applications.existsByProductId(productId)) {
            throw new ProductHasApplicationsException();
        }
        fill(product, input);
        applyImage(product, image);
        return product;
    }

    /** UC7 상품 삭제. 삭제한 상품 이름을 돌려준다. */
    @Transactional
    public String deleteTourProduct(long productId) {
        TravelProduct product = products.findForUpdate(productId).orElseThrow(ProductNotFoundException::new);
        if (applications.existsByProductId(productId)) {
            throw new ProductHasApplicationsException();
        }
        String name = product.getName();
        products.delete(product);
        return name;
    }

    /** 상품 대표 사진 경로를 바꾼다 (데모 데이터). */
    @Transactional
    public void setProductImage(long productId, String imagePath) {
        products.findById(productId).ifPresent(p -> p.setImage(imagePath));
    }

    // ---------------------------------------------------------------- 완료 여행 이력 (UC3)

    /** UC3 이전 여행 이력: 완료일 최근 순, 완료일 없는 기록은 뒤로. 끝난 여행을 먼저 이력에 반영한다. */
    @Transactional
    public List<TravelHistory> viewPreviousTravelHistory(long memberId) {
        completeFinishedTrips(memberId, today());
        return histories.findForMember(memberId);
    }

    @Transactional
    public int completeFinishedTrips(Long memberId) {
        return completeFinishedTrips(memberId, today());
    }

    /**
     * 다녀온 여행을 완료 이력으로 만든다. 만든 이력 수를 돌려준다.
     *
     * <p>대상: 취소하지 않았고 출발이 확정된 신청(허니문은 신청 상태, 그 외는 상품 상태) 중 여행 마지막 날이 지난 것.
     * 출발이 확정되지 않은 채 출발일이 지난 상품은 여행이 진행되지 않았으므로 이력이 되지 않는다.
     * 완료 여행은 신청 건수가 아니라 실제 다녀온 여행으로 센다 (설계 05·07장): 같은 고객이 같은 상품에 신청을
     * 여러 번 했어도 이력은 1건이고, 가격은 그 신청 금액(할인 반영)의 합계다. 여러 번 호출해도 중복되지 않는다.
     */
    @Transactional
    public int completeFinishedTrips(Long memberId, LocalDate today) {
        List<TravelApplication> apps = applications.findConfirmedDepartedBefore(today, memberId);
        Set<String> done = new HashSet<>(histories.completedKeys(HISTORY_ID_PREFIX + "%", memberId));
        Map<String, TravelHistory> trips = new LinkedHashMap<>(); // "회원:식별값" → 다녀온 여행 1건
        Map<String, Integer> prices = new HashMap<>();
        for (TravelApplication a : apps) {
            TravelProduct p = a.getProduct();
            String travelId = HISTORY_ID_PREFIX + p.getId();
            String key = a.getMemberId() + ":" + travelId;
            LocalDate end = p.getTripEndDate();
            if (done.contains(key) || !end.isBefore(today)) {
                continue;
            }
            trips.computeIfAbsent(key, k -> new TravelHistory(a.getMemberId(), travelId, p.getName(),
                    p.getTourLevel(), p.getTravelDays(), 0, true, end));
            prices.merge(key, a.getFinalAmount(), Integer::sum); // 같은 여행의 신청 금액 합계
        }
        int created = 0;
        for (Map.Entry<String, TravelHistory> e : trips.entrySet()) {
            TravelHistory h = e.getValue();
            histories.save(new TravelHistory(h.getMemberId(), h.getCompletedTravelId(), h.getProductName(),
                    h.getTourLevel(), h.getTravelDays(), prices.get(e.getKey()), true, h.getCompletedAt()));
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
                               int travelDays, int price, LocalDate completedAt) {
        if (histories.existsByMemberIdAndCompletedTravelId(memberId, completedTravelId)) {
            return false;
        }
        histories.save(new TravelHistory(memberId, completedTravelId, productName, level, travelDays, price,
                completedAt != null, completedAt));
        return true;
    }
}
