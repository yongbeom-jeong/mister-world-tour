package com.misterworldtour.members;

import java.io.Serializable;
import java.time.OffsetDateTime;
import java.util.Collection;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Member Management 가 User Interface 에 제공하는 회원가입·로그인 기능 (설계 4.1 MemberManagementAPI). */
public interface MemberManagementAPI {

    /** 휴대전화 번호 형식 (하이픈은 생략 가능). */
    Pattern CONTACT_RE = Pattern.compile("^(01[016789])-?(\\d{3,4})-?(\\d{4})$");

    /** 문자 발송용 성명·연락처. */
    record Contact(String name, String contact) {
    }

    /** 회원가입 결과. 실패하면 member 는 null 이고 errors 에 항목별(account·password 등) 안내가 있다. */
    record SignUpResult(Member member, Map<String, String> errors) {
        public boolean isSuccess() {
            return member != null;
        }
    }

    /** 로그인 입력 (아이디·비밀번호). */
    record LoginInfo(String account, String password) implements Serializable {
    }

    /** 로그인 결과. 성공하면 loginSession 이 있다. */
    record LoginResult(LoginSession loginSession) {
        public boolean isSuccess() {
            return loginSession != null;
        }
    }

    SignUpResult signUp(Role memberType, String account, String password, String name, String address, String contact);

    LoginResult logIn(LoginInfo loginInfo);

    /** 휴대전화 번호를 010-1234-5678 형식으로. 형식이 아니면 empty. */
    static Optional<String> normalizeContact(String value) {
        Matcher m = CONTACT_RE.matcher(value == null ? "" : value.strip());
        if (!m.matches()) {
            return Optional.empty();
        }
        return Optional.of(m.group(1) + "-" + m.group(2) + "-" + m.group(3));
    }

    /** 가입 입력을 검사해 항목별 안내를 돌려준다 (저장하지 않음). 화면이 자체 오류와 함께 보여 줄 때 쓴다. */
    Map<String, String> checkSignUp(Role memberType, String account, String password, String name, String address,
                                    String contact);

    /** since 이후 이 아이디로 가입된 회원 (가입 저장 여부를 다시 확인할 때). */
    Optional<Member> findMemberJoinedSince(String account, OffsetDateTime since);

    /** 회원 번호 → 이름 (직원 화면 표시용). */
    Map<Long, String> namesByIds(Collection<Long> ids);

    /** 문자 발송용 성명·연락처. */
    Optional<Contact> getContact(long memberId);
}
