package com.eventfulcommerce.user.service

import io.github.oshai.kotlinlogging.KotlinLogging
import jakarta.mail.internet.MimeMessage
import org.springframework.beans.factory.annotation.Value
import org.springframework.mail.javamail.JavaMailSender
import org.springframework.mail.javamail.MimeMessageHelper
import org.springframework.stereotype.Service

private val logger = KotlinLogging.logger {}

@Service
class EmailService(
    private val mailSender: JavaMailSender,
    @Value("\${spring.mail.username}") private val fromEmail: String
) {

    fun sendPasswordResetEmail(toEmail: String, token: String) {
        val subject = "[EventfulCommerce] 비밀번호 재설정"
        val html = """
            <div style="font-family: Arial, sans-serif; max-width: 600px; margin: 0 auto;">
                <h2 style="color: #333;">비밀번호 재설정 요청</h2>
                <p>아래 토큰을 사용하여 비밀번호를 재설정하세요.</p>
                <div style="background-color: #f4f4f4; padding: 16px; border-radius: 6px;
                            font-family: monospace; font-size: 14px; word-break: break-all;">
                    $token
                </div>
                <p style="color: #666; font-size: 14px;">토큰은 <strong>30분</strong>간 유효합니다.</p>
                <p style="color: #999; font-size: 12px;">
                    본인이 요청하지 않은 경우 이 이메일을 무시하세요.
                </p>
            </div>
        """.trimIndent()

        try {
            val message: MimeMessage = mailSender.createMimeMessage()
            val helper = MimeMessageHelper(message, true, "UTF-8")
            helper.setFrom(fromEmail)
            helper.setTo(toEmail)
            helper.setSubject(subject)
            helper.setText(html, true)
            mailSender.send(message)
            logger.info { "비밀번호 재설정 이메일 전송 완료: to=$toEmail" }
        } catch (e: Exception) {
            logger.error(e) { "비밀번호 재설정 이메일 전송 실패: to=$toEmail" }
            throw RuntimeException("이메일 전송에 실패했습니다. 잠시 후 다시 시도해주세요.")
        }
    }
}
