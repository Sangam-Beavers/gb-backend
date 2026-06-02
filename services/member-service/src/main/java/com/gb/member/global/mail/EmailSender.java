package com.gb.member.global.mail;

import com.gb.common.exception.BusinessException;
import com.gb.common.exception.CommonErrorCode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Component;

/**
 * 메일 발송 컴포넌트. Spring의 {@link JavaMailSender}(Gmail SMTP, application-*.yml의 spring.mail.*)를
 * 래핑해 "제목/본문/수신자"만으로 메일을 보낸다.
 *
 * <p>비밀번호 재설정 링크·가입 인증 메일 등에서 사용한다. 발송 실패(SMTP 연결 불가/인증 실패 등)는
 * 서버 측 문제로 보고 {@link CommonErrorCode#INTERNAL_SERVER_ERROR}로 변환한다(연동 장애 취급 — CLAUDE §6).
 *
 * <p>클래스명은 {@code EmailSender}로 둔다 — Spring Boot 메일 자동설정이 등록하는 빈 이름이 {@code mailSender}라,
 * 클래스명을 {@code MailSender}로 하면 빈 이름 충돌(BeanDefinitionOverrideException)이 난다.
 *
 * <p>현재는 평문(text) 메일만 지원한다. HTML 템플릿이 필요하면 추후 MimeMessage로 확장한다(과도한 추상화 방지).
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class EmailSender {

    private final JavaMailSender javaMailSender;

    /** 발신자 주소. spring.mail.username과 동일해야 Gmail이 거부하지 않는다. */
    @Value("${spring.mail.username}")
    private String from;

    /**
     * 평문 메일을 발송한다.
     *
     * @param to      수신자 이메일
     * @param subject 제목
     * @param body    본문(평문)
     */
    public void send(String to, String subject, String body) {
        try {
            SimpleMailMessage message = new SimpleMailMessage();
            message.setFrom(from);
            message.setTo(to);
            message.setSubject(subject);
            message.setText(body);
            javaMailSender.send(message);
            log.info("메일 발송 성공: to={}, subject={}", maskEmail(to), subject);
        } catch (Exception e) {
            // SMTP 연결 실패·인증 실패·타임아웃 등. 사내망에서 587 차단 시 여기로 떨어진다.
            log.error("메일 발송 실패: to={}, subject={}, msg={}", maskEmail(to), subject, e.getMessage());
            throw new BusinessException(CommonErrorCode.INTERNAL_SERVER_ERROR);
        }
    }

    /**
     * 로그용 이메일 마스킹 — 개인정보(PII)가 평문으로 로그에 남지 않게 한다.
     * 예: {@code abcde@example.com} → {@code ab***@example.com}. 로컬파트 2자 초과만 마스킹한다.
     */
    private String maskEmail(String email) {
        if (email == null || !email.contains("@")) {
            return "***";
        }
        int at = email.indexOf('@');
        String local = email.substring(0, at);
        String domain = email.substring(at);
        if (local.length() <= 2) {
            return "***" + domain;
        }
        return local.substring(0, 2) + "***" + domain;
    }
}
