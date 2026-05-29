package com.gb.document.domain.document.repository;

import com.gb.document.domain.document.entity.DocumentResult;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * DocumentResult Repository. 분석 결과 상세 조회와 목록 join용.
 */
public interface DocumentResultRepository extends JpaRepository<DocumentResult, Long> {

    /** 결과 상세 조회 — submission.public_id로 검색. */
    Optional<DocumentResult> findBySubmission_PublicId(String publicId);

    /** 목록 화면에서 risk_level 표시용. submission id IN (...) batch 조회. */
    List<DocumentResult> findAllBySubmission_IdIn(List<Long> submissionIds);
}
