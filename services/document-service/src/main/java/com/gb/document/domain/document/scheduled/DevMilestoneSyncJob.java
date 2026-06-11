package com.gb.document.domain.document.scheduled;

import com.gb.document.domain.document.entity.DocumentResult;
import com.gb.document.domain.document.entity.ProcessingStatus;
import com.gb.document.domain.document.repository.DocumentResultRepository;
import com.gb.document.global.event.MilestoneAchieved;
import com.gb.document.global.event.MilestoneType;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Dev 전용 DOCUMENT_ANALYZED 마일스톤 동기화 잡 (Phase 3 — BE-7).
 *
 * <h3>왜 필요한가</h3>
 * <p>Dev 환경에서는 SQS Consumer({@code gb.analysis.consumer-enabled=false})가 비활성화되고
 * 계정 B(AI Lambda)가 분석 결과를 본체 DB에 직접 INSERT한다. 이 경로는
 * {@link com.gb.document.domain.document.service.impl.AnalysisResultIngestServiceImpl}을 거치지
 * 않으므로 {@code DOCUMENT_ANALYZED} 마일스톤 이벤트가 발행되지 않는다.
 *
 * <h3>동작 방식</h3>
 * <ul>
 *   <li>30초마다 COMPLETED·PARTIAL 결과 전체를 조회해 {@link MilestoneAchieved} 내부 이벤트를 발행.</li>
 *   <li>{@code @Transactional}로 감싸 커밋 후 {@link com.gb.document.global.event.MilestoneEventPublisher}
 *       ({@code AFTER_COMMIT})가 Kafka 토픽 {@code document.milestone-achieved.v1}에 전송한다.</li>
 *   <li>member-service의 {@code member_milestones}는 {@code (user_public_id, milestone_type)} UNIQUE 제약으로
 *       자연 멱등 — 같은 유저의 반복 발행은 선검사(existsBy) 또는 catch-and-skip으로 1건만 기록된다.</li>
 *   <li>Dev 데이터 규모가 작아 N×30초 반복 발행은 부담 없음. 운영(stage·prod)에선 이 빈 자체가 등록 안 됨.</li>
 * </ul>
 */
@Slf4j
@Profile("dev")
@Component
@RequiredArgsConstructor
public class DevMilestoneSyncJob {

    private static final List<ProcessingStatus> COMPLETED_STATUSES =
            List.of(ProcessingStatus.COMPLETED, ProcessingStatus.PARTIAL);

    private final DocumentResultRepository documentResultRepository;
    private final ApplicationEventPublisher eventPublisher;

    /**
     * 30초마다 실행. 분석이 완료된 모든 문서에 대해 {@code DOCUMENT_ANALYZED} 마일스톤 이벤트를 발행한다.
     * member-service가 멱등 처리하므로 중복 발행은 무해하다.
     */
    @Scheduled(fixedDelay = 30_000)
    @Transactional
    public void syncMilestones() {
        List<DocumentResult> completed = documentResultRepository.findAllByProcessingStatusIn(COMPLETED_STATUSES);
        if (completed.isEmpty()) {
            return;
        }
        for (DocumentResult result : completed) {
            String userPublicId = result.getSubmission().getUserPublicId();
            if (userPublicId == null || userPublicId.isBlank()) {
                continue;
            }
            eventPublisher.publishEvent(new MilestoneAchieved(userPublicId, MilestoneType.DOCUMENT_ANALYZED));
        }
        log.debug("[dev-milestone-sync] DOCUMENT_ANALYZED 이벤트 발행 {}건", completed.size());
    }
}
