package com.misterworldtour.members;

import org.springframework.data.jpa.repository.JpaRepository;

import java.time.OffsetDateTime;
import java.util.Optional;

/** Data Access: 회원. 다른 패키지는 이 저장소를 직접 쓰지 않고 {@link MemberService} 를 거친다. */
interface MemberRepository extends JpaRepository<Member, Long> {

    /** 로그인용. Django 기본 인증과 같이 아이디는 정확히 일치해야 한다. */
    Optional<Member> findByUsername(String username);

    boolean existsByUsernameKey(String usernameKey);

    Optional<Member> findFirstByUsernameKeyAndDateJoinedGreaterThanEqual(String usernameKey, OffsetDateTime since);
}
