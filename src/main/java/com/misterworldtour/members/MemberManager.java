package com.misterworldtour.members;

import com.misterworldtour.members.MemberManagementAPI.LoginInfo;
import com.misterworldtour.members.MemberManagementAPI.LoginResult;
import com.misterworldtour.members.MemberManagementAPI.SignUpResult;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 회원가입과 로그인 처리 (설계 4.1 MemberManager). 회원 저장·조회는 {@link MemberDataAccessAPI} 에 요청한다.
 *
 * <ul>
 *   <li>signUp: 입력·회원 유형·아이디 중복을 확인하고 비밀번호 해시와 역할을 담은 Member 를 저장한다.</li>
 *   <li>logIn: 회원을 조회해 비밀번호를 검증하고, 성공하면 LoginSession 을 만들어 돌려준다.
 *       세션 보관은 Spring Security 가 사용자별 서버 세션에 한다 ({@code common.SecurityConfig}).</li>
 * </ul>
 * 다른 서브시스템은 회원 정보를 이 클래스의 메소드로만 다룬다.
 */
@Service
public class MemberManager implements MemberManagementAPI {

    /** 아이디: 영문·숫자·밑줄 4~20자. */
    public static final Pattern ACCOUNT_RE = Pattern.compile("^[A-Za-z0-9_]{4,20}$");
    public static final Pattern CONTACT_RE = Pattern.compile("^(01[016789])-?(\\d{3,4})-?(\\d{4})$");
    static final String REQUIRED = "필수 항목입니다.";

    /** 문자 발송용 성명·연락처. */
    public record Contact(String name, String contact) {
    }

    private final MemberDataAccessAPI dataAccess;
    private final PasswordPolicy passwordPolicy;

    MemberManager(MemberDataAccessAPI dataAccess, PasswordPolicy passwordPolicy) {
        this.dataAccess = dataAccess;
        this.passwordPolicy = passwordPolicy;
    }

    // ---------------------------------------------------------------- SignUp (UC1)

    /**
     * 회원가입. 입력값은 앞뒤 공백을 지우고 연락처는 010-1234-5678 형식으로 정리해 저장한다.
     * 입력 오류나 아이디 중복이면 저장하지 않고 항목별(memberType·account·password·name·address·contact) 안내를 돌려준다.
     * 저장은 SaveMember 의 트랜잭션에서 하며, 검사 뒤 같은 아이디가 동시에 가입되면 DB 유일 제약으로 막는다.
     */
    @Override
    public SignUpResult signUp(Role memberType, String account, String password, String name, String address,
                               String contact) {
        String acc = strip(account);
        String nm = strip(name);
        String addr = strip(address);
        String phone = strip(contact);
        String pw = password == null ? "" : password;
        Map<String, String> errors = checkSignUp(memberType, acc, pw, nm, addr, phone);
        if (!errors.isEmpty()) {
            return new SignUpResult(null, errors);
        }
        Member member = new Member(acc, Member.hashPassword(pw), nm, addr, normalizeContact(phone).orElseThrow(),
                memberType);
        try {
            return new SignUpResult(dataAccess.saveMember(member), Map.of());
        } catch (DataIntegrityViolationException e) { // 검사 이후 같은 아이디가 동시에 가입된 경우
            return new SignUpResult(null, Map.of("account", "이미 사용 중인 아이디입니다."));
        }
    }

    /**
     * 가입 입력 검사만 한다 (저장하지 않음). 비밀번호 정책 위반이 여러 개면 줄바꿈으로 이어 돌려준다.
     * 화면이 비밀번호 확인 불일치 같은 자체 오류와 함께 보여 줄 때도 쓴다.
     */
    @Transactional(readOnly = true)
    public Map<String, String> checkSignUp(Role memberType, String account, String password, String name,
                                           String address, String contact) {
        account = strip(account);
        name = strip(name);
        address = strip(address);
        contact = strip(contact);
        password = password == null ? "" : password;
        Map<String, String> errors = new LinkedHashMap<>();
        if (memberType == null) {
            errors.put("memberType", "회원 유형을 골라 주세요.");
        }
        if (account.isEmpty()) {
            errors.put("account", REQUIRED);
        } else if (account.length() > 20) {
            errors.put("account", tooLong(20, account));
        } else if (!ACCOUNT_RE.matcher(account).matches()) {
            errors.put("account", "아이디는 영문·숫자·밑줄(_)로 4~20자여야 합니다.");
        } else if (dataAccess.existsAccount(account)) {
            errors.put("account", "이미 사용 중인 아이디입니다.");
        }
        if (password.isEmpty()) {
            errors.put("password", REQUIRED);
        } else {
            java.util.List<String> problems = passwordPolicy.validate(password, account, name);
            if (!problems.isEmpty()) {
                errors.put("password", String.join("\n", problems));
            }
        }
        if (name.isEmpty()) {
            errors.put("name", REQUIRED);
        } else if (name.length() > 50) {
            errors.put("name", tooLong(50, name));
        }
        if (address.isEmpty()) {
            errors.put("address", REQUIRED);
        } else if (address.length() > 200) {
            errors.put("address", tooLong(200, address));
        }
        if (contact.isEmpty()) {
            errors.put("contact", REQUIRED);
        } else if (contact.length() > 20) {
            errors.put("contact", tooLong(20, contact));
        } else if (normalizeContact(contact).isEmpty()) {
            errors.put("contact", "휴대전화 번호 형식이 아닙니다. 예: 010-1234-5678");
        }
        return errors;
    }

    private static String tooLong(int max, String value) {
        return max + "자 이하로 입력해 주세요 (지금 " + value.length() + "자).";
    }

    // ---------------------------------------------------------------- LogIn (UC2)

    /** 로그인. 아이디는 정확히 일치해야 한다. 실패하면 loginSession 이 없는 결과. */
    @Override
    @Transactional(readOnly = true)
    public LoginResult logIn(LoginInfo loginInfo) {
        String account = loginInfo.account() == null ? "" : loginInfo.account();
        Optional<Member> found = dataAccess.findMemberByAccount(account);
        if (found.isEmpty()) {
            Member.checkDummyPassword(loginInfo.password());
            return new LoginResult(null);
        }
        Member member = found.get();
        if (!member.checkPassword(loginInfo.password())) {
            return new LoginResult(null);
        }
        LoginSession session = new LoginSession(member.getMemberId(), member.getRole(), member.getAccount(),
                member.getName());
        return new LoginResult(session);
    }

    // ---------------------------------------------------------------- 다른 서브시스템·화면용 조회

    /** since 이후 이 아이디로 가입된 회원. 가입 저장 중 오류 뒤 가입 여부 확인용. */
    @Transactional(readOnly = true)
    public Optional<Member> findMemberJoinedSince(String account, OffsetDateTime since) {
        return dataAccess.findMemberJoinedSince(strip(account), since);
    }

    @Transactional(readOnly = true)
    public Optional<Member> findMemberByAccount(String account) {
        return dataAccess.findMemberByAccount(account);
    }

    @Transactional(readOnly = true)
    public Optional<Contact> getContact(long memberId) {
        return dataAccess.findMember(memberId).map(m -> new Contact(m.getName(), m.getContact()));
    }

    /** 회원 고유번호 → 성명 (다른 서브시스템 화면 표시용). 없는 회원은 빠진다. */
    @Transactional(readOnly = true)
    public Map<Long, String> namesByIds(Collection<Long> ids) {
        Map<Long, String> map = new HashMap<>();
        dataAccess.findMembers(ids).forEach(m -> map.put(m.getMemberId(), m.getName()));
        return map;
    }

    /** 휴대전화 번호를 010-1234-5678 형식으로. 형식이 아니면 empty. */
    public static Optional<String> normalizeContact(String value) {
        Matcher m = CONTACT_RE.matcher(value == null ? "" : value.strip());
        if (!m.matches()) {
            return Optional.empty();
        }
        return Optional.of(m.group(1) + "-" + m.group(2) + "-" + m.group(3));
    }

    private static String strip(String s) {
        return s == null ? "" : s.strip();
    }
}
