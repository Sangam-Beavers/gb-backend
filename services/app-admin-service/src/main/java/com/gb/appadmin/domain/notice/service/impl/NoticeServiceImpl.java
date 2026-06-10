package com.gb.appadmin.domain.notice.service.impl;

import com.gb.appadmin.domain.notice.dto.request.NoticeCreateRequest;
import com.gb.appadmin.domain.notice.dto.request.NoticeUpdateRequest;
import com.gb.appadmin.domain.notice.dto.response.NoticeResponse;
import com.gb.appadmin.domain.notice.entity.Notice;
import com.gb.appadmin.domain.notice.repository.NoticeRepository;
import com.gb.appadmin.domain.notice.service.NoticeService;
import com.gb.appadmin.global.exception.code.AppAdminErrorCode;
import com.gb.common.exception.BusinessException;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class NoticeServiceImpl implements NoticeService {

    private final NoticeRepository noticeRepository;

    @Override
    public List<NoticeResponse> listPublished() {
        return noticeRepository.findByPublishedTrueOrderByPinnedDescCreatedAtDesc()
                .stream().map(NoticeResponse::from).toList();
    }

    @Override
    public List<NoticeResponse> listAll() {
        return noticeRepository.findAll().stream()
                .sorted((a, b) -> {
                    if (!a.getPinned().equals(b.getPinned())) return b.getPinned() ? 1 : -1;
                    if (a.getCreatedAt() == null) return 1;
                    if (b.getCreatedAt() == null) return -1;
                    return b.getCreatedAt().compareTo(a.getCreatedAt());
                })
                .map(NoticeResponse::from).toList();
    }

    @Override
    public NoticeResponse getByPublicId(String publicId) {
        Notice notice = findOrThrow(publicId);
        return NoticeResponse.from(notice);
    }

    @Override
    @Transactional
    public NoticeResponse create(NoticeCreateRequest request) {
        Notice notice = Notice.builder()
                .publicId(UUID.randomUUID().toString())
                .title(request.title())
                .content(request.content())
                .pinned(request.pinned())
                .published(request.published())
                .build();
        return NoticeResponse.from(noticeRepository.save(notice));
    }

    @Override
    @Transactional
    public NoticeResponse update(String publicId, NoticeUpdateRequest request) {
        Notice notice = findOrThrow(publicId);
        notice.update(request.title(), request.content(), request.pinned(), request.published());
        return NoticeResponse.from(notice);
    }

    @Override
    @Transactional
    public void delete(String publicId) {
        Notice notice = findOrThrow(publicId);
        noticeRepository.delete(notice);
    }

    private Notice findOrThrow(String publicId) {
        return noticeRepository.findByPublicId(publicId)
                .orElseThrow(() -> new BusinessException(AppAdminErrorCode.NOTICE_NOT_FOUND));
    }
}
