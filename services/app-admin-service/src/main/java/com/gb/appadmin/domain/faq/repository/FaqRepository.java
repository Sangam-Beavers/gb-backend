package com.gb.appadmin.domain.faq.repository;

import com.gb.appadmin.domain.faq.entity.Faq;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface FaqRepository extends JpaRepository<Faq, Long> {
    Optional<Faq> findByPublicId(String publicId);
    /** 사용자 노출용: published=true, 카테고리 필터 + sortOrder asc */
    List<Faq> findByPublishedTrueOrderByCategoryAscSortOrderAsc();
    List<Faq> findByCategoryAndPublishedTrueOrderBySortOrderAsc(String category);
    /** 관리자 전체 목록 */
    List<Faq> findAllByOrderByCategoryAscSortOrderAsc();
}
