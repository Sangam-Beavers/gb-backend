package com.gb.document.domain.document.repository;

import com.gb.document.domain.document.entity.Document;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Document 엔티티 Repository.
 *
 * <p><b>공유 인터페이스</b> — AI-WORK-SPLIT.md §3-③.
 * 챗봇 ChatController(심규보)가 권한 검증을 위해 {@link #findByPublicId(String)} 한 메서드만 사용한다.
 * 유진이 새 메서드를 추가하는 건 자유지만, <b>이 메서드의 시그니처는 변경 금지</b>.
 */
public interface DocumentRepository extends JpaRepository<Document, Long> {

    /**
     * 외부 노출 식별자(public_id)로 문서를 찾는다. 없으면 빈 Optional.
     * ChatController가 호출 후 {@code DocumentErrorCode.DOCUMENT_NOT_FOUND}로 던진다.
     */
    Optional<Document> findByPublicId(String publicId);
}
