package com.beomsu.pay.member;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

/** 회원 저장소. 로그인(이메일 조회)·가입 중복검사(email 존재 확인)에 쓰인다. */
public interface MemberRepository extends JpaRepository<Member, Long> {

    Optional<Member> findByEmail(String email);

    boolean existsByEmail(String email);
}
