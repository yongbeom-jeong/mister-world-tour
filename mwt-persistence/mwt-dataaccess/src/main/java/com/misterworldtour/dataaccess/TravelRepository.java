package com.misterworldtour.dataaccess;

import com.misterworldtour.travel.TravelDataAccessAPI.ProductApplicationCount;
import com.misterworldtour.travel.TravelDataAccessAPI.ProductFilter;
import com.misterworldtour.travel.DepartureStatus;
import com.misterworldtour.travel.Theme;
import com.misterworldtour.travel.TravelApplication;
import com.misterworldtour.travel.TravelHistory;
import com.misterworldtour.travel.TravelProduct;
import com.misterworldtour.travel.Traveler;

import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import jakarta.persistence.PersistenceContext;
import jakarta.persistence.TypedQuery;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;

/**
 * 여행 상품·신청·여행자·이전 여행 이력의 저장과 조회 (설계 7.1 TravelRepository).
 * 같은 상품의 관련 조회·저장은 {@link #runTravelTransaction} 으로 상품을 잠근 한 트랜잭션에서 처리한다.
 */
@Repository
class TravelRepository {

    @PersistenceContext
    private EntityManager em;

    private final TransactionTemplate tx;

    TravelRepository(PlatformTransactionManager transactionManager) {
        this.tx = new TransactionTemplate(transactionManager);
    }

    List<TravelProduct> findProducts(ProductFilter f) {
        StringBuilder q = new StringBuilder("select p from TravelProduct p where 1 = 1");
        if (f.departureAfter() != null) {
            q.append(" and p.departureDate > :after");
        }
        if (f.theme() != null) {
            q.append(" and p.theme = :theme");
        }
        if (f.level() != null) {
            q.append(" and p.tourLevel = :level");
        }
        q.append(" order by p.departureDate, p.productId");
        TypedQuery<TravelProduct> query = em.createQuery(q.toString(), TravelProduct.class);
        if (f.departureAfter() != null) {
            query.setParameter("after", f.departureAfter());
        }
        if (f.theme() != null) {
            query.setParameter("theme", f.theme());
        }
        if (f.level() != null) {
            query.setParameter("level", f.level());
        }
        return query.getResultList();
    }

    Optional<TravelProduct> findProduct(long productId) {
        return Optional.ofNullable(em.find(TravelProduct.class, productId));
    }

    /** 같은 상품의 신청·확정·수정을 한 번에 하나씩 (정원 동시 초과 방지). */
    Optional<TravelProduct> findProductForUpdate(long productId) {
        return em.createQuery("select p from TravelProduct p where p.productId = :id", TravelProduct.class)
                .setParameter("id", productId).setLockMode(LockModeType.PESSIMISTIC_WRITE)
                .getResultStream().findFirst();
    }

    TravelProduct saveProduct(TravelProduct product) {
        em.persist(product);
        em.flush();
        return product;
    }

    TravelProduct updateProduct(TravelProduct product) {
        TravelProduct merged = em.contains(product) ? product : em.merge(product);
        em.flush();
        return merged;
    }

    void deleteProduct(long productId) {
        TravelProduct p = em.find(TravelProduct.class, productId);
        if (p != null) {
            em.remove(p);
            em.flush();
        }
    }

    boolean hasApplications(long productId) {
        return !em.createQuery("select a.applicationId from TravelApplication a where a.product.productId = :id",
                Long.class).setParameter("id", productId).setMaxResults(1).getResultList().isEmpty();
    }

    void saveDepartureStatus(long productId, DepartureStatus status) {
        em.createQuery("update TravelProduct p set p.departureStatus = :s where p.productId = :id")
                .setParameter("s", status).setParameter("id", productId).executeUpdate();
    }

    <T> T runTravelTransaction(long productId, Function<Optional<TravelProduct>, T> work) {
        return tx.execute(status -> work.apply(findProductForUpdate(productId)));
    }

    Optional<TravelProduct> findProductByName(String name) {
        return em.createQuery("select p from TravelProduct p where p.productName = :name order by p.productId",
                TravelProduct.class).setParameter("name", name).setMaxResults(1).getResultStream().findFirst();
    }

    Optional<TravelProduct> findProductRegisteredSince(String name, Theme theme, LocalDate departureDate,
                                                       OffsetDateTime since) {
        return em.createQuery("""
                        select p from TravelProduct p where p.productName = :name and p.theme = :theme
                          and p.departureDate = :date and p.createdAt >= :since order by p.productId desc""",
                        TravelProduct.class)
                .setParameter("name", name).setParameter("theme", theme).setParameter("date", departureDate)
                .setParameter("since", since).setMaxResults(1).getResultStream().findFirst();
    }

    long countProducts() {
        return em.createQuery("select count(p) from TravelProduct p", Long.class).getSingleResult();
    }

    long countProductsByDepartureStatus(DepartureStatus status) {
        return em.createQuery("select count(p) from TravelProduct p where p.departureStatus = :s", Long.class)
                .setParameter("s", status).getSingleResult();
    }

    List<ProductApplicationCount> findRecentProducts(int limit) {
        return em.createQuery("""
                        select p, (select count(a) from TravelApplication a where a.product = p)
                        from TravelProduct p order by p.createdAt desc, p.productId desc""", Object[].class)
                .setMaxResults(limit).getResultList().stream()
                .map(r -> new ProductApplicationCount((TravelProduct) r[0], (Long) r[1])).toList();
    }

    TravelApplication saveApplication(TravelApplication application, List<Traveler> travelers) {
        for (Traveler t : travelers) {
            application.addTraveler(t);
        }
        em.persist(application);
        em.flush();
        return application;
    }

    Optional<TravelApplication> findApplicationByRequestKey(String requestKey) {
        return em.createQuery("""
                        select distinct a from TravelApplication a join fetch a.product left join fetch a.travelers
                        where a.requestKey = :key""", TravelApplication.class)
                .setParameter("key", requestKey).getResultStream().findFirst();
    }

    List<TravelApplication> findApplications(long productId) {
        return em.createQuery("""
                        select distinct a from TravelApplication a left join fetch a.travelers
                        where a.product.productId = :id and a.cancelledAt is null order by a.applicationId""",
                        TravelApplication.class)
                .setParameter("id", productId).getResultList();
    }

    int countTravelers(long productId) {
        return em.createQuery("""
                        select count(t) from Traveler t
                        where t.application.product.productId = :id and t.application.cancelledAt is null""",
                        Long.class)
                .setParameter("id", productId).getSingleResult().intValue();
    }

    Optional<TravelApplication> findApplication(long applicationId) {
        return Optional.ofNullable(em.find(TravelApplication.class, applicationId));
    }

    Optional<TravelApplication> findMemberApplication(long memberId, long applicationId) {
        return em.createQuery("""
                        select distinct a from TravelApplication a join fetch a.product left join fetch a.travelers
                        where a.applicationId = :id and a.memberId = :memberId""", TravelApplication.class)
                .setParameter("id", applicationId).setParameter("memberId", memberId).getResultStream().findFirst();
    }

    Optional<TravelApplication> findMemberApplicationByRequestKey(long memberId, String requestKey) {
        return em.createQuery("""
                        select a from TravelApplication a where a.memberId = :memberId and a.requestKey = :key""",
                        TravelApplication.class)
                .setParameter("memberId", memberId).setParameter("key", requestKey).getResultStream().findFirst();
    }

    Optional<Long> findApplicationProductId(long memberId, long applicationId) {
        return em.createQuery("""
                        select a.product.productId from TravelApplication a
                        where a.applicationId = :id and a.memberId = :memberId""", Long.class)
                .setParameter("id", applicationId).setParameter("memberId", memberId).getResultStream().findFirst();
    }

    List<TravelApplication> findMemberApplications(long memberId) {
        return em.createQuery("""
                        select a from TravelApplication a join fetch a.product where a.memberId = :memberId
                        order by a.submittedAt desc, a.applicationId desc""", TravelApplication.class)
                .setParameter("memberId", memberId).getResultList();
    }

    Map<Long, Integer> countTravelersByApplication(long memberId) {
        Map<Long, Integer> counts = new LinkedHashMap<>();
        em.createQuery("select a.applicationId, size(a.travelers) from TravelApplication a where a.memberId = :memberId",
                        Object[].class)
                .setParameter("memberId", memberId).getResultList()
                .forEach(r -> counts.put((Long) r[0], ((Number) r[1]).intValue()));
        return counts;
    }

    long countMemberApplications(long memberId) {
        return em.createQuery("""
                        select count(a) from TravelApplication a where a.memberId = :memberId and a.cancelledAt is null""",
                        Long.class)
                .setParameter("memberId", memberId).getSingleResult();
    }

    long countApplications() {
        return em.createQuery("select count(a) from TravelApplication a where a.cancelledAt is null", Long.class)
                .getSingleResult();
    }

    List<TravelApplication> findApplicationsByIds(Collection<Long> ids) {
        if (ids.isEmpty()) {
            return List.of();
        }
        return em.createQuery("select a from TravelApplication a join fetch a.product where a.applicationId in :ids",
                TravelApplication.class).setParameter("ids", ids).getResultList();
    }

    /** 출발 확정(허니문은 신청 상태, 그 외는 상품 상태)·미취소 신청 중 출발일이 today 이전인 것. */
    List<TravelApplication> findConfirmedApplicationsDepartedBefore(LocalDate today, Long memberId) {
        String q = """
                select a from TravelApplication a join fetch a.product p
                where a.cancelledAt is null and p.departureDate < :today
                  %s
                  and ((p.theme = com.misterworldtour.travel.Theme.HONEYMOON
                        and a.departureStatus = com.misterworldtour.travel.DepartureStatus.CONFIRMED)
                    or (p.theme <> com.misterworldtour.travel.Theme.HONEYMOON
                        and p.departureStatus = com.misterworldtour.travel.DepartureStatus.CONFIRMED))
                order by a.applicationId""".formatted(memberId == null ? "" : "and a.memberId = :memberId");
        TypedQuery<TravelApplication> query = em.createQuery(q, TravelApplication.class).setParameter("today", today);
        if (memberId != null) {
            query.setParameter("memberId", memberId);
        }
        return query.getResultList();
    }

    List<TravelHistory> findTravelHistories(long memberId) {
        return em.createQuery("""
                        select h from TravelHistory h where h.memberId = :memberId
                        order by case when h.completedAt is null then 1 else 0 end, h.completedAt desc, h.historyId desc""",
                        TravelHistory.class)
                .setParameter("memberId", memberId).getResultList();
    }

    TravelHistory saveTravelHistory(TravelHistory history) {
        em.persist(history);
        return history;
    }

    boolean existsTravelHistory(long memberId, String completedTravelId) {
        return !em.createQuery("""
                        select h.historyId from TravelHistory h
                        where h.memberId = :memberId and h.completedTravelId = :tid""", Long.class)
                .setParameter("memberId", memberId).setParameter("tid", completedTravelId)
                .setMaxResults(1).getResultList().isEmpty();
    }

    Set<String> findCompletedTravelKeys(String prefix, Long memberId) {
        String q = "select concat(h.memberId, ':', h.completedTravelId) from TravelHistory h "
                + "where h.completedTravelId like :prefix" + (memberId == null ? "" : " and h.memberId = :memberId");
        TypedQuery<String> query = em.createQuery(q, String.class).setParameter("prefix", prefix + "%");
        if (memberId != null) {
            query.setParameter("memberId", memberId);
        }
        return new HashSet<>(query.getResultList());
    }
}
