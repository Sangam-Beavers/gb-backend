package com.gb.document.global.config;

import com.gb.document.domain.document.entity.Document;
import com.gb.document.domain.document.repository.DocumentRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * dev profile일 때만 동작하는 데모용 더미 Document 시드 컴포넌트.
 *
 * <p>왜 필요한가: ChatController({@code POST /api/v1/documents/{publicId}/chat})가 권한 검증을 위해
 * DB에서 문서를 조회하는데, dev MySQL의 {@code documents} 테이블이 비어 있으면 항상 DOCUMENT4001로
 * 떨어져 챗봇 동작 자체를 검증할 수 없다. Phase 4의 분석 파이프라인이 실제 문서를 INSERT하기 전까지의
 * 임시 발판.
 *
 * <p>Phase 4 도입 시점에 분석 파이프라인이 documents에 진짜 데이터를 INSERT하기 시작하면 이 시드는
 * 제거 후보가 된다(현재는 영상 데모 + 로컬 테스트용으로만 사용).
 */
@Slf4j
@Profile("dev")
@Component
@RequiredArgsConstructor
public class DevDataInitializer implements ApplicationRunner {

    /**
     * 데모용 고정 UUID. 영상 시연 시 curl 명령에 그대로 박아 쓰기 위해 매 기동마다 동일하게 둔다.
     * 실제 운영에서는 user-service / document-service가 발급하는 UUID로 채워진다.
     */
    public static final String DEMO_DOCUMENT_PUBLIC_ID = "00000000-0000-0000-0000-000000000001";
    public static final String DEMO_USER_PUBLIC_ID     = "00000000-0000-0000-0000-000000000001";

    private final DocumentRepository documentRepository;

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        if (documentRepository.findByPublicId(DEMO_DOCUMENT_PUBLIC_ID).isPresent()) {
            log.info("[dev-seed] 데모 문서 이미 존재 — 시드 스킵 (publicId={})", DEMO_DOCUMENT_PUBLIC_ID);
            return;
        }
        Document seeded = Document.builder()
                .publicId(DEMO_DOCUMENT_PUBLIC_ID)
                .userPublicId(DEMO_USER_PUBLIC_ID)
                .build();
        documentRepository.save(seeded);
        log.info("[dev-seed] 데모 문서 시드 완료 — publicId={} ownerPublicId={}",
                DEMO_DOCUMENT_PUBLIC_ID, DEMO_USER_PUBLIC_ID);
    }
}
