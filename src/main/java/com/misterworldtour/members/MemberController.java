package com.misterworldtour.members;

import com.misterworldtour.common.Messages;
import com.misterworldtour.common.Messages.Level;
import jakarta.servlet.http.HttpSession;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.time.OffsetDateTime;
import java.util.Optional;

/** UC1 회원가입, UC2 로그인 화면. 로그인 처리 자체는 Spring Security 가 한다 ({@code SecurityConfig}). */
@Controller
@RequestMapping("/members")
class MemberController {

    private static final Logger log = LoggerFactory.getLogger(MemberController.class);

    private final MemberService memberService;
    private final SignUpFormValidator validator;

    MemberController(MemberService memberService, SignUpFormValidator validator) {
        this.memberService = memberService;
        this.validator = validator;
    }

    @ModelAttribute("roles")
    Role[] roles() {
        return Role.values();
    }

    @GetMapping("/signup/")
    String signUpPage(@AuthenticationPrincipal MemberPrincipal current, Model model) {
        if (current != null) {
            return "redirect:/";
        }
        model.addAttribute("form", new SignUpForm());
        return "members/signup";
    }

    @PostMapping("/signup/")
    String signUp(@AuthenticationPrincipal MemberPrincipal current, @ModelAttribute("form") SignUpForm form,
                  BindingResult errors, Model model, RedirectAttributes redirect) {
        if (current != null) {
            return "redirect:/";
        }
        validator.validate(form, errors);
        if (errors.hasErrors()) {
            return "members/signup";
        }
        OffsetDateTime started = OffsetDateTime.now();
        try {
            memberService.signUp(SignUpFormValidator.roleOf(form.getMemberType()), form.getUsername(),
                    form.getPassword1(), form.getName(), form.getAddress(), form.getContact());
        } catch (MemberService.DuplicateAccountException e) {
            errors.rejectValue("username", "duplicate", "이미 사용 중인 아이디입니다.");
            return "members/signup";
        } catch (DataAccessException e) {
            // UC1 5a: 저장 결과를 확인해 안내 (가입 완료로 잘못 안내하지 않음)
            log.error("회원가입 저장 중 DB 오류 username={}", form.getUsername(), e);
            Optional<Boolean> joined = joinedAfterError(form.getUsername(), started);
            if (joined.orElse(false)) {
                Messages.flash(redirect, Level.WARNING, "처리 중 오류가 있었지만 가입은 완료되었습니다. 로그인해 주세요.");
                return "redirect:/members/login/";
            }
            if (joined.isPresent()) {
                Messages.now(model, Level.ERROR, "일시적인 오류로 가입되지 않았습니다. 잠시 후 다시 가입해 주세요.");
            } else {
                Messages.now(model, Level.ERROR, "일시적인 오류로 가입 여부를 확인하지 못했습니다. "
                        + "잠시 후 같은 아이디로 로그인해 보고, 안 되면 다시 가입해 주세요.");
            }
            return "members/signup";
        }
        Messages.flash(redirect, Level.SUCCESS, "회원가입이 완료되었습니다. 로그인해 주세요.");
        return "redirect:/members/login/";
    }

    /** 가입 저장 오류 뒤 가입 여부. true(가입됨) / false(가입 안 됨) / empty(확인 불가). */
    private Optional<Boolean> joinedAfterError(String username, OffsetDateTime since) {
        try {
            return Optional.of(memberService.findMemberJoinedSince(username, since).isPresent());
        } catch (DataAccessException e) {
            log.error("가입 여부 확인 실패 username={}", username, e);
            return Optional.empty();
        }
    }

    @GetMapping("/login/")
    String loginPage(Authentication authentication, @RequestParam(required = false) String next,
                     HttpSession session, Model model) {
        if (authentication != null && authentication.isAuthenticated()
                && authentication.getPrincipal() instanceof MemberPrincipal) {
            return "redirect:/";
        }
        Object failure = session.getAttribute(LoginHandlers.LOGIN_FAILURE);
        session.removeAttribute(LoginHandlers.LOGIN_FAILURE);
        String username = "";
        if (failure instanceof LoginHandlers.LoginFailure f) {
            model.addAttribute("loginError", f.blank() ? "blank" : "bad");
            username = f.username();
        }
        model.addAttribute("username", username);
        model.addAttribute("next", LoginHandlers.isSafeNext(next) ? next : "");
        return "members/login";
    }
}
