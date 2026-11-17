package com.teamplespace.platform.security;

import java.util.UUID;

// 요청한 사용자와 브라우저 편집 세션
// 운영 인증이 생기기 전까지 호출자가 직접 만들어 넘긴다
public record Actor(UUID userId, UUID sessionId) {
}
