package com.gb.document.domain.document.repository;

import static org.assertj.core.api.Assertions.assertThat;

import com.gb.document.domain.document.entity.AnalysisDocumentType;
import com.gb.document.domain.document.entity.Document;
import com.gb.document.domain.document.entity.DocumentStatus;
import com.gb.document.global.config.JpaConfig;
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
