package com.misterworldtour.members;

import com.misterworldtour.common.MwtProperties;
import org.springframework.stereotype.Component;
import org.springframework.validation.Errors;

import java.util.Objects;

/**
 * 회원가입 입력 검사 (Django 판 SignUpForm 의 clean_* / clean 과 같은 순서·규칙).
 * 통과하면 입력값을 정리한다: 앞뒤 공백 제거, 연락처는 010-1234-5678 형식.
 */
@Component
public class SignUpFormValidator {

    static final String REQUIRED = "필수 항목입니다.";

    private final MemberService memberService;
    private final PasswordPolicy passwordPolicy;
    private final MwtProperties properties;

    SignUpFormValidator(MemberService memberService, PasswordPolicy passwordPolicy, MwtProperties properties) {
        this.memberService = memberService;
        this.passwordPolicy = passwordPolicy;
        this.properties = properties;
    }

    public void validate(SignUpForm form, Errors errors) {
        form.setUsername(strip(form.getUsername()));
        form.setName(strip(form.getName()));
        form.setAddress(strip(form.getAddress()));
        form.setContact(strip(form.getContact()));
        form.setStaffCode(strip(form.getStaffCode()));
        form.setPassword1(Objects.requireNonNullElse(form.getPassword1(), ""));
        form.setPassword2(Objects.requireNonNullElse(form.getPassword2(), ""));

        Role role = roleOf(form.getMemberType());
        if (role == null) {
            errors.rejectValue("memberType", "invalid", "회원 유형을 골라 주세요.");
        }

        if (required(errors, "username", form.getUsername()) && maxLength(errors, "username", form.getUsername(), 20)) {
            if (!MemberService.USERNAME_RE.matcher(form.getUsername()).matches()) {
                errors.rejectValue("username", "format", "아이디는 영문·숫자·밑줄(_)로 4~20자여야 합니다.");
            } else if (memberService.usernameTaken(form.getUsername())) {
                errors.rejectValue("username", "duplicate", "이미 사용 중인 아이디입니다.");
            }
        }
        boolean hasPw1 = required(errors, "password1", form.getPassword1());
        boolean hasPw2 = required(errors, "password2", form.getPassword2());
        required(errors, "name", form.getName());
        maxLength(errors, "name", form.getName(), 50);
        required(errors, "address", form.getAddress());
        maxLength(errors, "address", form.getAddress(), 200);
        if (required(errors, "contact", form.getContact()) && maxLength(errors, "contact", form.getContact(), 20)) {
            MemberService.normalizeContact(form.getContact()).ifPresentOrElse(
                    form::setContact,
                    () -> errors.rejectValue("contact", "format", "휴대전화 번호 형식이 아닙니다. 예: 010-1234-5678"));
        }

        if (hasPw1 && hasPw2 && !form.getPassword1().equals(form.getPassword2())) {
            errors.rejectValue("password2", "mismatch", "비밀번호가 일치하지 않습니다.");
        } else if (hasPw1) {
            for (String message : passwordPolicy.validate(form.getPassword1(), form.getUsername(), form.getName())) {
                errors.rejectValue("password1", "policy", message);
            }
        }
        if (role == Role.STAFF && !properties.staffSignupCode().equals(form.getStaffCode())) {
            errors.rejectValue("staffCode", "invalid", "직원 인증 코드가 올바르지 않습니다.");
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

    private static boolean required(Errors errors, String field, String value) {
        if (value.isEmpty()) {
            errors.rejectValue(field, "required", REQUIRED);
            return false;
        }
        return true;
    }

    private static boolean maxLength(Errors errors, String field, String value, int max) {
        if (value.length() > max) {
            errors.rejectValue(field, "maxLength", max + "자 이하로 입력해 주세요 (지금 " + value.length() + "자).");
            return false;
        }
        return true;
    }
}
