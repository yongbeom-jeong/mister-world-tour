package com.misterworldtour.travel;

import com.misterworldtour.sms.SMSNotificationAPI.SMSBatchResult;
import com.misterworldtour.travel.TravelApplication.TravelerInput;
import com.misterworldtour.travel.TravelPriceCalculator.PriceBreakdown;

import java.io.Serializable;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/**
 * Travel Management 가 User Interface 에 제공하는 여행 관리 기능 (설계 5.1 TravelManagementAPI).
 * 고객의 상품 조회·선택·구성·신청과 이전 여행 이력 조회, 직원의 상품 등록·수정·삭제.
 * 로그인한 회원은 LoginSession 으로 확인한다.
 */
public interface TravelManagementAPI {

    /** 구성 변경 결과: 새 구성과 1인 가격. */
    record ConfigurationResult(TravelConfiguration configuration, int unitPrice) {
    }

    /**
     * 신청 검토 결과. 저장하지 않으며 화면이 요청 키와 함께 보관했다가 확정 제출 때 넘긴다.
     *
     * @param travelers 정리한 실제 여행자 (첫 번째는 신청 고객 본인)
     */
    record ApplicationReviewResult(String requestKey, long productId, long memberId, TravelConfiguration configuration,
                                   List<TravelerInput> travelers, PriceBreakdown price) implements Serializable {
        public ApplicationReviewResult {
            travelers = List.copyOf(travelers);
        }

        /** 요청 키만 바꾼 검토 (데모 데이터를 다시 실행해도 중복되지 않게 고정 키를 쓸 때). */
        public ApplicationReviewResult withRequestKey(String key) {
            return new ApplicationReviewResult(key, productId, memberId, configuration, travelers, price);
        }
    }

    /**
     * 신청 등록 결과. recipientMemberIds 는 출발 확정 문자를 받을 회원 (없으면 빈 목록),
     * sms 는 문자 발송 결과 (보낼 문자가 없었으면 null). duplicate 는 같은 요청 키로 이미 저장된 신청이었는지.
     */
    record ApplicationResult(long applicationId, boolean honeymoon, DepartureStatus departureStatus,
                             boolean newlyConfirmed, boolean duplicate, int souvenirsIssued,
                             List<Long> recipientMemberIds, SMSBatchResult sms) {
        ApplicationResult withSms(SMSBatchResult result) {
            return new ApplicationResult(applicationId, honeymoon, departureStatus, newlyConfirmed, duplicate,
                    souvenirsIssued, recipientMemberIds, result);
        }
    }

    /** 직원이 입력하는 상품 정보. 기본 구성(hotel·meal·transport)은 비우면(null) 테마·등급 기본값. */
    record ProductInput(String productName, String destination, String description, Theme theme, TourLevel level,
                        int basePrice, int travelPeriod, LocalDate departureDate, HotelLevel hotel,
                        MealOption meal, TransportOption transport) {
    }

    /** 상품 사진 변경 요청: 그대로 / 삭제(테마 기본 사진 사용) / 새 사진. */
    record ImageChange(boolean delete, byte[] upload) {
        public static final ImageChange KEEP = new ImageChange(false, null);
        public static final ImageChange DELETE = new ImageChange(true, null);

        public static ImageChange replace(byte[] bytes) {
            return new ImageChange(false, bytes);
        }
    }

    /**
     * 테마·등급의 기본 구성(확인용)과 직원이 고를 수 있는 값. choices 는 항목별 [값, 이름] 목록이며 첫 항목이 기본값.
     */
    record DefaultConfigurationResult(String themeService, String souvenir, String transport, String hotelLevel,
                                      String meal, Map<String, List<List<String>>> choices) {
    }

    /** 고객의 이전 여행 이력 (완료일 최근 순). */
    List<TravelHistory> viewPreviousTravelHistory();

    /** 테마·등급에 맞는 상품 (null 이면 조건 없음). 고객은 출발 전 상품만, 직원은 전체. */
    List<TravelProduct> loadProducts(Theme theme, TourLevel level);

    /** 선택한 상품의 상세 정보와 기본 구성. 고객에게는 출발일이 지난 상품을 주지 않는다. */
    TravelProduct selectProduct(long productId);

    DefaultConfigurationResult loadDefaultConfiguration(Theme theme, TourLevel level);

    /** 현재 구성에 고객의 변경을 적용하고 검증한 뒤 1인 가격을 계산한다. 실패하면 직전 구성은 그대로. */
    ConfigurationResult configureTravelProduct(long productId, TravelConfiguration configuration,
                                               Map<String, ?> optionChanges);

    /** 상품·구성·여행자·정원·기념품 재고를 확인하고 금액(단골 할인 포함)을 계산한다. 저장하지 않는다. */
    ApplicationReviewResult reviewApplication(long productId, TravelConfiguration configuration,
                                              List<TravelerInput> travelers);

    /**
     * 확인한 신청을 다시 검증해 저장하고 출발 상태를 판정한다. 출발이 확정되면 문자 발송을 요청한다.
     * reviewedPrice 는 검토 때 보여 준 금액이며, 바뀌었으면 재검토를 요구한다. 같은 requestKey 의 재제출은 중복 저장하지 않는다.
     */
    ApplicationResult submitTravelApplication(long productId, TravelConfiguration configuration,
                                              List<TravelerInput> travelers, String requestKey,
                                              PriceBreakdown reviewedPrice);

    TravelProduct registerTourProduct(ProductInput input, ImageChange image);

    TravelProduct modifyTourProduct(long productId, ProductInput input, ImageChange image);

    /** 삭제한 상품 이름을 돌려준다. */
    String deleteTourProduct(long productId);
}
