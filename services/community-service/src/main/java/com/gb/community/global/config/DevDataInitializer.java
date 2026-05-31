package com.gb.community.global.config;

import com.gb.community.domain.comment.entity.Comment;
import com.gb.community.domain.comment.repository.CommentRepository;
import com.gb.community.domain.post.entity.Post;
import com.gb.community.domain.post.entity.PostCategory;
import com.gb.community.domain.post.repository.PostRepository;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * dev profile일 때만 동작하는 챗봇 데모용 시드 컴포넌트.
 *
 * <p>왜 필요한가: 챗봇 영상 시연 시나리오 4 "비슷한 경험 한 사람?"에서 MCP2가 community_db.posts를
 * SELECT해 답변하려면 데이터가 있어야 함. 커뮤팀이 본격 작업 시작 전이라 PM 권한으로 시드 글 10개 INSERT.
 *
 * <p>커뮤팀이 진짜 작업 시작 시점에 이 컴포넌트는 제거 후보가 된다. dev 데이터라 운영 영향 없음.
 *
 * <p>시드 내용은 외국인 노동자의 실제 고민(최저임금 미달, 초과근무, 임금체불, 비자, 계약서 등)을
 * 모방해 챗봇 답변이 자연스럽게 매칭되도록 의도적으로 구성.
 */
@Slf4j
@Profile("dev")
@Component
@RequiredArgsConstructor
public class DevDataInitializer implements ApplicationRunner {

    // 데모 작성자 UUID — 다양한 작성자 느낌을 위해 3개 분리
    private static final String DEMO_USER_1 = "00000000-0000-0000-0000-000000000001";
    private static final String DEMO_USER_2 = "00000000-0000-0000-0000-000000000002";
    private static final String DEMO_USER_3 = "00000000-0000-0000-0000-000000000003";

    private final PostRepository postRepository;
    private final CommentRepository commentRepository;

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        seedPostsIfEmpty();
        seedCommentsIfEmpty();
    }

    /**
     * posts 가드 — 비어있을 때만 글 10개 시드.
     *
     * <p>ApplicationRunner는 부팅마다 호출되므로, 가드 없이는 재기동 때마다 같은 글이
     * 누적 INSERT된다. K8s 파드 재기동/재배포 시점에도 안전하도록 멱등성 유지.
     */
    private void seedPostsIfEmpty() {
        long existing = postRepository.count();
        if (existing > 0) {
            log.info("[dev-seed] 커뮤니티 글 이미 {}개 존재 — posts 시드 스킵", existing);
            return;
        }

        List<Post> seedPosts = List.of(
                buildPost(
                        DEMO_USER_1, PostCategory.JOB,
                        "시급 9,000원 받고 일했는데 최저임금 미달인가요?",
                        "베트남에서 온 외국인입니다. 사장님이 시급 9,000원이라고 했는데 알고 보니 한국 최저임금보다 낮은 것 같아요. 같은 경험 있는 분 계시면 어떻게 해결했는지 알려주세요."
                ),
                buildPost(
                        DEMO_USER_2, PostCategory.JOB,
                        "주 60시간 근무 강요 + 초과근무 수당 없음",
                        "근로계약서엔 주 50시간이라고 적혀있지만 실제로는 60시간을 일해요. 초과근무 수당도 못 받고 있는데 이게 합법인가요? 신고하면 어떻게 되나요?"
                ),
                buildPost(
                        DEMO_USER_3, PostCategory.VISA,
                        "한국어를 잘 못해서 계약서 못 읽고 사인했어요",
                        "E-9 비자로 처음 한국에 왔는데 계약서 내용을 잘 모르는 채로 그냥 사인했어요. 나중에 보니 불리한 조건이 많네요. 비슷한 경험 있는 분?"
                ),
                buildPost(
                        DEMO_USER_1, PostCategory.JOB,
                        "외국인이라고 임금 체불 당하고 있어요",
                        "3개월치 월급을 안 줬어요. 사장님이 다음 달에 준다고 계속 미루기만 합니다. 신고하면 비자에 문제 생길까봐 걱정되는데 다들 어떻게 하셨나요?"
                ),
                buildPost(
                        DEMO_USER_2, PostCategory.QUESTION,
                        "근로계약서를 안 써준대요. 어떻게 해야 하나요?",
                        "입사한 지 한 달 됐는데 사장님이 근로계약서 안 써준다고 합니다. 한국에서는 안 써주는 게 정상인가요? 법적으로 문제 없는지 궁금합니다."
                ),
                buildPost(
                        DEMO_USER_3, PostCategory.VISA,
                        "E-9 비자 사업장 변경 가능한가요?",
                        "지금 회사 조건이 너무 안 좋아서 다른 곳으로 옮기고 싶은데 비자 때문에 걱정입니다. 사업장 변경 절차 아시는 분 도움 부탁드려요."
                ),
                buildPost(
                        DEMO_USER_1, PostCategory.QUESTION,
                        "최저임금법은 외국인에게도 적용되나요?",
                        "외국인은 최저임금 안 받아도 된다고 사장님이 말하는데 진짜인가요? 법령 잘 아시는 분 알려주세요."
                ),
                buildPost(
                        DEMO_USER_2, PostCategory.LIFE_INFO,
                        "외국인근로자지원센터 도움 받은 후기",
                        "임금 체불 문제로 1644-0644에 전화했더니 다국어 상담을 받을 수 있었어요. 정말 큰 도움이 됐습니다. 비슷한 상황이라면 꼭 전화해보세요."
                ),
                buildPost(
                        DEMO_USER_3, PostCategory.LIFE_INFO,
                        "월급 받자마자 본국 송금하는 분들 환율 팁",
                        "매월 송금하시는데 환율이 안 좋을 때는 한꺼번에 안 보내고 며칠 나눠서 보내면 좋아요. 송금 수수료도 은행마다 비교해보세요."
                ),
                buildPost(
                        DEMO_USER_1, PostCategory.QUESTION,
                        "주 52시간 초과근무 신고는 어디에 하나요?",
                        "주 52시간을 넘게 일하고 있는데 어디에 신고해야 하나요? 고용노동부 1350에 연락하면 되나요? 익명 신고도 가능한가요?"
                )
        );

        postRepository.saveAll(seedPosts);
        log.info("[dev-seed] 커뮤니티 글 {}개 시드 완료", seedPosts.size());
    }

    /**
     * comments 가드 — 비어있을 때만 댓글 시드.
     *
     * <p>posts 가드와 분리한 이유: posts는 이미 시드돼 있는데 comments만 추가하고 싶은
     * 상황(이번 PR 같은 케이스)을 지원하기 위해. posts 시드 직후엔 영속화된 Post 객체가
     * DB에 박혀있고, 여기선 그 객체들을 DB에서 다시 SELECT해 댓글을 매핑한다.
     *
     * <p>댓글이 매칭될 글이 없으면(=posts도 비어있으면) 시드 자체를 패스.
     */
    private void seedCommentsIfEmpty() {
        long existing = commentRepository.count();
        if (existing > 0) {
            log.info("[dev-seed] 커뮤니티 댓글 이미 {}개 존재 — comments 시드 스킵", existing);
            return;
        }

        // 삽입(시드) 순서로 정렬해 가져온다. dialog[]가 글 순서에 위치 결합돼 있어, posts와 별도 run으로
        // 댓글만 시드할 때(위 주석 참고) findAll()의 비결정적 스캔 순서로 글-댓글 짝이 어긋나지 않게 한다.
        List<Post> posts = postRepository.findAll(Sort.by(Sort.Direction.ASC, "id"));
        if (posts.isEmpty()) {
            log.info("[dev-seed] 커뮤니티 글 0개 — comments 시드 스킵");
            return;
        }

        List<Comment> seedComments = buildComments(posts);
        commentRepository.saveAll(seedComments);
        log.info("[dev-seed] 커뮤니티 댓글 {}개 시드 완료", seedComments.size());
    }

    private Post buildPost(String userPublicId, PostCategory category, String title, String content) {
        return Post.builder()
                .publicId(UUID.randomUUID().toString())
                .userPublicId(userPublicId)
                .category(category)
                .language("ko")
                .title(title)
                .content(content)
                .build();
    }

    /**
     * 각 시드 글마다 댓글 2개씩 (총 20개) 생성.
     *
     * <p>챗봇 시나리오 ④ "비슷한 경험 한 사람?" 답변이 풍부해지도록, 외국인 노동자의
     * 실전 답변 톤(다국어 상담 안내, 법령 인용, 신고 절차, 본인 경험)으로 구성.
     * MCP2가 posts와 comments를 JOIN해 본문+댓글 모두에서 키워드 매칭한다.
     *
     * <p>저장 순서: posts saveAll → id 박힘 → comments 빌드(post 참조) → saveAll.
     * post.commentCount는 도메인 메서드로 정합 유지 (트랜잭션 dirty checking).
     */
    private List<Comment> buildComments(List<Post> savedPosts) {
        List<Comment> comments = new ArrayList<>();

        // 시드 글 순서대로 댓글 2개씩 (총 20개)
        String[][] dialog = {
                // post 1 — 시급 9,000원 미달
                {DEMO_USER_2, "저도 작년에 똑같은 일 겪었어요. 노동부 1350에 전화해 신고했더니 차액 다 받았어요. 비자 영향 전혀 없었습니다."},
                {DEMO_USER_3, "2026년 한국 최저시급은 10,030원이에요. 시급 9,000원은 명백한 최저임금법 위반입니다."},
                // post 2 — 주 60시간 강요
                {DEMO_USER_1, "초과근무는 통상임금의 1.5배를 지급해야 합법이에요. 안 주면 임금체불로 신고 가능합니다."},
                {DEMO_USER_3, "근로감독관에게 신고하면 시정명령 나와요. 익명 신고도 가능합니다. 증거(출퇴근 기록)는 꼭 챙기세요."},
                // post 3 — 한국어 못해서 사인
                {DEMO_USER_2, "외국인근로자지원센터 1577-0071에서 다국어 계약서 검토를 무료로 해줘요. 베트남어, 태국어 등 다 됩니다."},
                {DEMO_USER_1, "저도 처음에 그랬는데, 불리한 조건이 명백하면 일부 조항 무효 판정 받은 사례가 있어요. 대한법률구조공단 132에 무료 상담받아보세요."},
                // post 4 — 임금체불
                {DEMO_USER_2, "임금체불 신고는 비자에 전혀 영향 없어요. 안심하고 1350 노동부에 전화하세요. 통역도 지원됩니다."},
                {DEMO_USER_3, "체당금 제도라고, 사업주가 못 줘도 정부가 대신 지급해주는 제도가 있어요. 근로복지공단에서 신청 가능합니다."},
                // post 5 — 근로계약서 미작성
                {DEMO_USER_1, "근로기준법 §17 위반입니다. 500만원 이하 벌금 사안이에요. 계약서 작성을 거부하는 것 자체가 신고 사유."},
                {DEMO_USER_3, "지금이라도 서면 요구하세요. 거부하면 1350에 신고. 카톡이나 문자로 근무 조건 합의 내용 남겨두면 증거 됩니다."},
                // post 6 — E-9 사업장 변경
                {DEMO_USER_1, "원래 3년간 3회 제한이지만, 사업주 귀책(임금체불·폭언 등)이면 횟수에 포함되지 않아요. 증거 모아서 고용센터에 신청하세요."},
                {DEMO_USER_2, "고용센터에서 사업장 변경 신청서를 받으세요. 절차 안내는 외국인력상담센터 1577-0071에 다국어로 받을 수 있습니다."},
                // post 7 — 최저임금 외국인 적용
                {DEMO_USER_3, "당연히 적용됩니다. 국적과 무관해요. 사장님이 거짓말한 거예요. 절대 속지 마세요."},
                {DEMO_USER_2, "최저임금법은 모든 근로자에게 동일하게 적용돼요. 외국인 차별은 명백히 위법입니다. 신고하면 차액 받을 수 있어요."},
                // post 8 — 지원센터 후기
                {DEMO_USER_1, "저도 도움 받았어요. 진짜 친절하고, 한국어 못해도 모국어 통역사 연결해줘요. 강력 추천합니다."},
                {DEMO_USER_3, "정보 감사합니다. 저도 비슷한 문제로 다음 주에 전화해볼게요. 1644-0644 메모해뒀습니다."},
                // post 9 — 환율 송금 팁
                {DEMO_USER_2, "맞아요. 저는 KRW-VND 환율 18.5 넘으면 보내고, 그 아래면 며칠 기다려요. 매일 환율 알림 받으니 편해요."},
                {DEMO_USER_1, "송금 앱 비교 꼭 해보세요. 수수료가 은행보다 훨씬 싼 곳들이 많아요. 환율 우대도 받을 수 있고요."},
                // post 10 — 주 52시간 신고
                {DEMO_USER_2, "고용노동부 1350으로 신고하시면 됩니다. 익명 가능하고 비자에 전혀 영향 없어요."},
                {DEMO_USER_3, "온라인 신고도 가능합니다. 고용노동부 홈페이지에서 '민원마당 → 임금체불 진정' 메뉴 이용하세요."},
        };

        int idx = 0;
        int maxComments = dialog.length;  // 방어 — posts 수가 예상보다 적/많아도 dialog 범위 초과 방지
        for (Post post : savedPosts) {
            // 글 1개당 댓글 2개씩 (위 배열 순서)
            for (int i = 0; i < 2; i++) {
                if (idx >= maxComments) {
                    return comments;  // dialog 소진 시 종료
                }
                comments.add(Comment.builder()
                        .post(post)
                        .userPublicId(dialog[idx][0])
                        .parentId(null)
                        .content(dialog[idx][1])
                        .build());
                post.increaseCommentCount();
                idx++;
            }
        }
        return comments;
    }
}
