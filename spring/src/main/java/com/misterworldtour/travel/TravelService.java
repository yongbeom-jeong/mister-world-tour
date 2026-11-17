package com.misterworldtour.travel;

import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

/**
 * Travel Management 서비스: 직원 상품 관리·통계·완료 이력 (설계 05장 TravelManager, Django 판 travel/services.py).
 */
@Service
public class TravelService {

    /** 직원 홈 통계. */
    public record DashboardCounts(long products, long applications, long confirmed) {
    }

    /** 직원 홈: 최근 등록 상품과 신청 수. */
    public record RecentProduct(TravelProduct product, long applicationCount) {
    }

    private final TravelProductRepository products;
    private final TravelApplicationRepository applications;
    private final TravelHistoryRepository histories;

    TravelService(TravelProductRepository products, TravelApplicationRepository applications,
                  TravelHistoryRepository histories) {
        this.products = products;
        this.applications = applications;
        this.histories = histories;
    }

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
    public Optional<TravelProduct> findByName(String name) {
        return products.findFirstByName(name);
    }

    /**
     * 상품 등록 (UC6). 기본 구성·출발 상태는 상품이 테마·등급으로 정한다.
     * 직원 화면의 입력 검사·사진 처리는 상품 관리 화면 이식 때 붙인다.
     */
    @Transactional
    public TravelProduct registerTourProduct(TravelProduct product) {
        String error = product.levelError();
        if (error != null) {
            throw new IllegalArgumentException(error);
        }
        return products.save(product);
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

    /** 상품 대표 사진 경로를 바꾼다 (데모 데이터·직원 사진 업로드). */
    @Transactional
    public void setProductImage(long productId, String imagePath) {
        products.findById(productId).ifPresent(p -> p.setImage(imagePath));
    }
}
