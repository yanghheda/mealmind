package com.mealmind.biz.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.mealmind.biz.entity.CartItem;

import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Param;

public interface CartItemMapper extends BaseMapper<CartItem> {

    /** 清空某会话下指定商家的全部条目（cart_item 无 merchant_id，需 join dish）。 */
    @Delete("DELETE ci FROM cart_item ci JOIN dish d ON ci.dish_id = d.id "
            + "WHERE ci.session_id = #{sessionId} AND d.merchant_id = #{merchantId}")
    int deleteByMerchant(@Param("sessionId") String sessionId, @Param("merchantId") String merchantId);
}
