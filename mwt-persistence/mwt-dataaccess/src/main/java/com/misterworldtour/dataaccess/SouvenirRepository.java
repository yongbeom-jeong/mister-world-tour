package com.misterworldtour.dataaccess;

import com.misterworldtour.souvenir.Souvenir;
import com.misterworldtour.souvenir.StockIssue;
import com.misterworldtour.souvenir.StockReceipt;
import com.misterworldtour.travel.Theme;

import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import jakarta.persistence.PersistenceContext;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/** 기념품·재고 조회와 입고·차감 반영 (설계 7.1 SouvenirRepository). */
@Repository
class SouvenirRepository {

    @PersistenceContext
    private EntityManager em;

    List<Souvenir> findSouvenirs() {
        return em.createQuery("select s from Souvenir s where s.active = true order by s.theme, s.souvenirName",
                Souvenir.class).getResultList();
    }

    List<Souvenir> findAllSouvenirs() {
        return em.createQuery("select s from Souvenir s order by s.theme, s.souvenirName", Souvenir.class)
                .getResultList();
    }

    Optional<Souvenir> findSouvenir(long souvenirId) {
        return em.createQuery("select s from Souvenir s where s.souvenirId = :id and s.active = true", Souvenir.class)
                .setParameter("id", souvenirId).getResultStream().findFirst();
    }

    List<Souvenir> findSouvenirsByTheme(Theme theme) {
        return em.createQuery("""
                        select s from Souvenir s where s.theme = :theme and s.active = true order by s.souvenirId""",
                        Souvenir.class)
                .setParameter("theme", theme).getResultList();
    }

    Optional<Souvenir> findSouvenirByName(String name) {
        return em.createQuery("select s from Souvenir s where s.nameKey = :key", Souvenir.class)
                .setParameter("key", Souvenir.keyOf(name)).getResultStream().findFirst();
    }

    Optional<Souvenir> findSouvenirForUpdate(long souvenirId) {
        return em.createQuery("select s from Souvenir s where s.souvenirId = :id and s.active = true", Souvenir.class)
                .setParameter("id", souvenirId).setLockMode(LockModeType.PESSIMISTIC_WRITE)
                .getResultStream().findFirst();
    }

    Souvenir saveSouvenir(Souvenir souvenir) {
        Souvenir saved = souvenir;
        if (souvenir.getSouvenirId() == null) {
            em.persist(souvenir);
        } else if (!em.contains(souvenir)) {
            saved = em.merge(souvenir);
        }
        em.flush();
        return saved;
    }

    void deleteSouvenir(Souvenir souvenir) {
        em.remove(em.contains(souvenir) ? souvenir : em.merge(souvenir));
        em.flush();
    }

    int findStockQuantity(long souvenirId) {
        return em.createQuery("select s.stockQuantity from Souvenir s where s.souvenirId = :id", Integer.class)
                .setParameter("id", souvenirId).getSingleResult();
    }

    /** 사용 중인 품목만 재고를 늘리고 입고 내역을 저장한다. */
    Optional<StockReceipt> saveStockReceipt(StockReceipt receipt) {
        em.flush();
        int updated = em.createQuery("""
                        update Souvenir s set s.stockQuantity = s.stockQuantity + :q
                        where s.souvenirId = :id and s.active = true""")
                .setParameter("q", receipt.getQuantity()).setParameter("id", receipt.getSouvenirId()).executeUpdate();
        if (updated == 0) {
            return Optional.empty();
        }
        receipt.recordStockAfter(findStockQuantity(receipt.getSouvenirId()));
        em.persist(receipt);
        em.flush();
        return Optional.of(receipt);
    }

    Optional<StockReceipt> findStockReceiptByRequestKey(String requestKey) {
        return em.createQuery("select r from StockReceipt r join fetch r.souvenir where r.requestKey = :key",
                StockReceipt.class).setParameter("key", requestKey).getResultStream().findFirst();
    }

    long countStockReceipts(long souvenirId) {
        return em.createQuery("select count(r) from StockReceipt r where r.souvenir.souvenirId = :id", Long.class)
                .setParameter("id", souvenirId).getSingleResult();
    }

    /** 재고가 quantity 이상일 때만 줄이는 조건부 UPDATE. */
    boolean decreaseStock(long souvenirId, int quantity) {
        em.flush();
        return em.createQuery("""
                        update Souvenir s set s.stockQuantity = s.stockQuantity - :q
                        where s.souvenirId = :id and s.stockQuantity >= :q""")
                .setParameter("q", quantity).setParameter("id", souvenirId).executeUpdate() > 0;
    }

    StockIssue saveStockIssue(StockIssue issue) {
        em.persist(issue);
        return issue;
    }

    Set<Long> findIssuedApplicationIds(long souvenirId, Collection<Long> applicationIds) {
        if (applicationIds.isEmpty()) {
            return Set.of();
        }
        return new HashSet<>(em.createQuery("""
                        select i.applicationId from StockIssue i
                        where i.souvenir.souvenirId = :id and i.applicationId in :apps""", Long.class)
                .setParameter("id", souvenirId).setParameter("apps", applicationIds).getResultList());
    }

    long countStockIssues(long souvenirId) {
        return em.createQuery("select count(i) from StockIssue i where i.souvenir.souvenirId = :id", Long.class)
                .setParameter("id", souvenirId).getSingleResult();
    }

    Page<StockIssue> findStockIssues(Long souvenirId, int page, int size) {
        String where = souvenirId == null ? "" : " where i.souvenir.souvenirId = :id";
        var query = em.createQuery("select i from StockIssue i join fetch i.souvenir" + where
                + " order by i.issuedAt desc, i.id desc", StockIssue.class);
        var count = em.createQuery("select count(i) from StockIssue i" + where, Long.class);
        if (souvenirId != null) {
            query.setParameter("id", souvenirId);
            count.setParameter("id", souvenirId);
        }
        int p = Math.max(page, 0);
        List<StockIssue> content = query.setFirstResult(p * size).setMaxResults(size).getResultList();
        return new PageImpl<>(content, PageRequest.of(p, size), count.getSingleResult());
    }

    List<StockIssue> findStockIssuesForApplication(long applicationId) {
        return em.createQuery("select i from StockIssue i where i.applicationId = :id", StockIssue.class)
                .setParameter("id", applicationId).getResultList();
    }
}
