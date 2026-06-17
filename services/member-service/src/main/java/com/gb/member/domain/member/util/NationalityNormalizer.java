package com.gb.member.domain.member.util;

import java.util.Map;

/**
 * 국적 입력값을 정본 코드(ISO 3166-1 alpha-2, 대문자)로 정규화한다.
 *
 * <p>저장 정본은 코드("KR","VN","US"...)다(members.nationality 주석 + admin 통계 라벨 매핑 기준).
 * 클라이언트(앱/CLI/시드)가 한글 이름·영문명·소문자 등으로 보내도 같은 코드로 수렴시켜,
 * 통계에서 같은 나라가 "한국" vs "KR" 처럼 두 그룹으로 갈리는 중복을 막는다.
 *
 * <p><b>정책: 정규화만 한다(거부하지 않음).</b> 별칭은 코드로 매핑하고, 그 외 값은 trim+대문자로 통과시킨다.
 * 외국인 근로자 플랫폼이라 NP/UZ/MM/KH 등 다양한 코드가 정상값이므로 화이트리스트 거부는 하지 않는다.
 * (앱 입력 선택지 제한은 프론트가 담당; 백엔드는 "표기 통일"이 역할.)
 */
public final class NationalityNormalizer {

    private NationalityNormalizer() {
    }

    /**
     * 별칭(한글/영문명) → ISO alpha-2 코드. 조회 시 입력을 소문자로 변환해 비교하므로
     * 영문 키는 소문자로 둔다(한글은 대소문자 개념이 없어 그대로).
     */
    private static final Map<String, String> ALIASES = Map.ofEntries(
            Map.entry("한국", "KR"), Map.entry("대한민국", "KR"), Map.entry("korea", "KR"), Map.entry("kor", "KR"),
            Map.entry("미국", "US"), Map.entry("usa", "US"),
            Map.entry("베트남", "VN"), Map.entry("vietnam", "VN"),
            Map.entry("필리핀", "PH"),
            Map.entry("기타", "ETC"), Map.entry("etc", "ETC"), Map.entry("other", "ETC"),
            // admin 차트가 라벨 매핑하는 그 외 국가의 한글명도 코드로 수렴
            Map.entry("중국", "CN"), Map.entry("캄보디아", "KH"), Map.entry("네팔", "NP"),
            Map.entry("태국", "TH"), Map.entry("인도네시아", "ID"), Map.entry("우즈베키스탄", "UZ"),
            Map.entry("미얀마", "MM"), Map.entry("스리랑카", "LK"), Map.entry("방글라데시", "BD")
    );

    /**
     * 국적 정규화. null/빈값은 그대로 반환(형식 검증은 Bean Validation {@code @NotBlank}가 담당).
     * 별칭이면 코드로, 아니면 trim 후 대문자로(예: "kr"→"KR", "vn"→"VN").
     */
    public static String normalize(String raw) {
        if (raw == null) {
            return null;
        }
        String trimmed = raw.trim();
        if (trimmed.isEmpty()) {
            return trimmed;
        }
        String mapped = ALIASES.get(trimmed.toLowerCase());
        return mapped != null ? mapped : trimmed.toUpperCase();
    }
}
