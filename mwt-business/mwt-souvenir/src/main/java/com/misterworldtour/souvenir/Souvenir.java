package com.misterworldtour.souvenir;

import com.misterworldtour.travel.Theme;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;

import java.util.Locale;

/**
 * 기념품 품목 (설계 6.1 Souvenir): 품목 번호·이름·여행 테마·조회 시점의 재고 수량. 테마에 여러 품목이 있을 수 있다.
 * 입고·차감 내역이 있는 품목을 삭제하면 내역 보존을 위해 숨긴다(active=false). 숨긴 품목은 신청 검사·차감·입고에서 빠진다.
 */
@Entity
@Table(name = "souvenir")
public class Souvenir {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Long souvenirId;

    @Column(name = "name", nullable = false, length = 50)
    private String souvenirName;

    @Column(name = "name_key", nullable = false, length = 50, unique = true)
    private String nameKey;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private Theme theme;

    @Column(name = "stock_quantity", nullable = false)
    private int stockQuantity;

    @Column(name = "is_active", nullable = false)
    private boolean active = true;

    protected Souvenir() {
    }

    public Souvenir(String souvenirName, Theme theme) {
        this.souvenirName = souvenirName;
        this.theme = theme;
    }

    /** 대소문자를 무시한 이름 중복 검사용 값. */
    public static String keyOf(String name) {
        return name == null ? "" : name.toLowerCase(Locale.ROOT);
    }

    @PrePersist
    @PreUpdate
    void fillKey() {
        nameKey = keyOf(souvenirName);
    }

    void rename(String souvenirName, Theme theme) {
        this.souvenirName = souvenirName;
        this.theme = theme;
    }

    void hide() {
        active = false;
    }

    public Long getSouvenirId() {
        return souvenirId;
    }

    public String getSouvenirName() {
        return souvenirName;
    }

    public Theme getTheme() {
        return theme;
    }

    public int getStockQuantity() {
        return stockQuantity;
    }

    public boolean isActive() {
        return active;
    }

    @Override
    public String toString() {
        return souvenirName + " (" + theme.getLabel() + ")";
    }
}
