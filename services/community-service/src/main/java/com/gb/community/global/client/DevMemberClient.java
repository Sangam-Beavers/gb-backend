package com.gb.community.global.client;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/**
 * 개발(dev) 프로파일용 {@link MemberClient} — <b>fixture 우선 + 미스만 Real 위임</b> 데코레이터.
 *
 * <p>과거 {@code DevDataInitializer}(데모 시드 주입기 — 실회원 흐름 정착으로 삭제됨)가 dev DB에 시드한
 * 게시글 작성자 UUID 3개는 member-service에 실존하지 않으므로, FIXTURES 히트는 고정 닉네임/인증여부를
 * 즉시 반환해 <b>dev DB에 이미 남아 있는 시드 글</b>의 화면 표시를 유지한다(역전 현상 방지).
 * dev DB에서 시드 행까지 정리하면 본 fixture·데코레이터도 제거하고 {@link RealMemberClient}를
 * {@code !test}로 확장(dev 직결)해 한 단계 더 단순화할 수 있다.
 * fixture에 없는 id(실회원)만 모아 {@link RealMemberClient} 로직에 <b>1회 배치 위임</b>한다(N+1 금지) —
 * 이로써 dev에서도 실회원이 쓴 글·댓글에 실제 닉네임이 표시된다(프론트 "Unknown" 버그 해소).
 *
 * <p>장애 정책은 위임받는 Real과 동일(fail-open): member-service 장애 시 위임분이 "Unknown" 폴백으로
 * 돌아오므로 본 클래스는 별도 폴백 로직을 두지 않는다(HTTP·폴백 로직은 RealMemberClient 1벌).
 *
 * <p>Real 인스턴스는 빈이 아니라 내부 생성이다 — dev 프로파일에서 {@link MemberClient} 빈이
 * 본 클래스 하나뿐이도록(같은 인터페이스 빈 2개 주입 충돌 방지). test 프로파일은 어떤 구현체도
 * 등록하지 않고 단위는 {@code @Mock}, 컨텍스트 로딩은 {@code @MockitoBean}으로 가린다(현행 유지).
 */
@Component
@Profile("dev")
public class DevMemberClient implements MemberClient {

    /** 개발용 고정 회원 데이터. 키는 (삭제된) DevDataInitializer가 dev DB에 남긴 시드 글 작성자 UUID 3개와 1:1.
     *  trust_grade는 인증 배지와 동일 규칙(이슈 #193 Phase 1: 인증=VERIFIED, 미인증=NEWCOMER)로 섞어 둬
     *  dev 화면에서 등급별 표시(테두리)를 바로 확인할 수 있게 한다. */
    static final Map<String, MemberInfo> FIXTURES = Map.of(
            "00000000-0000-0000-0000-000000000001",
                    new MemberInfo("Minh", true, null, "VERIFIED"),
            "00000000-0000-0000-0000-000000000002",
                    new MemberInfo("Sokha", false, null, "NEWCOMER"),
            "00000000-0000-0000-0000-000000000003",
                    new MemberInfo("Aung", true, null, "VERIFIED"));

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
}
