package com.gb.appadmin.domain.faq.entity;

import com.gb.appadmin.global.common.entity.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 자주 묻는 질문(FAQ).
 * category별 필터링 + sort_order로 정렬 노출.
 */
@Entity
@Getter
@Table(name = "faqs",
        indexes = {
                @Index(name = "idx_faqs_category", columnList = "category"),
                @Index(name = "idx_faqs_published", columnList = "published"),
                @Index(name = "idx_faqs_sort_order", columnList = "sort_order")
        })
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Faq extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "public_id", length = 36, nullable = false, unique = true)
    private String publicId;

    @Column(name = "question", length = 300, nullable = false)
    private String question;

    @Column(name = "answer", columnDefinition = "TEXT", nullable = false)
    private String answer;

    /** GENERAL / TRANSFER / EXCHANGE / DOCUMENT / ACCOUNT 등 */
    @Column(name = "category", length = 50, nullable = false)
    private String category;

    @Column(name = "published", nullable = false)
    private Boolean published;

    /** 동일 카테고리 내 노출 순서(낮을수록 위) */
    @Column(name = "sort_order", nullable = false)
    private Integer sortOrder;

    @Builder
    private Faq(String publicId, String question, String answer, String category,
                Boolean published, Integer sortOrder) {
        this.publicId = publicId;
        this.question = question;
        this.answer = answer;
        this.category = category;
        this.published = published != null ? published : false;
        this.sortOrder = sortOrder != null ? sortOrder : 0;
    }

    public void update(String question, String answer, String category,
                       Boolean published, Integer sortOrder) {
        if (question != null) this.question = question;
        if (answer != null) this.answer = answer;
        if (category != null) this.category = category;
        if (published != null) this.published = published;
        if (sortOrder != null) this.sortOrder = sortOrder;
    }
}
