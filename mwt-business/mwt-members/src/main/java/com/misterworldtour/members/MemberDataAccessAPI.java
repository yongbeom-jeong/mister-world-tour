package com.misterworldtour.members;

import java.time.OffsetDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

/** 회원 저장·조회. Data Access 의 {@code DataAccessManager} 가 구현한다. */
public interface MemberDataAccessAPI {

    boolean existsAccount(String account);

    Member saveMember(Member member);

    Optional<Member> findMemberByAccount(String account);

    Optional<Member> findMember(long memberId);

    List<Member> findMembers(Collection<Long> memberIds);

    Optional<Member> findMemberJoinedSince(String account, OffsetDateTime since);
}
