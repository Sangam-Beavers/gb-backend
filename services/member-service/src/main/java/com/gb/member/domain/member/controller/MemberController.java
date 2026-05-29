package com.gb.member.domain.member.controller;

import com.gb.member.domain.member.dto.SignupRequest;
import com.gb.member.domain.member.dto.SignupResponse;
import com.gb.member.domain.member.service.MemberService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/auth")      // ← 여기
@Tag(name = "Auth", description = "인증 API")
public class MemberController {

    private final MemberService memberService;

    @PostMapping("/register")        // ← 여기
    @Operation(summary = "회원가입", description = "이메일, 비밀번호, 이름, 닉네임, 국적, 주 사용 언어로 회원가입")
    public ResponseEntity<SignupResponse> signup(@Valid @RequestBody SignupRequest request) {
        SignupResponse response = memberService.signup(request);
        return ResponseEntity.ok(response);
    }
}