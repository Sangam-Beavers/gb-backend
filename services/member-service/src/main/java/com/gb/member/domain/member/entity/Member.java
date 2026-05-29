package com.gb.member.domain.member.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.AccessLevel;
import lombok.Builder;
import java.time.LocalDateTime;

@Entity
@Table(name = "members")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Member {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true)
    private String email;       // 로그인 아이디

    @Column(nullable = false)
    private String password;    // 암호화된 비밀번호

    @Column(nullable = false)
    private String name;        // 이름

    @Column(nullable = false, unique = true)
    private String nickname;    // 닉네임 (중복 안 됨)

    @Column(nullable = false)
    private String nationality; // 국적

    @Column(nullable = false)
    private String language;    // 주 사용 언어

    @Column(nullable = false, updatable = false)
    private LocalDateTime createdAt; // 가입 시각

    @PrePersist
    public void prePersist() {
        this.createdAt = LocalDateTime.now();
    }

    @Builder
    public Member(String email, String password, String name,
                  String nickname, String nationality, String language) {
        this.email = email;
        this.password = password;
        this.name = name;
        this.nickname = nickname;
        this.nationality = nationality;
        this.language = language;
    }
}