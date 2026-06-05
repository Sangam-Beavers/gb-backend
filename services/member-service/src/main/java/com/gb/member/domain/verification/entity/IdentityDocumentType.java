package com.gb.member.domain.verification.entity;

import java.util.Locale;
import java.util.regex.Pattern;

/**
 * 신분증 유형 + 유형별 번호 형식(정규식) 검증.
 *
 * <p>이슈 #152/#108 정리:
 * <ul>
 *   <li>{@link #ALIEN_REGISTRATION} — 한국 거주 외국인 노동자(앱 주 사용자).
 *   <li>{@link #NATIONAL_ID_KR}/{@link #NATIONAL_ID_US}/{@link #NATIONAL_ID_VN}/{@link #NATIONAL_ID_PH}
 *       — 본국 거주 가족(INTERNAL_TRANSFER 수령자). 송금 두 방향 사용자 모두 커버한다.
 *   <li>여권(PASSPORT)·일반 본국 신분증(NATIONAL_ID)은 본 단계에서 제거 — 위 5종으로 사용자 분류가 끝난다.
 * </ul>
 *
 * <p>실 신원확인 API 대신 형식(정규식) 검증으로 대체한다(데모). 운영 출시 전 실 KYC(NICE/SCI/국가별 위변조)
 * 도입은 별 이슈로 분리.
 *
 * <p>각 상수가 자신의 형식 규칙(Pattern)을 들고 있어, 검증 책임이 유형과 한 곳에 모인다.
 * API 필드명은 {@code identity_document_type}, DB 컬럼은 {@code document_type}이다(database.md §user_verifications).
 */
public enum IdentityDocumentType {

    /**
     * 외국인등록증(외국인등록번호). 한국 거주 외국인 노동자의 표준 신분증.
     * 형식: {@code YYMMDD-Sxxxxxx}
     * <ul>
     *   <li>앞 6자리 = 생년월일(YYMMDD)</li>
     *   <li>뒤 7자리 = 성별·국적 구분 첫자리(외국인 5~8) + 일련번호 6자리</li>
     * </ul>
     * 예: {@code 990101-5678901}
     */
    ALIEN_REGISTRATION(Pattern.compile("^\\d{6}-[5-8]\\d{6}$"), false),

    /**
     * 한국 주민등록번호 (RRN). 한국 시민 — 7번째 자리가 0~4 또는 9.
     * 형식: {@code YYMMDD-Sxxxxxx} (외국인등록번호와 형식 동일하나 7번째 자리 분기).
     * 예: {@code 900101-1234567}
     */
    NATIONAL_ID_KR(Pattern.compile("^\\d{6}-[0-49]\\d{6}$"), false),

    /**
     * 미국 SSN (Social Security Number). 형식: {@code XXX-XX-XXXX}.
     * 영역코드 000/666/9xx 금지, 그룹코드 00 금지, 일련번호 0000 금지(SSA 규정).
     * 예: {@code 123-45-6789}
     */
    NATIONAL_ID_US(
            Pattern.compile("^(?!000|666|9\\d{2})\\d{3}-(?!00)\\d{2}-(?!0000)\\d{4}$"), false),

    /**
     * 베트남 CCCD (Căn cước công dân). 12자리 숫자(하이픈 없음).
     * 첫 3자리=지역코드, 4번째=성별, 5~6=출생연도, 나머지=일련번호.
     * 예: {@code 079199012345}
     */
    NATIONAL_ID_VN(Pattern.compile("^\\d{12}$"), false),

    /**
     * 필리핀 PhilSys PCN (PhilSys Card Number). 16자리 영숫자 — 카드 표면 공개 번호.
     * PSN(12자리)은 법적 비공개(RA 11055)라 받지 않는다. 대문자만 허용 — 검증 전에 입력값을
     * {@link #matches}에서 대문자로 정규화한다(소문자 입력도 허용 → 영구 저장은 대문자).
     * 예: {@code A1B2C3D4E5F6G7H8}
     */
    NATIONAL_ID_PH(Pattern.compile("^[A-Z0-9]{16}$"), true);

    private final Pattern pattern;
    /** true면 {@link #matches} 검증 전에 입력값을 대문자로 정규화한다(현재 필리핀 PCN). */
    private final boolean uppercaseBeforeMatch;

    IdentityDocumentType(Pattern pattern, boolean uppercaseBeforeMatch) {
        this.pattern = pattern;
        this.uppercaseBeforeMatch = uppercaseBeforeMatch;
    }

    /** 신분증 번호가 이 유형의 형식에 맞는지 검증한다. */
    public boolean matches(String documentNumber) {
        if (documentNumber == null) {
            return false;
        }
        return pattern.matcher(normalize(documentNumber)).matches();
    }

    /**
     * 영구 저장/검증에 쓸 정규화된 번호. 필리핀 PCN처럼 대소문자 구분이 없는 형식은 대문자로 통일해
     * 검색·중복비교·암복호 결과가 입력 케이스에 흔들리지 않게 한다. 다른 유형은 입력 그대로 반환.
     */
    public String normalize(String documentNumber) {
        if (documentNumber == null) {
            return null;
        }
        return uppercaseBeforeMatch ? documentNumber.toUpperCase(Locale.ROOT) : documentNumber;
    }
}
