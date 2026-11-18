package com.misterworldtour.ui;

import com.misterworldtour.members.MemberManagementAPI;
import com.misterworldtour.members.Role;

import org.springframework.stereotype.Component;
import org.springframework.validation.Errors;

import java.util.Map;
import java.util.Objects;

/**
 * 회원가입 화면 입력 검사. 화면에만 있는 항목(비밀번호 확인, 직원 인증 코드)은 여기서 보고,
 * 회원 정보 자체(필수·형식·아이디 중복·비밀번호 정책)는 {@link MemberManagementAPI} 가 검사한 결과를 화면 항목에 옮긴다.
 */
@Component
public class SignUpFormValidator {

    static final String REQUIRED = "필수 항목입니다.";

    /** MemberManagementAPI 의 항목 이름 → 화면 입력 이름. */
    private static final Map<String, String> FIELDS = Map.of("account", "username", "password", "password1");

    private final MemberManagementAPI memberManager;
    private final UiProperties properties;

    SignUpFormValidator(MemberManagementAPI memberManager, UiProperties properties) {
        this.memberManager = memberManager;
        this.properties = properties;
    }

    /** 앞뒤 공백을 정리하고 화면 전용 항목을 검사한다. 가입 정보 검사는 하지 않는다. */
    public void validateFormOnly(SignUpForm form, Errors errors) {
        form.setUsername(strip(form.getUsername()));
        form.setName(strip(form.getName()));
        form.setAddress(strip(form.getAddress()));
        form.setContact(strip(form.getContact()));
        form.setStaffCode(strip(form.getStaffCode()));
        form.setPassword1(Objects.requireNonNullElse(form.getPassword1(), ""));
        form.setPassword2(Objects.requireNonNullElse(form.getPassword2(), ""));

        if (form.getPassword2().isEmpty()) {
            errors.rejectValue("password2", "required", REQUIRED);
        } else if (!form.getPassword1().isEmpty() && !form.getPassword1().equals(form.getPassword2())) {
            errors.rejectValue("password2", "mismatch", "비밀번호가 일치하지 않습니다.");
        }
        if (roleOf(form.getMemberType()) == Role.STAFF && !properties.staffSignupCode().equals(form.getStaffCode())) {
            errors.rejectValue("staffCode", "invalid", "직원 인증 코드가 올바르지 않습니다.");
        }
    }

    /**
     * 화면 전용 오류가 있을 때 가입 정보 검사 결과도 함께 보여 준다 (한 번에 고칠 수 있게).
     * 비밀번호가 확인과 다르면 비밀번호 정책 안내는 생략한다.
     */
    public void addMemberErrors(SignUpForm form, Errors errors) {
        Map<String, String> found = memberManager.checkSignUp(roleOf(form.getMemberType()), form.getUsername(),
                form.getPassword1(), form.getName(), form.getAddress(), form.getContact());
        boolean mismatch = errors.hasFieldErrors("password2") && !form.getPassword2().isEmpty();
        found.forEach((field, message) -> {
            if (!(mismatch && field.equals("password") && !form.getPassword1().isEmpty())) {
                reject(errors, field, message);
            }
        });
    }

    /** MemberManagementAPI 가 돌려준 항목별 안내를 화면 입력 칸에 붙인다. 비밀번호 정책은 줄마다 따로 표시한다. */
    public static void reject(Errors errors, String field, String message) {
        String formField = FIELDS.getOrDefault(field, field);
        for (String line : message.split("\n")) {
            errors.rejectValue(formField, field, line);
        }
    }

    static Role roleOf(String value) {
        for (Role r : Role.values()) {
            if (r.name().equals(value)) {
                return r;
            }
        }
        return null;
    }

    private static String strip(String value) {
        return value == null ? "" : value.strip();
    }
}
