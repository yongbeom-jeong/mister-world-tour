package com.misterworldtour.members;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Member Management 서비스 (설계 04장 MemberManager).
 *
 * <p>다른 패키지는 회원 정보를 이 클래스의 메서드로만 다룬다.
 */
@Service
public class MemberService {

    public static final Pattern USERNAME_RE = Pattern.compile("^[A-Za-z0-9_]{4,20}$");
    public static final Pattern CONTACT_RE = Pattern.compile("^(01[016789])-?(\\d{3,4})-?(\\d{4})$");

    /** 폼 검사 이후 동시에 같은 아이디(대소문자 무시)가 가입된 경우. */
    public static class DuplicateAccountException extends RuntimeException {
        public DuplicateAccountException(String username, Throwable cause) {
            super(username, cause);
        }
    }

    /** 문자 발송용 성명·연락처. */
    public record Contact(String name, String contact) {
    }

    private final MemberRepository members;
    private final PasswordEncoder passwordEncoder;

    MemberService(MemberRepository members, PasswordEncoder passwordEncoder) {
        this.members = members;
        this.passwordEncoder = passwordEncoder;
    }

    /** 검증된 입력으로 회원을 저장한다. 비밀번호는 해시로 저장된다. */
    @Transactional
    public Member signUp(Role role, String username, String password, String name, String address, String contact) {
        Member member = new Member(username, passwordEncoder.encode(password), name, address, contact, role);
        try {
            return members.saveAndFlush(member);
        } catch (DataIntegrityViolationException e) {
            throw new DuplicateAccountException(username, e);
        }
    }

    @Transactional(readOnly = true)
    public boolean usernameTaken(String username) {
        return members.existsByUsernameKey(Member.keyOf(username));
    }

    /** since 이후 이 아이디로 가입된 회원. 가입 저장 중 오류 뒤 가입 여부 확인용. */
    @Transactional(readOnly = true)
    public Optional<Member> findMemberJoinedSince(String username, OffsetDateTime since) {
        return members.findFirstByUsernameKeyAndDateJoinedGreaterThanEqual(Member.keyOf(username), since);
    }

    @Transactional(readOnly = true)
    public Optional<Member> findByUsername(String username) {
        return members.findByUsername(username);
    }

    @Transactional(readOnly = true)
    public Optional<Contact> getContact(long memberId) {
        return members.findById(memberId).map(m -> new Contact(m.getName(), m.getContact()));
    }

    /** 휴대전화 번호를 010-1234-5678 형식으로. 형식이 아니면 empty. */
    public static Optional<String> normalizeContact(String value) {
        Matcher m = CONTACT_RE.matcher(value == null ? "" : value.strip());
        if (!m.matches()) {
            return Optional.empty();
        }
        return Optional.of(m.group(1) + "-" + m.group(2) + "-" + m.group(3));
    }
}
