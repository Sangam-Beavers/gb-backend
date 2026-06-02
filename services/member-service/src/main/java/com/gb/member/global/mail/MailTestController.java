package com.gb.member.global.mail;

import com.gb.common.response.ApiResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * ⚠️ 임시 발송 검증용 컨트롤러. 메일 발송(Gmail SMTP)이 사내망에서 실제로 나가는지 확인하는 용도다.
 *
 * <p><b>검증 완료 후 삭제 예정.</b> 비밀번호 재설정·가입 인증 기능(2단계) 구현 시 이 컨트롤러는 제거하고,
 * MailSender를 각 기능 서비스에서 호출한다. (운영에 임시 발송 엔드포인트가 남으면 안 됨)
 */
@Tag(name = "MailTest", description = "[임시] 메일 발송 검증용 — 검증 후 삭제")
@RestController
@RequiredArgsConstructor
public class MailTestController {

    private final EmailSender emailSender;

    @PostMapping("/api/v1/mail/test")
    @Operation(summary = "[임시] 테스트 메일 발송", description = "지정 주소로 테스트 메일 1통 발송. SMTP 연동 확인용.")
    public ApiResponse<String> sendTest(@RequestParam String to) {
        emailSender.send(to, "[gb-backend] 메일 발송 테스트", "메일 발송이 정상 동작합니다. (SMTP 연동 확인용)");
        return ApiResponse.success("발송 시도 완료: " + to);
    }
}
