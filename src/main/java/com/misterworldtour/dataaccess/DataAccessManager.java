package com.misterworldtour.dataaccess;

import com.misterworldtour.dataaccess.DataAccessAPI.ProductApplicationCount;
import com.misterworldtour.dataaccess.DataAccessAPI.ProductFilter;
import com.misterworldtour.members.Member;
import com.misterworldtour.sms.SMSMessage;
import com.misterworldtour.souvenir.Souvenir;
import com.misterworldtour.souvenir.StockIssue;
import com.misterworldtour.souvenir.StockReceipt;
import com.misterworldtour.travel.DepartureStatus;
import com.misterworldtour.travel.Theme;
import com.misterworldtour.travel.TravelApplication;
import com.misterworldtour.travel.TravelHistory;
import com.misterworldtour.travel.TravelProduct;
import com.misterworldtour.travel.Traveler;

import org.springframework.data.domain.Page;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;

/**
 * {@link DataAccessAPI} 구현 (설계 7.1 DataAccessManager). 회원·여행·기념품 요청을 각각 MemberRepository·
 * TravelRepository·SouvenirRepository 에, 문자 기록 요청을 SMSMessageRepository 에 전달하고 결과를 돌려준다.
 * 현재 회원·선택 상품 같은 공유 상태는 보관하지 않는다.
 *
 * <p>각 메소드는 호출한 업무 메소드의 트랜잭션에 참여하고, 없으면 자체 트랜잭션으로 실행한다.
 */
@Service
@Transactional
public class DataAccessManager implements DataAccessAPI {

    private final MemberRepository memberRepository;
    private final TravelRepository travelRepository;
    private final SouvenirRepository souvenirRepository;
    private final SMSMessageRepository smsMessageRepository;

    DataAccessManager(MemberRepository memberRepository, TravelRepository travelRepository,
                      SouvenirRepository souvenirRepository, SMSMessageRepository smsMessageRepository) {
        this.memberRepository = memberRepository;
        this.travelRepository = travelRepository;
        this.souvenirRepository = souvenirRepository;
        this.smsMessageRepository = smsMessageRepository;
    }

    // ---------------------------------------------------------------- 회원

    @Override
    public boolean existsAccount(String account) {
        return memberRepository.existsAccount(account);
    }

    @Override
    public Member saveMember(Member member) {
        return memberRepository.saveMember(member);
    }

    @Override
    public Optional<Member> findMemberByAccount(String account) {
        return memberRepository.findMemberByAccount(account);
    }

    @Override
    public Optional<Member> findMember(long memberId) {
        return memberRepository.findMember(memberId);
    }

    @Override
    public List<Member> findMembers(Collection<Long> memberIds) {
        return memberRepository.findMembers(memberIds);
    }

    @Override
    public Optional<Member> findMemberJoinedSince(String account, OffsetDateTime since) {
        return memberRepository.findMemberJoinedSince(account, since);
    }

    // ---------------------------------------------------------------- 여행 상품

    @Override
    public List<TravelProduct> findProducts(ProductFilter filters) {
        return travelRepository.findProducts(filters);
    }

    @Override
    public Optional<TravelProduct> findProduct(long productId) {
        return travelRepository.findProduct(productId);
    }

    @Override
    public TravelProduct saveProduct(TravelProduct product) {
        return travelRepository.saveProduct(product);
    }

    @Override
    public TravelProduct updateProduct(TravelProduct product) {
        return travelRepository.updateProduct(product);
    }

    @Override
    public void deleteProduct(long productId) {
        travelRepository.deleteProduct(productId);
    }

    @Override
    public boolean hasApplications(long productId) {
        return travelRepository.hasApplications(productId);
    }

    @Override
    public void saveDepartureStatus(long productId, DepartureStatus departureStatus) {
        travelRepository.saveDepartureStatus(productId, departureStatus);
    }

    @Override
    public <T> T runTravelTransaction(long productId, Function<Optional<TravelProduct>, T> work) {
        return travelRepository.runTravelTransaction(productId, work);
    }

    @Override
    public Optional<TravelProduct> findProductByName(String productName) {
        return travelRepository.findProductByName(productName);
    }

    @Override
    public Optional<TravelProduct> findProductRegisteredSince(String productName, Theme theme, LocalDate departureDate,
                                                             OffsetDateTime since) {
        return travelRepository.findProductRegisteredSince(productName, theme, departureDate, since);
    }

    @Override
    public long countProducts() {
        return travelRepository.countProducts();
    }

    @Override
    public long countProductsByDepartureStatus(DepartureStatus status) {
        return travelRepository.countProductsByDepartureStatus(status);
    }

    @Override
    public List<ProductApplicationCount> findRecentProducts(int limit) {
        return travelRepository.findRecentProducts(limit);
    }

    // ---------------------------------------------------------------- 여행 신청

    @Override
    public TravelApplication saveApplication(TravelApplication application, List<Traveler> travelers) {
        return travelRepository.saveApplication(application, travelers);
    }

    @Override
    public Optional<TravelApplication> findApplicationByRequestKey(String requestKey) {
        return travelRepository.findApplicationByRequestKey(requestKey);
    }

    @Override
    public List<TravelApplication> findApplications(long productId) {
        return travelRepository.findApplications(productId);
    }

    @Override
    public int countTravelers(long productId) {
        return travelRepository.countTravelers(productId);
    }

    @Override
    public Optional<TravelApplication> findApplication(long applicationId) {
        return travelRepository.findApplication(applicationId);
    }

    @Override
    public Optional<TravelApplication> findMemberApplication(long memberId, long applicationId) {
        return travelRepository.findMemberApplication(memberId, applicationId);
    }

    @Override
    public Optional<TravelApplication> findMemberApplicationByRequestKey(long memberId, String requestKey) {
        return travelRepository.findMemberApplicationByRequestKey(memberId, requestKey);
    }

    @Override
    public Optional<Long> findApplicationProductId(long memberId, long applicationId) {
        return travelRepository.findApplicationProductId(memberId, applicationId);
    }

    @Override
    public List<TravelApplication> findMemberApplications(long memberId) {
        return travelRepository.findMemberApplications(memberId);
    }

    @Override
    public Map<Long, Integer> countTravelersByApplication(long memberId) {
        return travelRepository.countTravelersByApplication(memberId);
    }

    @Override
    public long countMemberApplications(long memberId) {
        return travelRepository.countMemberApplications(memberId);
    }

    @Override
    public long countApplications() {
        return travelRepository.countApplications();
    }

    @Override
    public List<TravelApplication> findApplicationsByIds(Collection<Long> applicationIds) {
        return travelRepository.findApplicationsByIds(applicationIds);
    }

    @Override
    public List<TravelApplication> findConfirmedApplicationsDepartedBefore(LocalDate today, Long memberId) {
        return travelRepository.findConfirmedApplicationsDepartedBefore(today, memberId);
    }

    // ---------------------------------------------------------------- 여행 이력

    @Override
    public List<TravelHistory> findTravelHistories(long memberId) {
        return travelRepository.findTravelHistories(memberId);
    }

    @Override
    public TravelHistory saveTravelHistory(TravelHistory history) {
        return travelRepository.saveTravelHistory(history);
    }

    @Override
    public boolean existsTravelHistory(long memberId, String completedTravelId) {
        return travelRepository.existsTravelHistory(memberId, completedTravelId);
    }

    @Override
    public Set<String> findCompletedTravelKeys(String prefix, Long memberId) {
        return travelRepository.findCompletedTravelKeys(prefix, memberId);
    }

    // ---------------------------------------------------------------- 기념품

    @Override
    public List<Souvenir> findSouvenirs() {
        return souvenirRepository.findSouvenirs();
    }

    @Override
    public List<Souvenir> findAllSouvenirs() {
        return souvenirRepository.findAllSouvenirs();
    }

    @Override
    public Optional<Souvenir> findSouvenir(long souvenirId) {
        return souvenirRepository.findSouvenir(souvenirId);
    }

    @Override
    public List<Souvenir> findSouvenirsByTheme(Theme theme) {
        return souvenirRepository.findSouvenirsByTheme(theme);
    }

    @Override
    public Optional<Souvenir> findSouvenirByName(String souvenirName) {
        return souvenirRepository.findSouvenirByName(souvenirName);
    }

    @Override
    public Optional<Souvenir> findSouvenirForUpdate(long souvenirId) {
        return souvenirRepository.findSouvenirForUpdate(souvenirId);
    }

    @Override
    public Souvenir saveSouvenir(Souvenir souvenir) {
        return souvenirRepository.saveSouvenir(souvenir);
    }

    @Override
    public void deleteSouvenir(Souvenir souvenir) {
        souvenirRepository.deleteSouvenir(souvenir);
    }

    @Override
    public int findStockQuantity(long souvenirId) {
        return souvenirRepository.findStockQuantity(souvenirId);
    }

    @Override
    public Optional<StockReceipt> saveStockReceipt(StockReceipt receipt) {
        return souvenirRepository.saveStockReceipt(receipt);
    }

    @Override
    public Optional<StockReceipt> findStockReceiptByRequestKey(String requestKey) {
        return souvenirRepository.findStockReceiptByRequestKey(requestKey);
    }

    @Override
    public long countStockReceipts(long souvenirId) {
        return souvenirRepository.countStockReceipts(souvenirId);
    }

    @Override
    public boolean decreaseStock(long souvenirId, int quantity) {
        return souvenirRepository.decreaseStock(souvenirId, quantity);
    }

    @Override
    public StockIssue saveStockIssue(StockIssue issue) {
        return souvenirRepository.saveStockIssue(issue);
    }

    @Override
    public Set<Long> findIssuedApplicationIds(long souvenirId, Collection<Long> applicationIds) {
        return souvenirRepository.findIssuedApplicationIds(souvenirId, applicationIds);
    }

    @Override
    public long countStockIssues(long souvenirId) {
        return souvenirRepository.countStockIssues(souvenirId);
    }

    @Override
    public Page<StockIssue> findStockIssues(Long souvenirId, int page, int size) {
        return souvenirRepository.findStockIssues(souvenirId, page, size);
    }

    @Override
    public List<StockIssue> findStockIssuesForApplication(long applicationId) {
        return souvenirRepository.findStockIssuesForApplication(applicationId);
    }

    // ---------------------------------------------------------------- 문자 발송 기록

    /** 실패해도 바깥 트랜잭션(신청 저장)을 되돌림 표시하지 않는다 (저장점까지만 취소). */
    @Override
    @Transactional(noRollbackFor = RuntimeException.class)
    public long insertSMSMessage(SMSMessage message) {
        return smsMessageRepository.insert(message);
    }

    @Override
    @Transactional(noRollbackFor = RuntimeException.class)
    public void saveSMSMessageResult(SMSMessage message, Long resentBy) {
        smsMessageRepository.saveResult(message, resentBy);
    }

    @Override
    public Optional<SMSMessage> findSMSMessage(long id) {
        return smsMessageRepository.find(id);
    }

    @Override
    public Optional<SMSMessage> findSMSMessageForUpdate(long id) {
        return smsMessageRepository.findForUpdate(id);
    }

    @Override
    public Page<SMSMessage> findSMSMessages(int page, int size) {
        return smsMessageRepository.findAll(page, size);
    }

    @Override
    public Page<SMSMessage> findProblemSMSMessages(OffsetDateTime stalePendingBefore, int page, int size) {
        return smsMessageRepository.findProblems(stalePendingBefore, page, size);
    }
}
