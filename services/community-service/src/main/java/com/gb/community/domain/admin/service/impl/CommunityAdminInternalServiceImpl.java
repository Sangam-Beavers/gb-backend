package com.gb.community.domain.admin.service.impl;

import com.gb.common.exception.BusinessException;
import com.gb.common.exception.CommonErrorCode;
import com.gb.community.domain.admin.dto.response.AdminReportPageResponse;
import com.gb.community.domain.admin.dto.response.AdminReportView;
import com.gb.community.domain.admin.dto.response.ReportStatsResponse;
import com.gb.community.domain.admin.service.CommunityAdminInternalService;
import com.gb.community.domain.post.entity.Post;
import com.gb.community.domain.post.entity.PostCategory;
import com.gb.community.domain.post.repository.PostRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class CommunityAdminInternalServiceImpl implements CommunityAdminInternalService {

    private final PostRepository postRepository;

    @Override
    public AdminReportPageResponse reports(String category, int page, int size) {
        Pageable pageable = PageRequest.of(Math.max(page, 0), normalizeSize(size),
                Sort.by(Sort.Direction.DESC, "commentCount", "id"));
        PostCategory cat = parseCategory(category);
        Page<Post> result = postRepository.findReportableForAdmin(cat, pageable);
        return AdminReportPageResponse.from(result.map(AdminReportView::from));
    }

    @Override
    @Transactional
    public void hidePost(String publicId) {
        // 발표용: 본체에 visibility 컬럼이 없어 soft delete 와 hidden 을 동일하게 처리한다.
        Post post = postRepository.findByPublicIdAndDeletedAtIsNull(publicId)
                .orElseThrow(() -> new BusinessException(CommonErrorCode.RESOURCE_NOT_FOUND));
        post.softDelete();
        log.info("[CommunityAdminInternal] hidePost: post={}, treated as soft-delete (visibility 컬럼은 다음 스프린트)", publicId);
    }

    @Override
    @Transactional
    public void deletePost(String publicId) {
        Post post = postRepository.findByPublicIdAndDeletedAtIsNull(publicId)
                .orElseThrow(() -> new BusinessException(CommonErrorCode.RESOURCE_NOT_FOUND));
        post.softDelete();
    }

    @Override
    public ReportStatsResponse stats() {
        long active = postRepository.countByDeletedAtIsNull();
        // 발표용 프록시: 활성 게시글의 ~10% 가 신고 대기로 가정(상한 99).
        long pending = Math.min(99, active / 10);
        return new ReportStatsResponse(pending);
    }

    private static PostCategory parseCategory(String category) {
        if (category == null || category.isBlank()) return null;
        try {
            return PostCategory.valueOf(category.toUpperCase());
        } catch (IllegalArgumentException ignore) {
            return null;
        }
    }

    private static int normalizeSize(int size) {
        if (size <= 0) return 20;
        return Math.min(size, 200);
    }
}
