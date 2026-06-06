package com.gb.document.domain.document.repository;

import static org.assertj.core.api.Assertions.assertThat;

import com.gb.document.domain.document.entity.AnalysisDocumentType;
import com.gb.document.domain.document.entity.Document;
import com.gb.document.domain.document.entity.DocumentStatus;
import com.gb.document.global.config.JpaConfig;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase.Replace;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.test.context.ActiveProfiles;

@DataJpaTest
@ActiveProfiles("test")
@AutoConfigureTestDatabase(replace = Replace.NONE)
@Import(JpaConfig.class)
class DocumentRepositoryTest {

    @Autowired TestEntityManager em;
    @Autowired DocumentRepository documentRepository;

    @Test
    @DisplayName("findAllByUserPublicId: 본인 문서만 createdAt DESC로 조회 — 다른 소유자는 제외")
    void 본인문서_최근순() {
        // 같은 사용자 3건 + 다른 사용자 1건
        Document a = persist(doc("a", "user-A", DocumentStatus.ANALYZING));
        Document b = persist(doc("b", "user-A", DocumentStatus.COMPLETED));
        Document c = persist(doc("c", "user-A", DocumentStatus.FAILED));
        persist(doc("d", "user-OTHER", DocumentStatus.COMPLETED));
        em.flush();

        Page<Document> page = documentRepository.findAllByUserPublicId(
                "user-A",
                PageRequest.of(0, 10, Sort.by(Sort.Direction.DESC, "createdAt")));

        assertThat(page.getTotalElements()).isEqualTo(3);
        // 최신 INSERT가 c → b → a 순으로 createdAt이 부여되므로 DESC 정렬 시 c, b, a 순서.
        assertThat(page.getContent())
                .extracting(Document::getPublicId)
                .containsExactly(c.getPublicId(), b.getPublicId(), a.getPublicId());
    }

    @Test
    @DisplayName("findAllByUserPublicIdAndStatusIn: 지정 상태만 최근순 — FAILED·다른 소유자는 제외")
    void 상태필터_본인문서_최근순() {
        Document a = persist(doc("a", "user-A", DocumentStatus.ANALYZING));
        Document b = persist(doc("b", "user-A", DocumentStatus.COMPLETED));
        persist(doc("c", "user-A", DocumentStatus.FAILED));          // 필터 밖 상태 — 제외
        persist(doc("d", "user-OTHER", DocumentStatus.COMPLETED));   // 다른 소유자 — 제외
        em.flush();

        Page<Document> page = documentRepository.findAllByUserPublicIdAndStatusIn(
                "user-A",
                List.of(DocumentStatus.ANALYZING, DocumentStatus.COMPLETED),
                PageRequest.of(0, 10, Sort.by(Sort.Direction.DESC, "createdAt")));

        assertThat(page.getTotalElements()).isEqualTo(2);
        // 최신 INSERT가 b → a 순으로 createdAt이 부여되므로 DESC 정렬 시 b, a 순서.
        assertThat(page.getContent())
                .extracting(Document::getPublicId)
                .containsExactly(b.getPublicId(), a.getPublicId());
    }

    @Test
    @DisplayName("findAllByStatusAndUpdatedAtBefore: 임계 이전의 ANALYZING만 — 최신 ANALYZING·타 상태 과거 건은 제외")
    void 오래된_ANALYZING만_조회() {
        Document stale = persist(doc("stale", "user-A", DocumentStatus.ANALYZING));
        persist(doc("fresh", "user-A", DocumentStatus.ANALYZING));          // 최신 — 제외돼야 함
        Document doneOld = persist(doc("done-old", "user-A", DocumentStatus.COMPLETED)); // 과거지만 타 상태 — 제외
        Document failOld = persist(doc("fail-old", "user-A", DocumentStatus.FAILED));    // 과거지만 타 상태 — 제외
        em.flush();

        // @LastModifiedDate가 persist 시점 값을 덮어쓰므로 과거 시각은 native UPDATE로 박는다(CLAUDE.md §10).
        // 저장 시각 규약 = UTC(JpaConfig utcDateTimeProvider, 10D 시각 통일) — 테스트의 기준 시각도 UTC로
        // 잡아야 KST 등 비-UTC JVM에서 audited 값(UTC)과 9시간 어긋나지 않는다.
        LocalDateTime past = LocalDateTime.now(ZoneOffset.UTC).minusHours(1);
        setUpdatedAt(stale.getId(), past);
        setUpdatedAt(doneOld.getId(), past);
        setUpdatedAt(failOld.getId(), past);
        em.clear();

        List<Document> result = documentRepository.findAllByStatusAndUpdatedAtBefore(
                DocumentStatus.ANALYZING, LocalDateTime.now(ZoneOffset.UTC).minusMinutes(30));

        assertThat(result).extracting(Document::getPublicId).containsExactly("stale");
    }

    private void setUpdatedAt(Long id, LocalDateTime updatedAt) {
        em.getEntityManager()
                .createNativeQuery("UPDATE document_submissions SET updated_at = :ts WHERE id = :id")
                .setParameter("ts", updatedAt)
                .setParameter("id", id)
                .executeUpdate();
    }

    private Document persist(Document d) {
        em.persist(d);
        return d;
    }

    private Document doc(String publicId, String userPublicId, DocumentStatus status) {
        return Document.builder()
                .publicId(publicId)
                .userPublicId(userPublicId)
                .analysisDocumentType(AnalysisDocumentType.LABOR_CONTRACT)
                .fileName("f.pdf")
                .status(status)
                .build();
    }
}
