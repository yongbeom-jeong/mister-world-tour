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

    Pattern CONTACT_RE = Pattern.compile("^(01[016789])-?(\\d{3,4})-?(\\d{4})$");

    record Contact(String name, String contact) {
    }

    /** 실패하면 member 는 null, errors 에 항목별 안내. */
    record SignUpResult(Member member, Map<String, String> errors) {
        public boolean isSuccess() {
            return member != null;
        }
    }

    record LoginInfo(String account, String password) implements Serializable {
    }

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

    /** 가입 입력 검사만 한다 (저장하지 않음). */
    Map<String, String> checkSignUp(Role memberType, String account, String password, String name, String address,
                                    String contact);

    /** 가입 저장 중 오류 뒤 저장 여부를 다시 확인할 때. */
    Optional<Member> findMemberJoinedSince(String account, OffsetDateTime since);

    Map<Long, String> namesByIds(Collection<Long> ids);

    Optional<Contact> getContact(long memberId);
}
