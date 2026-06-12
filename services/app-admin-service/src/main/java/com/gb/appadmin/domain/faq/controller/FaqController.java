package com.gb.appadmin.domain.faq.controller;

import com.gb.appadmin.domain.faq.dto.response.FaqResponse;
import com.gb.appadmin.domain.faq.service.FaqService;
import com.gb.common.response.ApiResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "FAQs (Public)", description = "FAQ 공개 조회")
@RestController
@RequestMapping("/api/v1/app-admin/app/faqs")
@RequiredArgsConstructor
public class FaqController {

    private final FaqService faqService;

    @Operation(summary = "FAQ 목록", description = "게시된 FAQ. category 파라미터로 필터링 가능.")
    @GetMapping
    public ApiResponse<List<FaqResponse>> listPublished(
            @Parameter(description = "카테고리 필터(GENERAL/TRANSFER/EXCHANGE/DOCUMENT/ACCOUNT)")
            @RequestParam(required = false) String category) {
        return ApiResponse.success(faqService.listPublished(category));
    }

    @Operation(summary = "FAQ 상세")
    @GetMapping("/{publicId}")
    public ApiResponse<FaqResponse> getOne(@PathVariable String publicId) {
        return ApiResponse.success(faqService.getByPublicId(publicId));
    }
}
