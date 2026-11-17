package com.misterworldtour.travel;

import com.misterworldtour.travel.Pricing.PriceBreakdown;

import java.io.Serializable;
import java.util.List;

/**
 * 신청 검토 결과 (UC5). 저장하지 않고 요청 키와 함께 사용자 세션에 보관했다가, 확정 제출 때 다시 검사해 저장한다.
 *
 * @param travelers 첫 번째는 신청 고객 본인
 */
public record Review(String requestKey, long productId, long memberId, TravelConfig config,
                     List<TravelerInput> travelers, PriceBreakdown breakdown) implements Serializable {

    /** 실제 여행자 한 명의 입력 (정리된 값). */
    public record TravelerInput(String name, String contact, boolean applicant) implements Serializable {
    }

    public Review {
        travelers = List.copyOf(travelers);
    }

    /** 요청 키만 바꾼 검토 (데모 데이터를 다시 실행해도 중복되지 않게 고정 키를 쓸 때). */
    public Review withRequestKey(String key) {
        return new Review(key, productId, memberId, config, travelers, breakdown);
    }
}
