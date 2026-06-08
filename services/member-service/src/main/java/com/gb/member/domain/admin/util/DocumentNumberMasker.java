package com.gb.member.domain.admin.util;

import com.gb.member.domain.verification.entity.IdentityDocumentType;

/**
 * 관리자 응답에 신분증 번호를 노출할 때 사용하는 마스킹 헬퍼.
 *
 * <p>conventions.md §15 PII — 평문 노출 절대 금지. 컬럼은 AES-256-GCM 으로 저장돼 엔티티 필드에선 평문이지만,
 * 관리자에게도 평문은 노출하지 않고 마스킹본만 전달한다.
 *
 * <p>패턴:
 * <ul>
 *     <li>ALIEN_REGISTRATION / NATIONAL_ID_KR — {@code YYMMDD-Sxxxxxx} → {@code YYMMDD-S******}</li>
 *     <li>NATIONAL_ID_US — {@code XXX-XX-XXXX} → {@code XXX-XX-****}</li>
 *     <li>NATIONAL_ID_VN — 12자리 → 앞 4 + {@code ********}</li>
 *     <li>NATIONAL_ID_PH — 16자리 → 앞 4 + {@code ************}</li>
 *     <li>그 외(미상 type 또는 null) — 끝 4자리만 노출 ({@code **********1234})</li>
 * </ul>
 */
public final class DocumentNumberMasker {

    private DocumentNumberMasker() {
    }

    public static String mask(IdentityDocumentType type, String documentNumber) {
        if (documentNumber == null || documentNumber.isBlank()) {
            return null;
        }
        if (type == null) {
            return tailMask(documentNumber, 4);
        }
        switch (type) {
            case ALIEN_REGISTRATION:
            case NATIONAL_ID_KR:
                // YYMMDD-S xxxxxx → 앞 6자 + "-" + 1자 + 6자 별표
                if (documentNumber.length() >= 8 && documentNumber.charAt(6) == '-') {
                    return documentNumber.substring(0, 8) + "******";
                }
                return tailMask(documentNumber, 4);
            case NATIONAL_ID_US:
                // XXX-XX-XXXX
                if (documentNumber.length() == 11) {
                    return documentNumber.substring(0, 7) + "****";
                }
                return tailMask(documentNumber, 4);
            case NATIONAL_ID_VN:
                // 12자리
                if (documentNumber.length() == 12) {
                    return documentNumber.substring(0, 4) + "********";
                }
                return tailMask(documentNumber, 4);
            case NATIONAL_ID_PH:
                if (documentNumber.length() == 16) {
                    return documentNumber.substring(0, 4) + "************";
                }
                return tailMask(documentNumber, 4);
            default:
                return tailMask(documentNumber, 4);
        }
    }

    /** 끝 N 자리만 보이게 ********1234 형태로 가린다. */
    private static String tailMask(String value, int visible) {
        if (value.length() <= visible) {
            return repeat("*", value.length());
        }
        return repeat("*", value.length() - visible) + value.substring(value.length() - visible);
    }

    private static String repeat(String s, int n) {
        StringBuilder sb = new StringBuilder(s.length() * n);
        for (int i = 0; i < n; i++) sb.append(s);
        return sb.toString();
    }
}
