package com.gb.appadmin.domain.notice.service;

import com.gb.appadmin.domain.notice.dto.request.NoticeCreateRequest;
import com.gb.appadmin.domain.notice.dto.request.NoticeUpdateRequest;
import com.gb.appadmin.domain.notice.dto.response.NoticeResponse;
import java.util.List;

public interface NoticeService {
    /** 게시된 공지사항 목록(사용자용) */
    List<NoticeResponse> listPublished();
    /** 전체 목록(관리자용) */
    List<NoticeResponse> listAll();
    NoticeResponse getByPublicId(String publicId);
    NoticeResponse create(NoticeCreateRequest request);
    NoticeResponse update(String publicId, NoticeUpdateRequest request);
    void delete(String publicId);
}
