package com.gb.appadmin.domain.faq.controller;

import com.gb.appadmin.domain.faq.dto.request.FaqCreateRequest;
import com.gb.appadmin.domain.faq.dto.request.FaqUpdateRequest;
import com.gb.appadmin.domain.faq.dto.response.FaqResponse;
import com.gb.appadmin.domain.faq.service.FaqService;
import com.gb.common.response.ApiResponse;
import com.gb.common.response.ErrorResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "FAQs (Admin)", description = "FAQ 관리")
@RestController
@RequestMapping("/api/v1/admin/app/faqs")
@RequiredArgsConstructor
public class AdminFaqController {

    private final FaqService faqService;

    @Operation(summary = "FAQ 전체 목록(관리자)")
    @GetMapping
    public ApiResponse<List<FaqResponse>> listAll() {
        return ApiResponse.success(faqService.listAll());
    }

    @Operation(summary = "FAQ 등록")
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<FaqResponse> create(@Valid @RequestBody FaqCreateRequest request) {
        return ApiResponse.success(faqService.create(request));
    }

    @Operation(summary = "FAQ 수정")
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "성공"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404",
                    description = "ADMIN4003 - 존재하지 않는 FAQ입니다.",
                    content = @Content(mediaType = "application/json",
                            schema = @Schema(implementation = ErrorResponse.class)))
    })
    @PatchMapping("/{publicId}")
    public ApiResponse<FaqResponse> update(@PathVariable String publicId,
                                           @Valid @RequestBody FaqUpdateRequest request) {
        return ApiResponse.success(faqService.update(publicId, request));
    }

    @Operation(summary = "FAQ 삭제")
    @DeleteMapping("/{publicId}")
    public ApiResponse<Void> delete(@PathVariable String publicId) {
        faqService.delete(publicId);
        return ApiResponse.success(null);
    }
}
