package com.misterworldtour.members;

import org.springframework.security.access.prepost.PreAuthorize;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 직원만 쓰는 화면 (서버에서 역할 검사).
 * 로그인하지 않았으면 로그인 화면으로, 고객이면 403 화면으로 보낸다.
 */
@Target({ElementType.TYPE, ElementType.METHOD})
@Retention(RetentionPolicy.RUNTIME)
@Documented
@PreAuthorize("hasRole('STAFF')")
public @interface StaffRoleRequired {
}
