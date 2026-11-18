package com.misterworldtour.demo;

import com.misterworldtour.travel.TravelProperties;
import com.misterworldtour.members.MemberManager;
import com.misterworldtour.members.Role;
import com.misterworldtour.souvenir.SouvenirManager;
import com.misterworldtour.travel.Theme;
import com.misterworldtour.travel.TourLevel;
import com.misterworldtour.travel.TravelApplication.TravelerInput;
import com.misterworldtour.travel.TravelConfiguration;
import com.misterworldtour.travel.TravelManagementAPI.ApplicationReviewResult;
import com.misterworldtour.travel.TravelManager;
import com.misterworldtour.travel.TravelProduct;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Clock;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 데모·시험용 더미 데이터.
 *
 * <pre>mvnw.cmd spring-boot:run -Dspring-boot.run.arguments=--seed-demo</pre>
 *
 * 데이터를 넣은 뒤 서버는 그대로 실행된다. 여러 번 실행해도 중복 생성되지 않는다(아이디·상품명 기준).
 * 날짜는 실행일 기준 상대값이다. 모든 값은 설계에 정해지지 않은 임의 값이다. 비밀번호는 모두 {@value #PASSWORD}.
 */
@Component
class DemoSeeder implements ApplicationRunner {

    static final String PASSWORD = "demo1234!";
    private static final Logger log = LoggerFactory.getLogger(DemoSeeder.class);

    record DemoMember(String username, Role role, String name, String address, String contact) {
    }

    record DemoProduct(String name, String destination, Theme theme, TourLevel level, int price, int days,
                       int daysUntilDeparture, String description, String photo) {
    }

    static final List<DemoMember> MEMBERS = List.of(
            new DemoMember("staff1", Role.STAFF, "김직원", "서울시 중구 세종대로 110", "010-1000-0001"),
            new DemoMember("regular1", Role.CUSTOMER, "박단골", "서울시 강남구 테헤란로 1", "010-2000-0001"),
            new DemoMember("customer2", Role.CUSTOMER, "이두번", "부산시 해운대구 해운대로 2", "010-2000-0002"),
            new DemoMember("newbie", Role.CUSTOMER, "최처음", "대전시 유성구 대학로 3", "010-2000-0003"));

    static final List<DemoProduct> PRODUCTS = List.of(
            new DemoProduct("몰디브 워터빌라 허니문", "몰디브 말레", Theme.HONEYMOON, TourLevel.PREMIUM, 3_200_000, 6, 45,
                    "수상 빌라에서 보내는 둘만의 6일. 선셋 크루즈 포함.", "maldives"),
            new DemoProduct("발리 우붓 로맨틱 허니문", "인도네시아 발리", Theme.HONEYMOON, TourLevel.GRAND, 2_400_000, 5, 30,
                    "우붓 정글 풀빌라와 짐바란 해변 디너.", "bali"),
            new DemoProduct("하와이 마우이 허니문", "미국 하와이", Theme.HONEYMOON, TourLevel.PREMIUM, 3_800_000, 7, 60,
                    "마우이 할레아칼라 일출과 오아후 자유일정.", "maui"),
            new DemoProduct("제주 힐링 효도 여행", "제주도", Theme.FILIAL, TourLevel.GRAND, 890_000, 3, 20,
                    "천천히 걷는 일정과 매일 안마·지압 서비스.", "jeju"),
            new DemoProduct("벳푸 온천 효도 여행", "일본 오이타 벳푸", Theme.FILIAL, TourLevel.PREMIUM, 1_650_000, 4, 35,
                    "료칸 숙박과 가이세키 요리, 지옥온천 순례.", "beppu"),
            new DemoProduct("타이베이 효도 여행", "대만 타이베이", Theme.FILIAL, TourLevel.GRAND, 1_190_000, 4, 50,
                    "고궁박물원·스펀 천등 날리기, 발 마사지 포함.", "taipei"),
            new DemoProduct("치앙마이 골프 챌린지", "태국 치앙마이", Theme.GOLF, TourLevel.CLASSIC, 1_290_000, 5, 25,
                    "명문 코스 3라운드, 카트·캐디피 포함.", "chiangmai"),
            new DemoProduct("다낭 골프 챌린지", "베트남 다낭", Theme.GOLF, TourLevel.GRAND, 1_490_000, 5, 40,
                    "바다를 끼고 도는 몽고메리 링크스 2라운드.", "danang"),
            new DemoProduct("미야자키 골프 챌린지", "일본 미야자키", Theme.GOLF, TourLevel.PREMIUM, 2_100_000, 4, 55,
                    "피닉스 컨트리클럽 라운드와 온천 리조트.", "miyazaki"),
            new DemoProduct("안나푸르나 베이스캠프 트레킹", "네팔 포카라", Theme.TREKKING, TourLevel.CLASSIC, 2_300_000, 10, 70,
                    "ABC 4,130m까지 전문 가이드와 함께.", "annapurna"),
            new DemoProduct("융프라우 알프스 트레킹", "스위스 인터라켄", Theme.TREKKING, TourLevel.PREMIUM, 4_500_000, 8, 90,
                    "아이거 트레일과 융프라우요흐, 산악열차 패스 포함.", "jungfrau"),
            new DemoProduct("한라산·올레길 트레킹", "제주도", Theme.TREKKING, TourLevel.CLASSIC, 590_000, 3, 15,
                    "성판악 코스 정상 등반과 올레 7코스.", "hallasan"));

    /** 초기 기념품 재고 (입고 내역으로 기록). */
    static final Map<String, Integer> STOCK = Map.of("커플 기념티", 20, "건강 인삼", 30, "골프공", 50, "기념품 스카프", 40);

    record DemoHistory(String productName, TourLevel level, int days, int price, int daysAgo) {
    }

    /** 아이디 → 완료 이력. regular1 은 3년 내 3회(단골), customer2 는 3년 내 2회 + 오래된 1회(단골 아님). */
    static final Map<String, List<DemoHistory>> HISTORIES = Map.of(
            "regular1", List.of(
                    new DemoHistory("오사카 미식 효도 여행", TourLevel.GRAND, 4, 1_100_000, 120),
                    new DemoHistory("괌 가족 휴양", TourLevel.GRAND, 5, 1_500_000, 400),
                    new DemoHistory("다낭 골프 챌린지", TourLevel.CLASSIC, 5, 1_300_000, 800),
                    new DemoHistory("푸껫 골프 챌린지", TourLevel.CLASSIC, 5, 1_200_000, 1_500)), // 3년 지남
            "customer2", List.of(
                    new DemoHistory("제주 힐링 효도 여행", TourLevel.GRAND, 3, 850_000, 200),
                    new DemoHistory("한라산·올레길 트레킹", TourLevel.CLASSIC, 3, 550_000, 600),
                    new DemoHistory("홋카이도 설경 트레킹", TourLevel.GRAND, 6, 1_900_000, 1_400)));

    record DemoApplication(String username, String productName, List<String> companions) {
    }

    /**
     * 미리 넣어 둘 신청. 실제 신청 처리(검토 → 등록)를 그대로 쓴다.
     * 다낭 골프 2명(1명 더 오면 출발 확정), 제주 효도 8명(이미 확정, 남은 자리 2명).
     */
    static final List<DemoApplication> APPLICATIONS = List.of(
            new DemoApplication("customer2", "다낭 골프 챌린지", List.of("이동반")),
            new DemoApplication("customer2", "제주 힐링 효도 여행",
                    List.of("이부모", "이모친", "김이모", "김삼촌", "박사촌", "박조카", "최할매")));

    private final MemberManager members;
    private final TravelManager travel;
    private final SouvenirManager souvenirs;
    private final TravelProperties properties;
    private final Clock clock;

    DemoSeeder(MemberManager members, TravelManager travel, SouvenirManager souvenirs,
               TravelProperties properties, Clock clock) {
        this.members = members;
        this.travel = travel;
        this.souvenirs = souvenirs;
        this.properties = properties;
        this.clock = clock;
    }

    @Override
    public void run(ApplicationArguments args) throws IOException {
        if (args.containsOption("seed-demo")) {
            seed();
        }
    }

    void seed() throws IOException {
        LocalDate today = LocalDate.now(clock);
        int newMembers = 0;
        for (DemoMember m : MEMBERS) {
            if (members.findMemberByAccount(m.username()).isEmpty()) {
                members.signUp(m.role(), m.username(), PASSWORD, m.name(), m.address(), m.contact());
                newMembers++;
            }
        }
        int newProducts = 0;
        for (DemoProduct d : PRODUCTS) {
            if (travel.findProductByName(d.name()).isPresent()) {
                continue;
            }
            TravelProduct p = travel.registerTourProduct(new TravelProduct(d.name(), d.destination(), d.description(),
                    d.theme(), d.level(), d.price(), d.days(), today.plusDays(d.daysUntilDeparture())));
            travel.setProductImage(p.getProductId(), copyPhoto(d.photo()));
            newProducts++;
        }
        long staffId = memberId("staff1");
        STOCK.forEach((name, qty) -> souvenirs.seedStock(name, qty, staffId, "seed-" + name));

        int newHistories = 0;
        for (Map.Entry<String, List<DemoHistory>> e : HISTORIES.entrySet()) {
            long mid = memberId(e.getKey());
            for (int i = 0; i < e.getValue().size(); i++) {
                DemoHistory h = e.getValue().get(i);
                if (travel.seedHistory(mid, "seed-" + e.getKey() + "-" + i, h.productName(), h.level(), h.days(),
                        h.price(), today.minusDays(h.daysAgo()))) {
                    newHistories++;
                }
            }
        }

        int newApps = 0;
        for (int i = 0; i < APPLICATIONS.size(); i++) {
            DemoApplication a = APPLICATIONS.get(i);
            String key = "seed-application-" + i;
            long mid = memberId(a.username());
            if (travel.findSubmittedApplication(mid, key).isPresent()) {
                continue;
            }
            TravelProduct product = travel.findProductByName(a.productName()).orElse(null);
            if (product == null) {
                continue;
            }
            List<TravelerInput> rows = new ArrayList<>();
            rows.add(new TravelerInput("", "", true)); // 첫 행은 신청 고객 본인 (계정 정보로 채움)
            a.companions().forEach(n -> rows.add(new TravelerInput(n, "", false)));
            ApplicationReviewResult review = travel.reviewApplication(mid, product.getProductId(), TravelConfiguration.defaultOf(product), rows);
            travel.submitTravelApplication(mid, review.withRequestKey(key));
            newApps++;
        }
        log.info("데모 데이터 준비 완료: 계정 {}개·상품 {}개·이력 {}건·신청 {}건 신규 (비밀번호 모두 {})",
                newMembers, newProducts, newHistories, newApps, PASSWORD);
    }

    private long memberId(String username) {
        return members.findMemberByAccount(username).orElseThrow().getMemberId();
    }

    /** 데모 상품 사진을 업로드 사진처럼 media 폴더에 복사한다. 반환값은 media 기준 경로. */
    private String copyPhoto(String photo) throws IOException {
        String relative = "products/seed/" + photo + ".jpg";
        Path target = Path.of(properties.mediaRoot()).resolve(relative);
        Files.createDirectories(target.getParent());
        try (InputStream in = new ClassPathResource("static/img/photos/" + photo + ".jpg").getInputStream()) {
            Files.copy(in, target, StandardCopyOption.REPLACE_EXISTING);
        }
        return relative;
    }
}
