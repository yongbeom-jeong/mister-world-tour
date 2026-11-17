package com.misterworldtour.members;

import java.io.Serializable;
import java.util.Map;

/** Member Management 가 User Interface 에 제공하는 회원가입·로그인 기능 (설계 4.1 MemberManagementAPI). */
public interface MemberManagementAPI {

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
}
