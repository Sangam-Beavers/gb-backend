package com.gb.community.domain.post.repository;

import com.gb.community.domain.post.entity.Post;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Post 엔티티 Repository — <b>최소 스켈레톤</b>.
 *
 * <p>본 PR 범위는 시드 데이터 작성 + ddl-auto 트리거용. 실제 CRUD API에 필요한
 * 검색·페이지네이션 메서드는 커뮤팀이 본격 작업 시점에 추가한다.
 *
 * <p>챗봇 MCP2는 이 Repository를 호출하지 않고 mcp_reader 계정으로 MySQL을
 * 직접 SELECT한다 (ai-chatbot-mcp.md §9).
 */
public interface PostRepository extends JpaRepository<Post, Long> {

    Optional<Post> findByPublicId(String publicId);
}
