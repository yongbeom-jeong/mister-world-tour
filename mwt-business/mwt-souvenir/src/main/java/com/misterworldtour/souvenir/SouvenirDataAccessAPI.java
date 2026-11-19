package com.misterworldtour.souvenir;

import com.misterworldtour.travel.Theme;

import org.springframework.data.domain.Page;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/** 기념품·입고·차감 저장·조회. Data Access 의 {@code DataAccessManager} 가 구현한다. */
public interface SouvenirDataAccessAPI {

    List<Souvenir> findSouvenirs();

    /** 숨긴 품목 포함. */
    List<Souvenir> findAllSouvenirs();

    Optional<Souvenir> findSouvenir(long souvenirId);

    List<Souvenir> findSouvenirsByTheme(Theme theme);

    Optional<Souvenir> findSouvenirByName(String souvenirName);

    Optional<Souvenir> findSouvenirForUpdate(long souvenirId);

    Souvenir saveSouvenir(Souvenir souvenir);

    void deleteSouvenir(Souvenir souvenir);

    /** 지금 DB 의 재고 수량 (영속성 문맥의 옛 값이 아니라). */
    int findStockQuantity(long souvenirId);

    /** 입고 내역 저장과 재고 증가를 함께 반영한다. 숨긴 품목이면 empty. */
    Optional<StockReceipt> saveStockReceipt(StockReceipt receipt);

    Optional<StockReceipt> findStockReceiptByRequestKey(String requestKey);

    long countStockReceipts(long souvenirId);

    /** 재고가 quantity 이상일 때만 줄인다 (음수 방지). */
    boolean decreaseStock(long souvenirId, int quantity);

    StockIssue saveStockIssue(StockIssue issue);

    Set<Long> findIssuedApplicationIds(long souvenirId, Collection<Long> applicationIds);

    long countStockIssues(long souvenirId);

    /** souvenirId 가 null 이면 전체. page 는 0부터. */
    Page<StockIssue> findStockIssues(Long souvenirId, int page, int size);

    List<StockIssue> findStockIssuesForApplication(long applicationId);
}
