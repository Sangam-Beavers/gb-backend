package com.gb.community.domain.post.dto.response;

import static org.assertj.core.api.Assertions.assertThat;

import com.gb.community.domain.post.entity.Post;
import com.gb.community.domain.post.entity.PostCategory;
import com.gb.community.global.client.MemberInfo;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * {@link PostSummaryResponse#from} 의 본문 미리보기({@code content_preview}) 생성 경계 검증.
 *
 * <p>목록 응답에서 {@code preview(content)}(앞 {@code PREVIEW_MAX_LENGTH=100}자 + 말줄임표)는 실제
 * 변환 로직인데 목록 통합 테스트는 매핑/정렬만 보고 이 경계는 직접 검증하지 않는다. 여기서 ≤100/＞100/
 * 정확히 101자 경계와 null/빈 문자열 안전성을 못 박는다(DB·Spring 컨텍스트 불필요한 순수 단위 테스트).
 *
 * <p>말줄임표는 단일 문자 U+2026('…')이라 100자 잘림 + 1자 = 길이 101이 된다.
 */
class PostSummaryResponseTest {

    private static final String USER = "00000000-0000-0000-0000-000000000001";
    private static final MemberInfo AUTHOR = new MemberInfo("Minh", true);

    /** 미리보기 최대 길이(PostSummaryResponse.PREVIEW_MAX_LENGTH와 동일). */
    private static final int MAX = 100;
    /** 말줄임표(U+2026) — 단일 문자. */
    private static final String ELLIPSIS = "…";

    @Test
    @DisplayName("길이 ≤100: 원문 그대로(말줄임표 없음)")
    void preview_길이_100이하_원문그대로() {
        String shortContent = "짧은 본문입니다";
        assertThat(previewOf(shortContent)).isEqualTo(shortContent);

        // 정확히 100자 경계: 잘리지 않고 원문 유지, 말줄임표 없음.
        String exactly100 = "a".repeat(99) + "Z";
        assertThat(exactly100).hasSize(100);
        assertThat(previewOf(exactly100))
                .isEqualTo(exactly100)
                .doesNotContain(ELLIPSIS);
    }

    @Test
    @DisplayName("길이 >100: 앞 100자만 남기고 말줄임표(…) 부착 → 길이 101, 뒷부분 잘림")
    void preview_길이_100초과_앞100자_말줄임() {
        // 앞 100자는 'a', 그 뒤는 잘려야 할 꼬리.
        String content = "a".repeat(MAX) + "TAIL_MUST_BE_CUT";

        String preview = previewOf(content);

        assertThat(preview)
                .isEqualTo("a".repeat(MAX) + ELLIPSIS)
                .hasSize(MAX + 1)              // 100자 + 말줄임표 1자
                .endsWith(ELLIPSIS)
                .doesNotContain("TAIL");       // 100자 이후는 버려진다
    }

    @Test
    @DisplayName("경계 정확히 101자: 100자 + … 로 잘림(100자에서는 안 잘리던 것이 1자 늘면 잘린다)")
    void preview_경계_101자() {
        String content = "a".repeat(MAX) + "Z"; // 101자, 101번째 글자는 'Z'
        assertThat(content).hasSize(101);

        String preview = previewOf(content);

        assertThat(preview)
                .isEqualTo("a".repeat(MAX) + ELLIPSIS)
                .hasSize(MAX + 1)
                .doesNotContain("Z");          // 101번째 글자 'Z'는 잘려 사라진다
    }

    @Test
    @DisplayName("null/빈 문자열 안전: null → null, \"\" → \"\"(말줄임표 미부착)")
    void preview_null_빈문자열_안전() {
        assertThat(previewOf(null)).isNull();
        assertThat(previewOf("")).isEqualTo("");
    }

    // ----- helpers -----

    /** content를 가진 Post로 from()을 태워 만들어진 content_preview 값을 돌려준다. */
    private String previewOf(String content) {
        Post post = Post.of(USER, PostCategory.JOB, "ko", "제목", content);
        return PostSummaryResponse.from(post, AUTHOR, USER).getContentPreview();
    }

    @Test
    @DisplayName("is_author: 요청자=작성자면 true, 다르면 false")
    void isAuthor_요청자_작성자_비교() {
        Post post = Post.of(USER, PostCategory.JOB, "ko", "제목", "본문");

        assertThat(PostSummaryResponse.from(post, AUTHOR, USER).getIsAuthor()).isTrue();
        assertThat(PostSummaryResponse.from(post, AUTHOR, "00000000-0000-0000-0000-000000000009")
                .getIsAuthor()).isFalse();
    }
}
