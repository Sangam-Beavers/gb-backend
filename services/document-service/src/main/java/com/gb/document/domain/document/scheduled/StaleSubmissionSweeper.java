package com.gb.document.domain.document.scheduled;

import com.gb.document.domain.document.entity.Document;
import com.gb.document.domain.document.entity.DocumentStatus;
import com.gb.document.domain.document.repository.DocumentRepository;
import com.gb.document.global.config.AnalysisProperties;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * ANALYZING 고아 건 정리 스케줄러.
 *
 * <p><b>왜 필요한가:</b> 제출(POST /documents)은 Pre-signed URL만 발급하고 파일 업로드는 사용자가
 * S3에 직접 한다. 사용자가 업로드를 안 하면 백엔드는 그 사실을 알 길이 없어(S3 이벤트는 Lambda A로만 감)
 * 해당 건이 영원히 ANALYZING으로 남는다. 업로드는 됐지만 결과가 유실된 건(Lambda 장애·DLQ행)도 마찬가지.
 * 둘 다 "일정 시간 지나도 ANALYZING이면 FAILED" 한 가지 규칙으로 정리한다 — 이후 복구는
 * 사용자가 새로 제출(POST /documents)하는 한 경로뿐이다(원본 재사용 재시도 경로는 없음).
 *
 * <p><b>판정 기준 — updatedAt:</b> createdAt이 아니라 updatedAt(상태가 마지막으로 바뀐 시점) 기준이다.
 * 임계값({@code gb.analysis.stale-timeout-minutes}, 기본 30분)은 업로드 URL TTL(10분) + 분석 소요(수 분)
 * 보다 넉넉해야 정상 진행 중인 건을 죽이지 않는다.
 *
 * <p><b>늦은 결과 자기치유:</b> sweep이 FAILED 처리한 뒤 분석 결과가 늦게 도착해도 무해하다 —
 * {@code AnalysisResultIngestService}는 상태와 무관하게 결과를 UPSERT하고 status를 덮어쓰므로
 * (FAILED→COMPLETED) 최종적으로 결과가 이긴다.
 *
 * <p><b>분산 환경:</b> 별도 분산 락 없음(wallet 스케줄러와 달리 자금 이동이 없다). 여러 인스턴스가
 * 동시에 돌아도 "ANALYZING→FAILED" 멱등 갱신이라 결과가 같다.
 *
 * <p><b>cron:</b> {@code gb.analysis.stale-sweep-cron} 프로퍼티로 외부 설정. 기본 10분 주기.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class StaleSubmissionSweeper {

    private final DocumentRepository documentRepository;
    private final AnalysisProperties analysisProperties;

    @Scheduled(cron = "${gb.analysis.stale-sweep-cron:0 */10 * * * *}", zone = "Asia/Seoul")
    @Transactional
    public void sweepStaleSubmissions() {
        LocalDateTime threshold = LocalDateTime.now(ZoneOffset.UTC)
                .minusMinutes(analysisProperties.staleTimeoutMinutes());
        List<Document> stales = documentRepository
                .findAllByStatusAndUpdatedAtBefore(DocumentStatus.ANALYZING, threshold);
        if (stales.isEmpty()) {
            return;
        }

        for (Document document : stales) {
            document.markFailed();
            log.info("[stale-sweep] ANALYZING 초과 건 FAILED 처리 — publicId={}, updatedAt={}",
                    document.getPublicId(), document.getUpdatedAt());
        }
        log.info("[stale-sweep] 총 {}건 FAILED 처리 (threshold={}, timeoutMinutes={})",
                stales.size(), threshold, analysisProperties.staleTimeoutMinutes());
    }
}
