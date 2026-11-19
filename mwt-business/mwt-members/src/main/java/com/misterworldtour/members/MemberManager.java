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

/** 회원가입과 로그인 처리 (설계 4.1 MemberManager). */
@Service
public class MemberManager implements MemberManagementAPI {

    public static final Pattern ACCOUNT_RE = Pattern.compile("^[A-Za-z0-9_]{4,20}$");
    static final String REQUIRED = "필수 항목입니다.";

    private final MemberDataAccessAPI dataAccess;
    private final PasswordPolicy passwordPolicy;

    MemberManager(MemberDataAccessAPI dataAccess, PasswordPolicy passwordPolicy) {
        this.dataAccess = dataAccess;
        this.passwordPolicy = passwordPolicy;
    }

    /** 회원가입 (UC1). 검사 뒤 같은 아이디가 동시에 가입되면 DB 유일 제약으로 막는다. */
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
        Member member = new Member(acc, Member.hashPassword(pw), nm, addr, MemberManagementAPI.normalizeContact(phone).orElseThrow(),
                memberType);
        try {
            return new SignUpResult(dataAccess.saveMember(member), Map.of());
        } catch (DataIntegrityViolationException e) {
            return new SignUpResult(null, Map.of("account", "이미 사용 중인 아이디입니다."));
        }
    }

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
        } else if (MemberManagementAPI.normalizeContact(contact).isEmpty()) {
            errors.put("contact", "휴대전화 번호 형식이 아닙니다. 예: 010-1234-5678");
        }
        return errors;
    }

    private static String tooLong(int max, String value) {
        return max + "자 이하로 입력해 주세요 (지금 " + value.length() + "자).";
    }

    /** 로그인 (UC2). 아이디는 정확히 일치해야 한다. */
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

    @Transactional(readOnly = true)
    public Map<Long, String> namesByIds(Collection<Long> ids) {
        Map<Long, String> map = new HashMap<>();
        dataAccess.findMembers(ids).forEach(m -> map.put(m.getMemberId(), m.getName()));
        return map;
    }

    private static String strip(String s) {
        return s == null ? "" : s.strip();
    }
}
