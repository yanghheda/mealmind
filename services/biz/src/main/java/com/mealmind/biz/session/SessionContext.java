package com.mealmind.biz.session;

import com.mealmind.biz.common.BizException;
import com.mealmind.biz.common.ErrorCode;
import com.mealmind.biz.entity.VisitorSession;

/**
 * 请求级会话上下文：由 {@link SessionInterceptor} 在鉴权通过后写入，请求结束清理。
 */
public final class SessionContext {

    private static final ThreadLocal<VisitorSession> HOLDER = new ThreadLocal<>();

    private SessionContext() {
    }

    public static void set(VisitorSession session) {
        HOLDER.set(session);
    }

    public static VisitorSession get() {
        VisitorSession session = HOLDER.get();
        if (session == null) {
            throw new BizException(ErrorCode.UNAUTHORIZED);
        }
        return session;
    }

    public static String sessionId() {
        return get().getId();
    }

    public static void clear() {
        HOLDER.remove();
    }
}
