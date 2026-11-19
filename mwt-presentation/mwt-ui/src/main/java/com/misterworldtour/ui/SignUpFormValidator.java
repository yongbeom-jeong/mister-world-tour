package com.misterworldtour.ui;

import com.misterworldtour.members.MemberManagementAPI;
import com.misterworldtour.members.Role;

import org.springframework.stereotype.Component;
import org.springframework.validation.Errors;

import java.util.Map;
import java.util.Objects;

/**
 * 회원가입 화면 입력 검사. 비밀번호 확인·직원 인증 코드만 여기서 보고,
 * 회원 정보 검사는 {@link MemberManagementAPI} 결과를 화면 항목에 옮긴다.
 */
@Component
public class SignUpFormValidator {

    static final String REQUIRED = "필수 항목입니다.";

    private static final Map<String, String> FIELDS = Map.of("account", "username", "password", "password1");

    private final MemberManagementAPI memberManager;
    private final UiProperties properties;

    SignUpFormValidator(MemberManagementAPI memberManager, UiProperties properties) {
        this.memberManager = memberManager;
        this.properties = properties;
    }

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

    /** 비밀번호가 확인과 다르면 비밀번호 정책 안내는 생략한다. */
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
