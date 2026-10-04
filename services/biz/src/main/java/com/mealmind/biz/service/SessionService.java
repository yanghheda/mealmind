package com.mealmind.biz.service;

import java.security.SecureRandom;
import java.util.UUID;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.mealmind.biz.common.BizException;
import com.mealmind.biz.common.ErrorCode;
import com.mealmind.biz.config.AppProperties;
import com.mealmind.biz.dto.SessionGuestResponse;
import com.mealmind.biz.dto.SessionMeResponse;
import com.mealmind.biz.entity.CartItem;
import com.mealmind.biz.entity.VisitorSession;
import com.mealmind.biz.mapper.CartItemMapper;
import com.mealmind.biz.mapper.PreferenceMapper;
import com.mealmind.biz.mapper.VisitorSessionMapper;
import com.mealmind.biz.session.SessionContext;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 游客会话：创建、校验、查询、退出。
 */
@Service
public class SessionService {

    private static final Logger log = LoggerFactory.getLogger(SessionService.class);
    private static final String STATUS_ACTIVE = "ACTIVE";
    private static final String STATUS_REVOKED = "REVOKED";
    private static final char[] SUFFIX_ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789".toCharArray();

    private final VisitorSessionMapper visitorSessionMapper;
    private final CartItemMapper cartItemMapper;
    private final PreferenceMapper preferenceMapper;
    private final AppProperties properties;
    private final SecureRandom random = new SecureRandom();

    public SessionService(VisitorSessionMapper visitorSessionMapper,
                          CartItemMapper cartItemMapper,
                          PreferenceMapper preferenceMapper,
                          AppProperties properties) {
        this.visitorSessionMapper = visitorSessionMapper;
        this.cartItemMapper = cartItemMapper;
        this.preferenceMapper = preferenceMapper;
        this.properties = properties;
    }

    /** 接口 1：创建游客身份。 */
    public SessionGuestResponse createGuest() {
        try {
            VisitorSession session = new VisitorSession();
            session.setId(UUID.randomUUID().toString().replace("-", ""));
            session.setNickname(properties.getNicknamePrefix() + randomSuffix());
            java.time.LocalDateTime now = java.time.LocalDateTime.now();
            session.setCreatedAt(now);
            session.setLastActiveAt(now);
            session.setStatus(STATUS_ACTIVE);
            visitorSessionMapper.insert(session);
            return new SessionGuestResponse(session.getId(), session.getNickname(), session.getCreatedAt());
        } catch (Exception ex) {
            log.error("create guest session failed", ex);
            throw new BizException(ErrorCode.SESSION_CREATE_FAILED);
        }
    }

    /** 鉴权用：仅返回 ACTIVE 会话，否则 null。 */
    public VisitorSession findActive(String sessionId) {
        VisitorSession session = visitorSessionMapper.selectById(sessionId);
        if (session == null || !STATUS_ACTIVE.equals(session.getStatus())) {
            return null;
        }
        return session;
    }

    /** 接口 2：查询当前登录态。 */
    public SessionMeResponse me() {
        VisitorSession session = SessionContext.get();
        return new SessionMeResponse(session.getId(), session.getNickname(),
                session.getCreatedAt(), session.getLastActiveAt(), session.getStatus());
    }

    /** 接口 3：退出登录，置 REVOKED 并清空该会话的购物车与偏好。 */
    @Transactional
    public void revoke() {
        String sessionId = SessionContext.sessionId();
        cartItemMapper.delete(new QueryWrapper<CartItem>().eq("session_id", sessionId));
        preferenceMapper.deleteBySessionId(sessionId);
        visitorSessionMapper.update(null,
                new UpdateWrapper<VisitorSession>()
                        .eq("id", sessionId)
                        .set("status", STATUS_REVOKED)
                        .set("last_active_at", java.time.LocalDateTime.now()));
    }

    private String randomSuffix() {
        StringBuilder sb = new StringBuilder(4);
        for (int i = 0; i < 4; i++) {
            sb.append(SUFFIX_ALPHABET[random.nextInt(SUFFIX_ALPHABET.length)]);
        }
        return sb.toString();
    }
}
