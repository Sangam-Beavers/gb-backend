package com.gb.admin.global.client;

import java.util.List;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;

/**
 * Page<T>를 들고 다닐 일이 잦지만, MSA 경계를 넘는 Mock 데이터는 단순한 List + 메타로 충분하다.
 * 이 헬퍼는 Mock 구현체가 Spring Page를 손쉽게 만들 수 있도록 한다(다음 스프린트에서 RealXxxAdminClient가
 * 실 페이지네이션으로 교체).
 */
public final class AdminPage {

    private AdminPage() {
    }

    public static <T> Page<T> of(List<T> all, int page, int size) {
        int from = Math.min(page * size, all.size());
        int to = Math.min(from + size, all.size());
        List<T> slice = all.subList(from, to);
        return new PageImpl<>(slice, PageRequest.of(page, Math.max(size, 1)), all.size());
    }
}
