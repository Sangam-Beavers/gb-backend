package com.gb.wallet.global.client;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/**
 * 개발(dev) 프로파일용 {@link MemberClient} — <b>fixture 우선 + 미스만 Real 위임</b> 데코레이터.
 *
 * <p>FIXTURES 5명(개발 DB의 wallets.user_public_id 시드와 1:1)은 member-service에 실존하지 않으므로
 * 즉시 고정 데이터를 반환해 기존 dev 시나리오(시드 거래의 수신자 표시, fixture 이메일 검증)를 유지한다.
 * fixture에 없는 id/이메일(실회원)만 {@link RealMemberClient} 로직에 위임한다 — dev에서도 실회원
 * 최근 송금 수신자 닉네임 표시와 validate-member(실회원 간 송금 검증)가 동작한다.
 *
 * <p>실패 정책은 위임받는 Real과 동일(메서드별 분리): {@link #getMember}=fail-open("Unknown" 폴백),
 * {@link #findByEmail}=fail-fast(404 MEMBER4001→empty, 그 외 COMMON5000 전파 — 본 클래스가 삼키지 않음).
 *
 * <p>Real 인스턴스는 빈이 아니라 내부 생성이다 — dev 프로파일에서 {@link MemberClient} 빈이 본 클래스
 * 하나뿐이도록(같은 인터페이스 빈 2개 주입 충돌 방지). stage는 {@link RealMemberClient}가 직접 빈으로
 * 등록된다(기존 Mock의 dev·stage 점유에서 의도적으로 변경). test 프로파일은 어떤 구현체도 등록하지
 * 않고 단위는 {@code @Mock}, 컨텍스트 로딩은 {@code @MockitoBean}으로 가린다(현행 유지).
 */
@Component
@Profile("dev")
public class DevMemberClient implements MemberClient {

    /** 개발용 고정 회원 데이터. 키는 개발 DB wallets.user_public_id 시드와 1:1로 맞춰 사용한다. */
    static final Map<String, MemberInfo> FIXTURES = Map.of(
            "11111111-1111-1111-1111-111111111111",
                    new MemberInfo("11111111-1111-1111-1111-111111111111", "linh@example.com",
                            "Nguyen Thi Linh", "Linh", "VN", true),
            "22222222-2222-2222-2222-222222222222",
                    new MemberInfo("22222222-2222-2222-2222-222222222222", "maria@example.com",
                            "Maria Santos", "Maria", "PH", true),
            "33333333-3333-3333-3333-333333333333",
                    new MemberInfo("33333333-3333-3333-3333-333333333333", "hieu@example.com",
                            "Tran Van Hieu", "Hieu", "VN", false),
            "44444444-4444-4444-4444-444444444444",
                    new MemberInfo("44444444-4444-4444-4444-444444444444", "john@example.com",
                            "John Doe", "John", "KR", true),
            "55555555-5555-5555-5555-555555555555",
                    new MemberInfo("55555555-5555-5555-5555-555555555555", "sun@example.com",
                            "Park Sun", "Sun", "PH", false));

    private final MemberClient delegate;

    @Autowired
    public DevMemberClient(
            RestClient memberRestClient,
            @Value("${member.api.base-url}") String memberApiBaseUrl) {
        this(new RealMemberClient(memberRestClient, memberApiBaseUrl));
    }

    /** 테스트용 — 위임 대상을 직접 주입한다(HTTP 없이 fixture/위임 분기 검증). */
    DevMemberClient(MemberClient delegate) {
        this.delegate = delegate;
    }

    @Override
    public MemberInfo getMember(String userPublicId) {
        MemberInfo fixture = FIXTURES.get(userPublicId);
        return fixture != null ? fixture : delegate.getMember(userPublicId);
    }

    @Override
    public Map<String, MemberInfo> getMembers(Collection<String> userPublicIds) {
        // fixture 히트를 분리하고, 미스만 모아 Real에 1회 배치 위임 후 merge(인터페이스 배치 계약 유지 —
        // 요청한 모든 id가 키로 포함된다: 히트=fixture, 미스=Real 결과(누락·장애는 Real이 폴백으로 채움)).
        // community DevMemberClient.getMembers와 동일 구조.
        List<String> ids = userPublicIds.stream().distinct().toList();
        Map<String, MemberInfo> result = new HashMap<>();
        List<String> misses = new ArrayList<>();
        for (String id : ids) {
            MemberInfo fixture = FIXTURES.get(id);
            if (fixture != null) {
                result.put(id, fixture);
            } else {
                misses.add(id);
            }
        }
        if (!misses.isEmpty()) {
            result.putAll(delegate.getMembers(misses));
        }
        return result;
    }

    @Override
    public Optional<MemberInfo> findByEmail(String email) {
        // fixture 이메일 히트(대소문자 무시 — 기존 Mock 동작 유지)는 즉시 반환, 미스는 Real에 위임.
        // 위임 결과가 empty면 진짜 "없는 회원"(404 MEMBER4001)이고, 장애는 Real이 COMMON5000으로
        // fail-fast하므로 여기서 흡수하지 않는다(검증 용도 — 조용한 가짜 데이터 금지).
        Optional<MemberInfo> fixture = FIXTURES.values().stream()
                .filter(m -> email != null && email.equalsIgnoreCase(m.email()))
                .findFirst();
        if (fixture.isPresent()) {
            return fixture;
        }
        return delegate.findByEmail(email);
    }
}
