package com.mealmind.biz.mapper;

import java.util.List;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.mealmind.biz.entity.Merchant;

import org.apache.ibatis.annotations.Select;

public interface MerchantMapper extends BaseMapper<Merchant> {

    /** 品类枚举：接口 5 的 category 参数校验用。 */
    @Select("SELECT DISTINCT category FROM merchant ORDER BY category")
    List<String> selectDistinctCategories();
}
