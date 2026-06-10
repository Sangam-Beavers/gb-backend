package com.gb.appadmin.domain.notice.repository;

import com.gb.appadmin.domain.notice.entity.Notice;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface NoticeRepository extends JpaRepository<Notice, Long> {
    Optional<Notice> findByPublicId(String publicId);
    /** 사용자 노출용: published=true 항목, pinned 먼저 → createdAt desc */
    List<Notice> findByPublishedTrueOrderByPinnedDescCreatedAtDesc();
}
