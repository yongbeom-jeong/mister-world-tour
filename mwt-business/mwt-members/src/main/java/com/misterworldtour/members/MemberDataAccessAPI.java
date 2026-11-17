package com.misterworldtour.members;

import java.time.OffsetDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

/**
 * 회원 데이터의 저장과 조회. Member Management 가 필요로 하는 인터페이스이며,
 * Data Access 서브시스템의 {@code DataAccessManager} 가 구현한다 (의존성 역전: 인터페이스는 쓰는 쪽이 가진다).
 */
public interface MemberDataAccessAPI {

    boolean existsAccount(String account);

    Member saveMember(Member member);

    Optional<Member> findMemberByAccount(String account);

    Optional<Member> findMember(long memberId);

    List<Member> findMembers(Collection<Long> memberIds);

    /** since 이후 이 아이디(대소문자 무시)로 가입된 회원. */
    Optional<Member> findMemberJoinedSince(String account, OffsetDateTime since);
}
