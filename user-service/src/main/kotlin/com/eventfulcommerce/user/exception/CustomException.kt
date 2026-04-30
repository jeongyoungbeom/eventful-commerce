package com.eventfulcommerce.user.exception

/**
 * 사용자 정의 예외 기본 클래스
 */
sealed class CustomException(
    message: String,
    cause: Throwable? = null
) : RuntimeException(message, cause)

/**
 * 중복된 이메일
 */
class DuplicateEmailException(email: String) :
    CustomException("이미 사용 중인 이메일입니다: $email")

/**
 * 중복된 사업자 등록번호
 */
class DuplicateBusinessNumberException(businessNumber: String) :
    CustomException("이미 등록된 사업자 등록번호입니다: $businessNumber")

/**
 * 사용자를 찾을 수 없음
 */
class UserNotFoundException(userId: String) :
    CustomException("사용자를 찾을 수 없습니다: $userId")

/**
 * 잘못된 인증 정보
 */
class InvalidCredentialsException :
    CustomException("이메일 또는 비밀번호가 일치하지 않습니다")

/**
 * 계정 잠금
 */
class AccountLockedException(unlockTime: String) :
    CustomException("계정이 잠겼습니다. 잠금 해제 시간: $unlockTime")

/**
 * 유효하지 않은 토큰
 */
class InvalidTokenException(message: String = "유효하지 않은 토큰입니다") :
    CustomException(message)

/**
 * 만료된 토큰
 */
class ExpiredTokenException :
    CustomException("만료된 토큰입니다")
