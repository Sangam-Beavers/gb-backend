package com.gb.appadmin.global.client;

import com.gb.appadmin.domain.member.dto.response.UserActivityResponse;
import com.gb.appadmin.domain.member.dto.response.UserCommentView;
import com.gb.appadmin.domain.member.dto.response.UserPostView;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * 개발용 픽스처 커뮤니티 클라이언트. community-service 없이도 회원 활동 화면 개발 가능.
 */
@Slf4j
@Component
@Profile("test")
public class MockCommunityAdminClient implements CommunityAdminClient {

    private static final DateTimeFormatter FMT = DateTimeFormatter.ISO_LOCAL_DATE_TIME;

    // uid-001(Juan) 게시글 픽스처
    private static final Map<String, List<UserPostView>> POSTS = Map.of(
            "uid-001", List.of(
                    new UserPostView("post-uid-001", "VISA", "비자 연장 신청 방법 질문", 3, 7, now(-5)),
                    new UserPostView("post-uid-002", "LIFE", "서울 생활 꿀팁 공유", 1, 12, now(-2))
            ),
            "uid-002", List.of(
                    new UserPostView("post-uid-003", "JOB", "공장 일자리 추천 부탁드려요", 5, 3, now(-10))
            )
    );

    private static final Map<String, List<UserCommentView>> COMMENTS = Map.of(
            "uid-001", List.of(
                    new UserCommentView("cmt-uid-001", "post-uid-003", "저도 같은 경험 있어요!", 2, now(-4))
            ),
            "uid-003", List.of(
                    new UserCommentView("cmt-uid-002", "post-uid-001", "감사합니다 도움이 많이 됐어요", 0, now(-1))
            )
    );

    @Override
    public UserActivityResponse getUserActivity(String userPublicId, int postPage, int commentPage, int size) {
        log.info("[MockCommunityAdminClient] getUserActivity userPublicId={}", userPublicId);
        List<UserPostView> posts = POSTS.getOrDefault(userPublicId, List.of());
        List<UserCommentView> comments = COMMENTS.getOrDefault(userPublicId, List.of());
        return new UserActivityResponse(
                posts, 0, size, posts.size(),
                comments, 0, size, comments.size()
        );
    }

    private static String now(int daysOffset) {
        return LocalDateTime.now().plusDays(daysOffset).format(FMT);
    }
}
