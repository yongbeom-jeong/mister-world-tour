package com.misterworldtour.souvenir;

import com.misterworldtour.travel.Theme;

import org.springframework.data.domain.Page;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * 기념품·입고·차감 데이터의 저장과 조회. Souvenir Management 가 필요로 하는 인터페이스이며,
 * Data Access 서브시스템의 {@code DataAccessManager} 가 구현한다 (의존성 역전: 인터페이스는 쓰는 쪽이 가진다).
 */
public interface SouvenirDataAccessAPI {

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
}
