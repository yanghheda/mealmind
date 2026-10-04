package com.mealmind.biz.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

/** 菜品标签 dish_tag（复合主键，仅查询）。 */
@TableName("dish_tag")
public class DishTag {

    @TableId(value = "dish_id", type = IdType.INPUT)
    private String dishId;
    private String tag;
    private String tagType;

    public String getDishId() {
        return dishId;
    }

    public void setDishId(String dishId) {
        this.dishId = dishId;
    }

    public String getTag() {
        return tag;
    }

    public void setTag(String tag) {
        this.tag = tag;
    }

    public String getTagType() {
        return tagType;
    }

    public void setTagType(String tagType) {
        this.tagType = tagType;
    }
}
