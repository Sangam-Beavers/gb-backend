package com.gb.member.domain.admin.service.impl;

import com.gb.common.exception.BusinessException;
import com.gb.member.domain.admin.dto.response.AdminMemberLookupResponse;
import com.gb.member.domain.admin.dto.response.AdminMemberMini;
import com.gb.member.domain.admin.dto.response.AdminMemberPageResponse;
import com.gb.member.domain.admin.dto.response.AdminMemberView;
import com.gb.member.domain.admin.dto.response.MemberStatsResponse;
import com.gb.member.domain.admin.service.MemberAdminInternalService;
import com.gb.member.domain.member.entity.Member;
import com.gb.member.domain.member.repository.MemberRepository;
import com.gb.member.domain.member.service.TrustGradeService;
import com.gb.member.domain.verification.entity.UserVerification;
import com.gb.member.domain.verification.entity.VerificationStatus;
import com.gb.member.domain.verification.repository.UserVerificationRepository;
import com.gb.member.global.exception.code.MemberErrorCode;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class MemberAdminInternalServiceImpl implements MemberAdminInternalService {

    private final MemberRepository memberRepository;
    private final UserVerificationRepository userVerificationRepository;
    private final TrustGradeService trustGradeService;

    @Override
    public AdminMemberPageResponse search(String q, String kycStatus, int page, int size) {
        Pageable pageable = PageRequest.of(Math.max(page, 0), normalizeSize(size),
                Sort.by(Sort.Direction.DESC, "createdAt"));
        String needle = (q == null || q.isBlank()) ? null : q.trim();
        Page<Member> members = memberRepository.searchForAdmin(needle, pageable);

        // 최신 verification 한 번에 IN 조회(N+1 방지).
        List<Long> ids = members.getContent().stream().map(Member::getId).toList();
        Map<Long, UserVerification> latestByMember = new HashMap<>();
        if (!ids.isEmpty()) {
            userVerificationRepository.findLatestByMemberIds(ids).forEach(v ->
                    latestByMember.put(v.getMember().getId(), v));
        }

        VerificationStatus statusFilter = parseStatus(kycStatus);

        Page<AdminMemberView> mapped = members.map(m -> {
            UserVerification v = latestByMember.get(m.getId());
            return AdminMemberView.from(m, v);
        });

        if (statusFilter != null) {
            String want = statusFilter.name();
            List<AdminMemberView> filtered = mapped.getContent().stream()
                    .filter(v -> want.equals(v.kycStatus()))
                    .toList();
            mapped = new org.springframework.data.domain.PageImpl<>(
                    filtered, pageable, members.getTotalElements());
        }

        return AdminMemberPageResponse.from(mapped);
    }

    @Override
    public AdminMemberView get(String publicId) {
        Member m = memberRepository.findByPublicIdAndDeletedAtIsNull(publicId)
                .orElseThrow(() -> new BusinessException(MemberErrorCode.MEMBER_NOT_FOUND));
        UserVerification v = userVerificationRepository.findTopByMemberOrderByIdDesc(m).orElse(null);
        return AdminMemberView.from(m, v);
    }

    @Override
    @Transactional
    public void approveKyc(String publicId) {
        Member m = memberRepository.findByPublicIdAndDeletedAtIsNull(publicId)
                .orElseThrow(() -> new BusinessException(MemberErrorCode.MEMBER_NOT_FOUND));
        UserVerification v = userVerificationRepository.findTopByMemberOrderByIdDesc(m).orElse(null);
        if (v != null) {
            // 발표용: 최신 verification 의 status를 APPROVED로 바꾸려면 도메인 메서드가 필요한데
            // UserVerification.approved() 정적 팩토리만 있고 상태 변경 메서드는 없다. 본 발표 단계에선
            // member.isVerified 만 표식한다(audit 흐름 분리). 후속 스프린트에서 verification 상태 전환 메서드 도입.
            log.info("[MemberAdminInternal] approveKyc — verification 상태 전환은 다음 스프린트(member={}, vId={})",
                    publicId, v.getId());
        }
        m.markVerified();
        // 이슈 #193 — 배지 부여는 신뢰등급 마일스톤이므로 같은 tx 안에서 재계산한다(단일 진입점).
        trustGradeService.recalculate(m);
    }

    @Override
    @Transactional
    public void rejectKyc(String publicId, String reason) {
        Member m = memberRepository.findByPublicIdAndDeletedAtIsNull(publicId)
                .orElseThrow(() -> new BusinessException(MemberErrorCode.MEMBER_NOT_FOUND));
        // 발표용: 거절은 audit_log(admin-service)에만 남기고, member 본체 상태는 변경하지 않는다
        // (도메인 메서드 신규 도입은 다음 스프린트).
        log.info("[MemberAdminInternal] rejectKyc — 본체 상태 변경은 다음 스프린트(member={}, reason={})", publicId, reason);
        // 이슈 #193 — 등급 재계산 훅(승인 취소 지점). 현 스프린트의 reject는 배지(is_verified)를 내리지 않아
        // 등급도 그대로다(no-op). 다음 스프린트에서 승인 취소가 배지를 회수하면 같은 호출이 NEWCOMER 복귀를
        // 수행한다 — 산정 규칙은 TrustGradeService 단일 진입점에만 둔다.
        trustGradeService.recalculate(m);
    }

    @Override
    public AdminMemberLookupResponse lookup(Collection<String> userPublicIds) {
        if (userPublicIds == null || userPublicIds.isEmpty()) {
            return new AdminMemberLookupResponse(Map.of());
        }
        List<Member> found = memberRepository.findByPublicIdInAndDeletedAtIsNull(userPublicIds);
        Map<String, AdminMemberMini> result = new LinkedHashMap<>();
        for (Member m : found) {
            result.put(m.getPublicId(), AdminMemberMini.from(m));
        }
        return new AdminMemberLookupResponse(result);
    }

    @Override
    public MemberStatsResponse getStats() {
        long total = memberRepository.countByDeletedAtIsNull();
        long approved = userVerificationRepository.countByStatus(VerificationStatus.APPROVED);
        long pending = userVerificationRepository.countByStatus(VerificationStatus.PENDING);
        long rejected = userVerificationRepository.countByStatus(VerificationStatus.REJECTED);
        long reviewedTotal = approved + rejected;
        String passRate = reviewedTotal == 0 ? "0.0000"
                : BigDecimal.valueOf(approved).divide(BigDecimal.valueOf(reviewedTotal),
                        4, RoundingMode.HALF_UP).toPlainString();
        LocalDateTime startOfDay = LocalDate.now().atStartOfDay();
        LocalDateTime endOfDay = LocalDateTime.of(LocalDate.now(), LocalTime.MAX);
        long newToday = memberRepository.countByDeletedAtIsNullAndCreatedAtBetween(startOfDay, endOfDay);
        return new MemberStatsResponse(total, pending, approved, rejected, passRate, newToday);
    }

    private static VerificationStatus parseStatus(String kycStatus) {
        if (kycStatus == null || kycStatus.isBlank()) return null;
        try {
            return VerificationStatus.valueOf(kycStatus.toUpperCase());
        } catch (IllegalArgumentException ignore) {
            return null;
        }
    }

    private static int normalizeSize(int size) {
        if (size <= 0) return 20;
        return Math.min(size, 200);
    }
}
