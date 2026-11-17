package com.misterworldtour.travel;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;

/**
 * 여행 상품·신청·이력 데이터의 저장과 조회. Travel Management 가 필요로 하는 인터페이스이며,
 * Data Access 서브시스템의 {@code DataAccessManager} 가 구현한다 (의존성 역전: 인터페이스는 쓰는 쪽이 가진다).
 *
 * <p>같은 상품을 잠그고 여러 조회·저장을 묶을 때는 {@link #runTravelTransaction} 을 쓴다.
 */
public interface TravelDataAccessAPI {

    /** 상품 목록 조회 조건. 값이 null 이면 조건 없음. departureAfter 가 있으면 그날 이후 출발 상품만. */
    record ProductFilter(Theme theme, TourLevel level, LocalDate departureAfter) {
    }

    /** 상품과 그 상품의 신청 수. */
    record ProductApplicationCount(TravelProduct product, long applicationCount) {
    }

    List<TravelProduct> findProducts(ProductFilter filters);

    Optional<TravelProduct> findProduct(long productId);

    TravelProduct saveProduct(TravelProduct product);

    TravelProduct updateProduct(TravelProduct product);

    void deleteProduct(long productId);

    boolean hasApplications(long productId);

    void saveDepartureStatus(long productId, DepartureStatus departureStatus);

    /**
     * 같은 상품을 잠근 한 트랜잭션 안에서 work 를 실행한다. work 는 잠근 상품(없으면 empty)을 받는다.
     * work 가 예외를 내면 이 작업의 변경을 모두 취소한다.
     */
    <T> T runTravelTransaction(long productId, Function<Optional<TravelProduct>, T> work);

    Optional<TravelProduct> findProductByName(String productName);

    Optional<TravelProduct> findProductRegisteredSince(String productName, Theme theme, LocalDate departureDate,
                                                      OffsetDateTime since);

    long countProducts();

    long countProductsByDepartureStatus(DepartureStatus status);

    /** 최근 등록 상품과 신청 수 (취소 포함). */
    List<ProductApplicationCount> findRecentProducts(int limit);


    /** 신청과 실제 여행자 목록을 함께 저장한다. */
    TravelApplication saveApplication(TravelApplication application, List<Traveler> travelers);

    Optional<TravelApplication> findApplicationByRequestKey(String requestKey);

    /** 상품의 취소하지 않은 신청 (신청 순). */
    List<TravelApplication> findApplications(long productId);

    /** 상품에 저장된 실제 여행자 수 (취소하지 않은 신청 합계). */
    int countTravelers(long productId);

    Optional<TravelApplication> findApplication(long applicationId);

    Optional<TravelApplication> findMemberApplication(long memberId, long applicationId);

    Optional<TravelApplication> findMemberApplicationByRequestKey(long memberId, String requestKey);

    Optional<Long> findApplicationProductId(long memberId, long applicationId);

    /** 고객의 신청 (최근 순, 상품 함께). */
    List<TravelApplication> findMemberApplications(long memberId);

    /** 고객의 신청별 여행자 수 (신청 번호 → 수). */
    Map<Long, Integer> countTravelersByApplication(long memberId);

    long countMemberApplications(long memberId);

    long countApplications();

    List<TravelApplication> findApplicationsByIds(Collection<Long> applicationIds);

    /** 출발이 확정되고 취소하지 않은 신청 중 출발일이 today 이전인 것. memberId 가 null 이면 전체. */
    List<TravelApplication> findConfirmedApplicationsDepartedBefore(LocalDate today, Long memberId);


    /** 고객의 이전 여행 이력 (완료일 최근 순, 완료일 없는 기록은 뒤로). */
    List<TravelHistory> findTravelHistories(long memberId);

    TravelHistory saveTravelHistory(TravelHistory history);

    boolean existsTravelHistory(long memberId, String completedTravelId);

    /** 식별값이 prefix 로 시작하는 이력의 "회원번호:식별값" 목록. memberId 가 null 이면 전체. */
    Set<String> findCompletedTravelKeys(String prefix, Long memberId);
}
