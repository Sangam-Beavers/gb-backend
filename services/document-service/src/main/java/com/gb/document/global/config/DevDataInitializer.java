package com.gb.document.global.config;

import com.gb.document.domain.document.entity.AnalysisDocumentType;
import com.gb.document.domain.document.entity.Document;
import com.gb.document.domain.document.entity.DocumentResult;
import com.gb.document.domain.document.entity.DocumentStatus;
import com.gb.document.domain.document.entity.ProcessingStatus;
import com.gb.document.domain.document.entity.RiskItem;
import com.gb.document.domain.document.entity.RiskLevel;
import com.gb.document.domain.document.entity.WageSummary;
import com.gb.document.domain.document.repository.DocumentRepository;
import com.gb.document.domain.document.repository.DocumentResultRepository;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * dev profile일 때만 동작하는 더미 시드.
 *
 * <p>두 가지 용도:
 * <ol>
 *   <li>챗봇(R1) 권한 검증용 — DEMO_DOCUMENT_PUBLIC_ID로 COMPLETED 문서를 박아 둔다.</li>
 *   <li>분석 조회 API 4종(P2) 동작 확인용 — FAILED 문서와 결과 미생성 ANALYZING 문서도 시드해
 *       /status, /result(COMPLETED는 결과, ANALYZING은 422), /list, /retry(FAILED→ANALYZING)를
 *       SQS Consumer 없이 검증할 수 있게 한다.</li>
 * </ol>
 */
@Slf4j
@Profile("dev")
@Component
@RequiredArgsConstructor
public class DevDataInitializer implements ApplicationRunner {

    /** 데모용 고정 UUID. 영상 시연·로컬 curl에서 그대로 박아 쓰기 위해 매 기동마다 동일하게 둔다. */
    public static final String DEMO_DOCUMENT_PUBLIC_ID = "00000000-0000-0000-0000-000000000001";
    public static final String DEMO_FAILED_PUBLIC_ID   = "00000000-0000-0000-0000-000000000002";
    public static final String DEMO_ANALYZING_PUBLIC_ID = "00000000-0000-0000-0000-000000000003";
    public static final String DEMO_USER_PUBLIC_ID     = "00000000-0000-0000-0000-000000000001";

    private final DocumentRepository documentRepository;
    private final DocumentResultRepository documentResultRepository;

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        seedCompletedWithResult();
        seedFailed();
        seedAnalyzing();
    }

    private void seedCompletedWithResult() {
        if (documentRepository.findByPublicId(DEMO_DOCUMENT_PUBLIC_ID).isPresent()) {
            log.info("[dev-seed] 완료 데모 문서 이미 존재 — 스킵 ({})", DEMO_DOCUMENT_PUBLIC_ID);
            return;
        }
        Document document = documentRepository.save(Document.builder()
                .publicId(DEMO_DOCUMENT_PUBLIC_ID)
                .userPublicId(DEMO_USER_PUBLIC_ID)
                .analysisDocumentType(AnalysisDocumentType.LABOR_CONTRACT)
                .fileName("demo-labor-contract.pdf")
                .status(DocumentStatus.COMPLETED)
                .build());

        WageSummary wage = new WageSummary(
                "KRW",
                new BigDecimal("2000000"),
                new BigDecimal("9620"),
                List.of(new WageSummary.Deduction("national_pension", new BigDecimal("90000"))));
        List<RiskItem> risks = List.of(
                new RiskItem(RiskLevel.HIGH, "제8조", "최저임금 미달 가능성"));

        documentResultRepository.save(DocumentResult.builder()
                .submission(document)
                .analysisDocumentType(AnalysisDocumentType.LABOR_CONTRACT)
                .processingStatus(ProcessingStatus.COMPLETED)
                .overallRiskLevel(RiskLevel.HIGH)
                .ocrConfidence(new BigDecimal("0.92"))
                .wageSummary(wage)
                .riskItems(risks)
                .translatedText("번역된 본문(데모).")
                .translatedLang("ko")
                .s3MaskedKey("masked/demo-masked.png")
                .completedAt(LocalDateTime.now(ZoneOffset.UTC))
                .build());

        log.info("[dev-seed] 완료 데모 문서 + 결과 시드 완료 ({})", DEMO_DOCUMENT_PUBLIC_ID);
    }

    private void seedFailed() {
        if (documentRepository.findByPublicId(DEMO_FAILED_PUBLIC_ID).isPresent()) return;
        documentRepository.save(Document.builder()
                .publicId(DEMO_FAILED_PUBLIC_ID)
                .userPublicId(DEMO_USER_PUBLIC_ID)
                .analysisDocumentType(AnalysisDocumentType.PAYSLIP)
                .fileName("demo-payslip.pdf")
                .status(DocumentStatus.FAILED)
                .build());
        log.info("[dev-seed] FAILED 데모 문서 시드 완료 ({})", DEMO_FAILED_PUBLIC_ID);
    }

    private void seedAnalyzing() {
        if (documentRepository.findByPublicId(DEMO_ANALYZING_PUBLIC_ID).isPresent()) return;
        documentRepository.save(Document.builder()
                .publicId(DEMO_ANALYZING_PUBLIC_ID)
                .userPublicId(DEMO_USER_PUBLIC_ID)
                .analysisDocumentType(AnalysisDocumentType.EMPLOYMENT_CONTRACT)
                .fileName("demo-employment.pdf")
                .status(DocumentStatus.ANALYZING)
                .build());
        log.info("[dev-seed] ANALYZING 데모 문서 시드 완료 ({})", DEMO_ANALYZING_PUBLIC_ID);
    }
}
