package com.mealmind.biz.session;

import com.mealmind.biz.common.BizException;
import com.mealmind.biz.common.ErrorCode;
import com.mealmind.biz.entity.VisitorSession;
import com.mealmind.biz.service.SessionService;

import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * 会话鉴权拦截器：读取 X-Session-Id，校验会话存在且为 ACTIVE；
 * 失败统一抛 UNAUTHORIZED（由 GlobalExceptionHandler 转 401）。
 */
@Component
public class SessionInterceptor implements HandlerInterceptor {

    public static final String HEADER = "X-Session-Id";

    private final SessionService sessionService;

    public SessionInterceptor(SessionService sessionService) {
        this.sessionService = sessionService;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        String sessionId = request.getHeader(HEADER);
        if (sessionId == null || sessionId.isBlank()) {
            throw new BizException(ErrorCode.UNAUTHORIZED);
        }
        VisitorSession session = sessionService.findActive(sessionId);
        if (session == null) {
            throw new BizException(ErrorCode.UNAUTHORIZED);
        }
        SessionContext.set(session);
        return true;
    }

    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response, Object handler, Exception ex) {
        SessionContext.clear();
    }
}
