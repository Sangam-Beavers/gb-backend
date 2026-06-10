package com.gb.appadmin.domain.faq.service;

import com.gb.appadmin.domain.faq.dto.request.FaqCreateRequest;
import com.gb.appadmin.domain.faq.dto.request.FaqUpdateRequest;
import com.gb.appadmin.domain.faq.dto.response.FaqResponse;
import java.util.List;

public interface FaqService {
    List<FaqResponse> listPublished(String category);
    List<FaqResponse> listAll();
    FaqResponse getByPublicId(String publicId);
    FaqResponse create(FaqCreateRequest request);
    FaqResponse update(String publicId, FaqUpdateRequest request);
    void delete(String publicId);
}
