package com.misterworldtour.dataaccess;

import com.misterworldtour.members.Member;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.springframework.stereotype.Repository;

import java.time.OffsetDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

/** 회원 저장·조회 (설계 7.1 MemberRepository). */
@Repository
class MemberRepository {

    @PersistenceContext
    private EntityManager em;

    boolean existsAccount(String account) {
        return !em.createQuery("select m.memberId from Member m where m.accountKey = :key", Long.class)
                .setParameter("key", Member.accountKeyOf(account)).setMaxResults(1).getResultList().isEmpty();
    }

    /** 같은 아이디면 DB 유일 제약 오류. */
    Member saveMember(Member member) {
        em.persist(member);
        em.flush();
        return member;
    }

    Optional<Member> findMemberByAccount(String account) {
        return em.createQuery("select m from Member m where m.account = :account", Member.class)
                .setParameter("account", account).getResultStream().findFirst();
    }

    Optional<Member> findMember(long memberId) {
        return Optional.ofNullable(em.find(Member.class, memberId));
    }

    List<Member> findMembers(Collection<Long> memberIds) {
        if (memberIds.isEmpty()) {
            return List.of();
        }
        return em.createQuery("select m from Member m where m.memberId in :ids", Member.class)
                .setParameter("ids", memberIds).getResultList();
    }

    Optional<Member> findMemberJoinedSince(String account, OffsetDateTime since) {
        return em.createQuery("select m from Member m where m.accountKey = :key and m.joinedAt >= :since", Member.class)
                .setParameter("key", Member.accountKeyOf(account)).setParameter("since", since)
                .setMaxResults(1).getResultStream().findFirst();
    }
}
