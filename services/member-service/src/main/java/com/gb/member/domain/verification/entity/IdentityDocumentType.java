package com.gb.member.domain.verification.entity;

import java.util.regex.Pattern;

/**
 * 신분증 유형 + 유형별 번호 형식(정규식) 검증.
 *
 * <p>이 앱은 <b>외국인 근로자</b> 대상이므로 주 신분증은 <b>외국인등록증(ALIEN_REGISTRATION)</b>이며,
 * 외국인등록번호 형식을 가장 정밀하게 검증한다. 실 신원확인 API 대신 형식(정규식) 검증으로 대체한다(데모).
 *
 * <p>각 상수가 자신의 형식 규칙(Pattern)을 들고 있어, 검증 책임이 유형과 한 곳에 모인다.
 * API 필드명은 {@code identity_document_type}, DB 컬럼은 {@code document_type}이다(database.md §user_verifications).
 */
public enum IdentityDocumentType {

    /**
     * 외국인등록증(외국인등록번호). 형식: {@code YYMMDD-Sxxxxxx}
     * <ul>
     *   <li>앞 6자리 = 생년월일(YYMMDD)</li>
     *   <li>뒤 7자리 = 성별·국적 구분 첫자리(외국인은 5~8) + 일련번호 6자리</li>
     * </ul>
     * 예: {@code 990101-5678901}
     */
    ALIEN_REGISTRATION(Pattern.compile("^\\d{6}-[5-8]\\d{6}$")),

    /**
     * 여권번호. 첫 글자 영문 대문자 + 영숫자 7~8자(한국 여권 M + 8숫자, 국제 여권 포함).
     * 예: {@code M12345678}
     */
    PASSPORT(Pattern.compile("^[A-Z][A-Z0-9]{7,8}$")),

    /**
     * 본국 신분증. 국가별 체계가 달라 영숫자/하이픈 6~20자로 느슨하게 검증한다.
     */
    NATIONAL_ID(Pattern.compile("^[A-Za-z0-9-]{6,20}$"));

    private final Pattern pattern;

    IdentityDocumentType(Pattern pattern) {
        this.pattern = pattern;
    }

    /** 신분증 번호가 이 유형의 형식에 맞는지 검증한다. */
    public boolean matches(String documentNumber) {
        return documentNumber != null && pattern.matcher(documentNumber).matches();
    }
}
