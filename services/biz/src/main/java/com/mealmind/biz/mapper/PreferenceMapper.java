package com.mealmind.biz.mapper;

import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Param;

/**
 * 偏好记忆：M1 仅需退出登录时清除该会话的偏好（接口 3），故不建实体。
 */
public interface PreferenceMapper {

    @Delete("DELETE FROM preference WHERE session_id = #{sessionId}")
    int deleteBySessionId(@Param("sessionId") String sessionId);
}
