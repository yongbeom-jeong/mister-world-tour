package com.misterworldtour.travel;

import java.util.List;

/**
 * 신청 검토·출발 확정에 필요한 테마 기념품 재고 기능. Travel Management 가 필요로 하는 인터페이스이며,
 * Souvenir Management 의 {@code SouvenirManager} 가 구현한다 (의존성 역전: 인터페이스는 쓰는 쪽이 가진다).
 * 그래서 의존은 souvenir → travel 한 방향뿐이다.
 */
public interface SouvenirStockAPI {

    /** 테마에 연결된 사용 중인 품목의 이름과 지금 재고. */
    record ThemeSouvenir(String souvenirName, int stockQuantity) {
    }

    /** 차감할 신청 하나: 신청 번호와 여행자 수. */
    record IssueItem(long applicationId, int travelerCount) {
    }

    /** 출발 확정에 필요한 기념품이 모자람. 호출자 트랜잭션이 취소되어 앞의 차감도 되돌아간다. */
    class SouvenirShortage extends RuntimeException {
        private final String name;
        private final int needed;
        private final int stock;

        public SouvenirShortage(String name, int needed, int stock) {
            super(name + ": needed=" + needed + " stock=" + stock);
            this.name = name;
            this.needed = needed;
            this.stock = stock;
        }

        public String getName() {
            return name;
        }

        public int getNeeded() {
            return needed;
        }

        public int getStock() {
            return stock;
        }
    }

    /** 테마에 연결된 사용 중인 품목 (테마·이름 순). */
    List<ThemeSouvenir> themeSouvenirs(Theme theme);

    /** 테마에 연결된 사용 중인 품목 이름. */
    List<String> souvenirNamesForTheme(Theme theme);

    /**
     * 출발 확정된 신청들의 테마 기념품을 차감한다. 품목마다 여행자 1인당 1개, (품목, 신청)마다 한 번만.
     * 한 품목이라도 모자라면 {@link SouvenirShortage}. 반환값은 차감한 총 개수.
     */
    int issueForDeparture(Theme theme, List<IssueItem> items);
}
