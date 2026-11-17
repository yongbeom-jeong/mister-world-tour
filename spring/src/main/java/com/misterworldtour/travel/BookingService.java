package com.misterworldtour.travel;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDate;
import java.util.List;

/**
 * 고객의 상품 선택·구성(UC4)과 신청(UC5) 업무 처리 (설계 05장 TravelManager, Django 판 travel/booking.py).
 * 컨트롤러는 권한 확인과 입력 형식 검사만 하고 이 서비스를 호출한다.
 */
@Service
public class BookingService {

    private final TravelProductRepository products;
    private final TravelApplicationRepository applications;
    private final TravelerRepository travelers;
    private final Clock clock;

    BookingService(TravelProductRepository products, TravelApplicationRepository applications,
                   TravelerRepository travelers, Clock clock) {
        this.products = products;
        this.applications = applications;
        this.travelers = travelers;
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

    /** 취소하지 않은 신청 수 (고객 홈 표시). */
    @Transactional(readOnly = true)
    public long activeApplicationCount(long memberId) {
        return applications.countByMemberIdAndCancelledAtIsNull(memberId);
    }
}
