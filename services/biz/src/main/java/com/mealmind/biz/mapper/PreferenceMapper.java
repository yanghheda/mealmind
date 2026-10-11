package com.mealmind.biz.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.mealmind.biz.entity.Preference;

import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Param;

/** 偏好记忆（M4 起含完整实体；退出登录时物理删除该会话的偏好）。 */
public interface PreferenceMapper extends BaseMapper<Preference> {

    @Delete("DELETE FROM preference WHERE session_id = #{sessionId}")
    int deleteBySessionId(@Param("sessionId") String sessionId);
}