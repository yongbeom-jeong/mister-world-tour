package com.misterworldtour.dataaccess;

import com.misterworldtour.members.Member;
import com.misterworldtour.sms.SMSMessage;
import com.misterworldtour.souvenir.Souvenir;
import com.misterworldtour.souvenir.StockIssue;
import com.misterworldtour.souvenir.StockReceipt;
import com.misterworldtour.travel.DepartureStatus;
import com.misterworldtour.travel.Theme;
import com.misterworldtour.travel.TourLevel;
import com.misterworldtour.travel.TravelApplication;
import com.misterworldtour.travel.TravelHistory;
import com.misterworldtour.travel.TravelProduct;
import com.misterworldtour.travel.Traveler;

import org.springframework.data.domain.Page;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;

/**
 * 회원·여행·기념품·문자 기록 데이터의 저장과 조회 (설계 7.1 DataAccessAPI). {@link DataAccessManager} 가 구현한다.
 *
 * <p>업무 서브시스템(Member·Travel·Souvenir Management, SMS Notification)은 이 인터페이스로만 데이터에 접근한다.
 * 트랜잭션 경계는 호출하는 업무 메소드가 정하고, 같은 상품을 잠그고 여러 조회·저장을 묶을 때는
 * {@link #runTravelTransaction} 을 쓴다.
 */
public interface DataAccessAPI {

    /** 상품 목록 조회 조건. 값이 null 이면 조건 없음. departureAfter 가 있으면 그날 이후 출발 상품만. */
    record ProductFilter(Theme theme, TourLevel level, LocalDate departureAfter) {
    }

    /** 상품과 그 상품의 신청 수. */
    record ProductApplicationCount(TravelProduct product, long applicationCount) {
    }

    // ---------------------------------------------------------------- 회원 (MemberRepository)

    boolean existsAccount(String account);

    Member saveMember(Member member);

    Optional<Member> findMemberByAccount(String account);

    Optional<Member> findMember(long memberId);

    List<Member> findMembers(Collection<Long> memberIds);

    /** since 이후 이 아이디(대소문자 무시)로 가입된 회원. */
    Optional<Member> findMemberJoinedSince(String account, OffsetDateTime since);

    // ---------------------------------------------------------------- 여행 상품 (TravelRepository)

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

    // ---------------------------------------------------------------- 여행 신청 (TravelRepository)

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

    // ---------------------------------------------------------------- 여행 이력 (TravelRepository)

    /** 고객의 이전 여행 이력 (완료일 최근 순, 완료일 없는 기록은 뒤로). */
    List<TravelHistory> findTravelHistories(long memberId);

    TravelHistory saveTravelHistory(TravelHistory history);

    boolean existsTravelHistory(long memberId, String completedTravelId);

    /** 식별값이 prefix 로 시작하는 이력의 "회원번호:식별값" 목록. memberId 가 null 이면 전체. */
    Set<String> findCompletedTravelKeys(String prefix, Long memberId);

    // ---------------------------------------------------------------- 기념품·입고·차감 (SouvenirRepository)

    /** 사용 중인 품목 (테마·이름 순). */
    List<Souvenir> findSouvenirs();

    /** 숨긴 품목까지 포함한 전체 품목. */
    List<Souvenir> findAllSouvenirs();

    Optional<Souvenir> findSouvenir(long souvenirId);

    List<Souvenir> findSouvenirsByTheme(Theme theme);

    Optional<Souvenir> findSouvenirByName(String souvenirName);

    Optional<Souvenir> findSouvenirForUpdate(long souvenirId);

    Souvenir saveSouvenir(Souvenir souvenir);

    void deleteSouvenir(Souvenir souvenir);

    /** 지금 DB 의 재고 수량 (영속성 문맥의 옛 값이 아니라). */
    int findStockQuantity(long souvenirId);

    /**
     * 입고 내역 저장과 재고 증가를 함께 반영한다. 숨긴 품목이면 반영하지 않고 empty.
     * 저장한 입고 내역에는 증가 직후 재고가 기록된다.
     */
    Optional<StockReceipt> saveStockReceipt(StockReceipt receipt);

    Optional<StockReceipt> findStockReceiptByRequestKey(String requestKey);

    long countStockReceipts(long souvenirId);

    /** 재고가 quantity 이상일 때만 줄인다 (음수 방지). 줄였으면 true. */
    boolean decreaseStock(long souvenirId, int quantity);

    StockIssue saveStockIssue(StockIssue issue);

    Set<Long> findIssuedApplicationIds(long souvenirId, Collection<Long> applicationIds);

    long countStockIssues(long souvenirId);

    /** 차감 내역 (최근 순). souvenirId 가 null 이면 전체. page 는 0부터. */
    Page<StockIssue> findStockIssues(Long souvenirId, int page, int size);

    List<StockIssue> findStockIssuesForApplication(long applicationId);

    // ---------------------------------------------------------------- 문자 발송 기록 (SMSMessageRepository)

    /**
     * 발송 기록을 저장하고 번호를 붙인다. 바깥 트랜잭션(신청 저장) 안에서 불러도 실패가 바깥을 되돌리지 않게
     * 저장점을 두고 저장한다. 실패하면 예외.
     */
    long insertSMSMessage(SMSMessage message);

    /** 메모리에서 바뀐 발송 결과를 저장된 기록에 반영한다. resentBy 가 있으면 재발송 횟수도 센다. */
    void saveSMSMessageResult(SMSMessage message, Long resentBy);

    Optional<SMSMessage> findSMSMessage(long id);

    Optional<SMSMessage> findSMSMessageForUpdate(long id);

    Page<SMSMessage> findSMSMessages(int page, int size);

    /** 확인 필요: 실패·결과 불명·stalePendingBefore 보다 오래 멈춘 발송 중 (최근 순). */
    Page<SMSMessage> findProblemSMSMessages(OffsetDateTime stalePendingBefore, int page, int size);
}
