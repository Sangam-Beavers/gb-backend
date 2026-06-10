package com.gb.appadmin.domain.faq.service.impl;

import com.gb.appadmin.domain.faq.dto.request.FaqCreateRequest;
import com.gb.appadmin.domain.faq.dto.request.FaqUpdateRequest;
import com.gb.appadmin.domain.faq.dto.response.FaqResponse;
import com.gb.appadmin.domain.faq.entity.Faq;
import com.gb.appadmin.domain.faq.repository.FaqRepository;
import com.gb.appadmin.domain.faq.service.FaqService;
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
public class FaqServiceImpl implements FaqService {

    private final FaqRepository faqRepository;

    @Override
    public List<FaqResponse> listPublished(String category) {
        if (category != null && !category.isBlank()) {
            return faqRepository.findByCategoryAndPublishedTrueOrderBySortOrderAsc(category)
                    .stream().map(FaqResponse::from).toList();
        }
        return faqRepository.findByPublishedTrueOrderByCategoryAscSortOrderAsc()
                .stream().map(FaqResponse::from).toList();
    }

    @Override
    public List<FaqResponse> listAll() {
        return faqRepository.findAllByOrderByCategoryAscSortOrderAsc()
                .stream().map(FaqResponse::from).toList();
    }

    @Override
    public FaqResponse getByPublicId(String publicId) {
        return FaqResponse.from(findOrThrow(publicId));
    }

    @Override
    @Transactional
    public FaqResponse create(FaqCreateRequest request) {
        Faq faq = Faq.builder()
                .publicId(UUID.randomUUID().toString())
                .question(request.question())
                .answer(request.answer())
                .category(request.category())
                .published(request.published())
                .sortOrder(request.sortOrder())
                .build();
        return FaqResponse.from(faqRepository.save(faq));
    }

    @Override
    @Transactional
    public FaqResponse update(String publicId, FaqUpdateRequest request) {
        Faq faq = findOrThrow(publicId);
        faq.update(request.question(), request.answer(), request.category(),
                request.published(), request.sortOrder());
        return FaqResponse.from(faq);
    }

    @Override
    @Transactional
    public void delete(String publicId) {
        Faq faq = findOrThrow(publicId);
        faqRepository.delete(faq);
    }

    private Faq findOrThrow(String publicId) {
        return faqRepository.findByPublicId(publicId)
                .orElseThrow(() -> new BusinessException(AppAdminErrorCode.FAQ_NOT_FOUND));
    }
}
