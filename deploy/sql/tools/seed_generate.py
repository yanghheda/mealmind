#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""MealMind M1 种子数据生成器（Python 3.12，仅标准库）。

运行本脚本即可就地（重）生成 deploy/sql/20_seed.sql：
    python3 deploy/sql/tools/seed_generate.py

固定随机种子 random.seed(20261004)，同样的输入必得逐字节一致的输出。
12 家精选商家 / 菜单来自 tools/curated_base.json（由 prototype/mock.js 程序化导出），
m13–m40 为程序化生成的虚构商家。禁止真实品牌。
"""

import json
import os
import random
import re
from urllib.parse import quote

SEED = 20261004
HERE = os.path.dirname(os.path.abspath(__file__))
BASE_JSON = os.path.join(HERE, "curated_base.json")
OUT_SQL = os.path.normpath(os.path.join(HERE, "..", "20_seed.sql"))

# ---------------------------------------------------------------------------
# 基础工具
# ---------------------------------------------------------------------------

def img(prompt):
    """与 prototype/mock.js 的内置图像服务构造方式保持一致。"""
    return (
        "https://trae-api-cn.mchost.guru/api/ide/v1/text_to_image?prompt="
        + quote(prompt, safe="")
        + "&image_size=square"
    )


_PAREN = re.compile(r"（[^）]*）")


def dish_img(name):
    clean = _PAREN.sub("", str(name or ""))
    return img(clean + "，美食摄影，特写，诱人，中式")


def esc_str(v):
    if v is None:
        return "NULL"
    return "'" + str(v).replace("\\", "\\\\").replace("'", "''") + "'"


def num(v):
    return "NULL" if v is None else str(v)


def money(v):
    return "NULL" if v is None else ("%.2f" % float(v))


# ---------------------------------------------------------------------------
# 解析 prototype 字段
# ---------------------------------------------------------------------------

def parse_price(p):
    s = str(p).replace("¥", "").replace("起", "").strip()
    return float(s)


def parse_sales(s):
    s = str(s)
    if "月售" not in s:
        return 0
    digits = "".join(ch for ch in s if ch.isdigit())
    return int(digits) if digits else 0


def parse_km(t):
    m = re.search(r"([0-9.]+)", str(t))
    return int(round(float(m.group(1)) * 1000)) if m else 0


def parse_min(t):
    digits = "".join(ch for ch in str(t) if ch.isdigit())
    return int(digits) if digits else 0


def parse_fee(t):
    if "免" in str(t):
        return 0.0
    m = re.search(r"([0-9.]+)", str(t))
    return float(m.group(1)) if m else 0.0


# ---------------------------------------------------------------------------
# 品类与营业时间
# ---------------------------------------------------------------------------

CATEGORIES = [
    "炸鸡串串", "鸭脖卤味", "港式茶点", "湘菜快餐", "麻辣烫", "炸鸡汉堡",
    "米粉面食", "卤味凉菜", "轻食沙拉", "日式简餐", "奶茶饮品", "龙虾烧烤",
]

CURATED_CATEGORY = {
    "m1": "炸鸡串串", "m2": "鸭脖卤味", "m3": "港式茶点", "m4": "湘菜快餐",
    "m5": "麻辣烫", "m6": "炸鸡汉堡", "m7": "米粉面食", "m8": "卤味凉菜",
    "m9": "轻食沙拉", "m10": "日式简餐", "m11": "奶茶饮品", "m12": "龙虾烧烤",
}

CURATED_TIMES = {
    "m1": ("10:30:00", "23:00:00"), "m2": ("11:00:00", "23:30:00"),
    "m3": ("09:30:00", "21:30:00"), "m4": ("10:00:00", "22:00:00"),
    "m5": ("10:30:00", "22:30:00"), "m6": ("10:00:00", "23:00:00"),
    "m7": ("06:30:00", "21:00:00"), "m8": ("09:00:00", "21:00:00"),
    "m9": ("09:30:00", "20:30:00"), "m10": ("11:00:00", "22:30:00"),
    "m11": ("09:00:00", "22:00:00"), "m12": ("16:00:00", "02:00:00"),
}

CAT_TIMES = {
    "炸鸡串串": ("10:30:00", "23:30:00"), "鸭脖卤味": ("10:00:00", "23:00:00"),
    "港式茶点": ("09:00:00", "21:30:00"), "湘菜快餐": ("10:00:00", "22:00:00"),
    "麻辣烫": ("10:30:00", "22:30:00"), "炸鸡汉堡": ("10:00:00", "23:00:00"),
    "米粉面食": ("06:30:00", "21:30:00"), "卤味凉菜": ("09:00:00", "21:00:00"),
    "轻食沙拉": ("09:30:00", "20:30:00"), "日式简餐": ("11:00:00", "22:30:00"),
    "奶茶饮品": ("09:00:00", "22:00:00"), "龙虾烧烤": ("16:00:00", "02:00:00"),
}

# 生成商家的菜品池：品类 -> [(分类名, 菜名, 描述, 价格, 是否套餐), ...]
POOLS = {
    "炸鸡串串": [
        ("招牌串串", "招牌炸串双人餐", "2 荤 2 素含招牌品，适合 2 人", 30.9, True),
        ("招牌串串", "香辣鸡翅（4 只）", "炭火风味，香辣入味", 15.8, False),
        ("招牌串串", "劲爆鸡米花", "现炸酥脆，一口一个", 12.8, False),
        ("招牌串串", "秘制炸鸡架", "秘制腌料，越啃越香", 16.8, False),
        ("炸物小吃", "孜然羊肉串（10 串）", "现串现烤，孜然味足", 29.9, False),
        ("炸物小吃", "脆皮炸鸡腿", "外脆里嫩，锁汁多汁", 8.8, False),
        ("炸物小吃", "黄金炸年糕", "外脆内糯，甜辣可选", 6.9, False),
        ("炸物小吃", "香酥鸡排", "整块鸡肉现炸", 12.9, False),
        ("炸物小吃", "炸茄盒（6 个）", "现炸茄子夹肉馅", 9.9, False),
        ("炸物小吃", "炸香菇（8 个）", "菌香浓郁，酥脆不腻", 9.9, False),
        ("套餐", "单人嗨吃套餐", "2 荤 2 素，一人食刚好", 19.9, True),
        ("套餐", "双人分享套餐", "4 荤 4 素，够两人吃", 39.9, True),
        ("套餐", "全家福炸串桶", "混合炸串配薯条", 49.9, True),
        ("饮品", "冰镇酸梅汤", "酸甜解腻", 6.0, False),
        ("饮品", "柠檬气泡水", "清爽带气", 7.0, False),
        ("饮品", "可乐（中杯）", "冰镇听装", 5.0, False),
        ("小吃", "拍黄瓜", "蒜香爽口", 10.0, False),
        ("小吃", "花生毛豆拼盘", "下酒小菜", 12.0, False),
        ("小吃", "香菜拌木耳", "清爽解腻", 8.8, False),
        ("小吃", "炸藕片", "薄脆爽口", 8.8, False),
    ],
    "鸭脖卤味": [
        ("招牌卤味", "招牌卤鸭脖（微辣）", "秘制卤汁，微辣回甘", 18.0, False),
        ("招牌卤味", "招牌鸭头", "卤香浓郁，越啃越香", 9.0, False),
        ("招牌卤味", "卤鸡爪（5 只）", "软糯脱骨", 16.8, False),
        ("招牌卤味", "卤鸭翅（2 只）", "卤香入骨", 12.8, False),
        ("素菜凉菜", "招牌毛豆", "盐水毛豆，越吃越香", 9.8, False),
        ("素菜凉菜", "卤藕片", "卤香脆爽", 9.9, False),
        ("素菜凉菜", "凉拌木耳", "酸辣爽口", 8.8, False),
        ("素菜凉菜", "卤海带结", "软糯入味", 6.9, False),
        ("组合套餐", "追剧卤味拼盘（4 拼）", "鸭脖 + 鸭头 + 藕片 + 毛豆", 35.8, True),
        ("组合套餐", "下酒拼盘（3 拼）", "三款卤味拼盘", 25.8, True),
        ("饮品", "冰镇酸梅汤", "酸甜解腻", 6.0, False),
        ("小吃", "花生毛豆拼盘", "下酒小菜", 12.0, False),
        ("小吃", "凉拌黄瓜", "蒜香爽口", 8.0, False),
        ("招牌卤味", "卤鸭肠", "爽脆弹牙", 14.8, False),
        ("招牌卤味", "卤鸭锁骨", "肉多有嚼劲", 13.8, False),
        ("招牌卤味", "卤鸭舌", "细腻入味", 18.8, False),
        ("素菜凉菜", "卤千张", "豆香浓郁", 8.8, False),
        ("素菜凉菜", "香菜拌豆干", "清爽豆香", 8.8, False),
        ("小吃", "卤鸡胗", "紧实弹牙", 13.8, False),
        ("小吃", "卤豆干", "豆香入味", 7.9, False),
    ],
    "港式茶点": [
        ("手工茶点", "虾饺（4 只）", "水晶皮手工现包", 14.8, False),
        ("手工茶点", "干蒸烧卖（4 只）", "肉馅饱满", 12.8, False),
        ("手工茶点", "豉汁凤爪", "软糯脱骨", 16.8, False),
        ("手工茶点", "流沙包（3 只）", "咸蛋黄流心", 12.0, False),
        ("粉面饭", "干炒牛河", "镬气足，牛肉给得实在", 26.0, False),
        ("粉面饭", "黯然销魂饭", "叉烧配流心蛋", 28.0, False),
        ("粉面饭", "云吞面", "鲜虾云吞，汤头清鲜", 22.0, False),
        ("粉面饭", "艇仔粥", "料足绵滑", 12.8, False),
        ("饮品", "港式冻奶茶", "茶香浓郁", 12.0, False),
        ("饮品", "冻柠茶", "清爽解腻", 12.0, False),
        ("甜品", "菠萝油", "外皮酥脆，黄油香", 9.9, False),
        ("甜品", "蛋挞（2 只）", "现烤酥皮蛋挞", 8.8, False),
        ("套餐", "下午茶双人餐", "4 款茶点 + 2 杯饮品", 45.9, True),
        ("套餐", "早茶一人食", "3 款茶点 + 1 杯饮品", 25.9, True),
        ("手工茶点", "糯米鸡", "荷叶清香，馅料足", 13.8, False),
        ("手工茶点", "肠粉（鲜虾）", "滑嫩鲜香", 15.8, False),
        ("甜品", "杨枝甘露", "芒果西柚西米", 16.8, False),
        ("小食", "豉油皇鸡脚", "卤香入味", 15.8, False),
        ("小食", "炸云吞（6 只）", "现炸酥脆", 11.8, False),
        ("小食", "香煎萝卜糕", "外焦里嫩", 10.8, False),
    ],
    "湘菜快餐": [
        ("招牌盖码饭", "小炒肉盖码饭", "现炒湘味小炒肉，配足量米饭", 22.0, False),
        ("招牌盖码饭", "辣椒炒蛋饭", "香辣下饭", 18.0, False),
        ("招牌盖码饭", "小炒黄牛肉", "黄牛肉现炒，香辣过瘾", 32.0, False),
        ("招牌盖码饭", "香干回锅肉饭", "肥瘦相间，豆干入味", 24.0, False),
        ("单点小炒", "农家小炒肉", "锅气十足", 26.0, False),
        ("单点小炒", "紫苏黄瓜", "清爽解腻", 12.0, False),
        ("单点小炒", "酸辣鸡杂", "酸辣开胃", 22.0, False),
        ("单点小炒", "干锅花菜", "干香入味", 18.0, False),
        ("汤与凉菜", "紫菜蛋花汤", "清爽开胃", 6.0, False),
        ("汤与凉菜", "凉拌黄瓜", "蒜香爽口", 8.0, False),
        ("主食", "米饭", "香软可口", 2.0, False),
        ("套餐", "湘味双人餐", "2 荤 1 素 + 2 份米饭", 45.9, True),
        ("套餐", "工作日单人餐", "1 荤 1 素 + 米饭", 24.9, True),
        ("饮品", "冰镇酸梅汤", "酸甜解腻", 6.0, False),
        ("招牌盖码饭", "剁椒鱼头饭", "剁椒鲜辣，鱼肉嫩滑", 38.0, False),
        ("单点小炒", "爆炒猪肝", "嫩滑不老", 22.0, False),
        ("单点小炒", "香辣鸭掌", "软糯香辣", 19.8, False),
        ("汤与凉菜", "花生毛豆拼盘", "下饭小菜", 12.0, False),
        ("饮品", "柠檬气泡水", "清爽带气", 7.0, False),
        ("汤与凉菜", "香菜拌木耳", "清爽解腻", 8.8, False),
    ],
    "麻辣烫": [
        ("招牌麻辣烫", "招牌麻辣烫（微辣）", "8 种食材 + 宽粉，微辣可调", 21.8, False),
        ("招牌麻辣烫", "番茄麻辣烫", "番茄汤底，酸甜开胃", 22.8, False),
        ("招牌麻辣烫", "骨汤麻辣烫", "骨汤打底，鲜香浓郁", 20.8, False),
        ("自选加料", "嫩牛肉", "现切嫩牛肉", 8.0, False),
        ("自选加料", "毛肚", "爽脆弹牙", 9.0, False),
        ("自选加料", "午餐肉", "厚切午餐肉", 5.0, False),
        ("自选加料", "鹌鹑蛋（5 个）", "入味小蛋", 3.0, False),
        ("自选加料", "手工宽粉", "久煮不烂", 3.0, False),
        ("套餐", "微辣一人食套餐", "8 种食材 + 宽粉，微辣可调", 21.8, True),
        ("套餐", "双人麻辣烫套餐", "双人份食材，微辣可调", 39.9, True),
        ("饮品", "冰豆奶", "解辣顺口", 6.0, False),
        ("饮品", "酸梅汤", "酸甜解腻", 6.0, False),
        ("自选加料", "油豆皮", "吸汁入味", 5.0, False),
        ("自选加料", "金针菇", "爽滑鲜嫩", 5.0, False),
        ("自选加料", "蟹棒", "鲜甜弹牙", 6.0, False),
        ("自选加料", "鸭血", "嫩滑入味", 6.0, False),
        ("自选加料", "手打虾滑", "虾肉弹牙", 12.0, False),
        ("招牌麻辣烫", "麻辣拌", "少汤多料，麻辣过瘾", 23.8, False),
        ("小吃", "香菜拌木耳", "清爽解腻", 8.8, False),
        ("小吃", "花生毛豆拼盘", "下酒小菜", 12.0, False),
    ],
    "炸鸡汉堡": [
        ("汉堡", "香辣鸡腿堡", "整块鸡腿肉，现炸锁汁", 12.9, False),
        ("汉堡", "藤椒鸡腿堡", "藤椒风味，麻辣清香", 13.9, False),
        ("汉堡", "奥尔良鸡腿堡", "奥尔良腌料，微甜多汁", 13.9, False),
        ("汉堡", "牛肉芝士堡", "厚牛肉饼配芝士", 16.9, False),
        ("炸物", "盐酥鸡", "外酥里嫩", 9.9, False),
        ("炸物", "鸡米花", "一口一个", 10.9, False),
        ("炸物", "黄金薯条", "现炸粗薯条", 8.9, False),
        ("炸物", "香辣鸡翅（4 只）", "香辣入味", 15.8, False),
        ("套餐", "单人炸鸡套餐", "鸡排 + 薯条 + 可乐", 19.9, True),
        ("套餐", "双人分享餐", "2 汉堡 + 炸物拼盘 + 2 饮品", 35.9, True),
        ("饮品", "可乐（中杯）", "冰镇", 5.0, False),
        ("饮品", "柠檬茶", "清爽解腻", 7.0, False),
        ("汉堡", "双层牛堡", "双层牛肉，分量扎实", 18.9, False),
        ("炸物", "炸鸡排", "整块鸡胸现炸", 12.9, False),
        ("炸物", "鸡块（5 块）", "外脆里嫩", 11.9, False),
        ("炸物", "洋葱圈", "酥脆微甜", 8.9, False),
        ("小吃", "炸香菇（8 个）", "菌香酥脆", 9.9, False),
        ("小吃", "香菜拌木耳", "清爽解腻", 8.8, False),
        ("小吃", "凉拌黄瓜", "蒜香爽口", 8.0, False),
        ("小吃", "花生毛豆拼盘", "下酒小菜", 12.0, False),
    ],
    "米粉面食": [
        ("招牌米粉", "麻辣牛肉粉", "大块卤牛肉，麻辣鲜香", 22.8, False),
        ("招牌米粉", "原味肉丝粉", "骨汤清鲜，肉丝嫩滑", 16.8, False),
        ("招牌米粉", "杂酱粉", "酱香浓郁", 18.8, False),
        ("招牌米粉", "酸辣鸡杂粉", "酸辣开胃", 19.8, False),
        ("面食", "手工牛肉面", "现拉面条，牛肉足", 24.8, False),
        ("面食", "红烧排骨面", "排骨酥烂", 22.8, False),
        ("面食", "老坛酸菜面", "酸爽开胃", 19.8, False),
        ("加料", "荷包蛋", "现煎流心", 1.99, False),
        ("加料", "鹌鹑蛋（3 个）", "入味小蛋", 0.1, False),
        ("加料", "牛肉加量", "多加一份卤牛肉", 8.0, False),
        ("加料", "卤蛋", "卤香入味", 2.0, False),
        ("小食", "香菜拌木耳", "清爽解腻", 8.8, False),
        ("小食", "花生毛豆拼盘", "下酒小菜", 12.0, False),
        ("套餐", "招牌米粉套餐", "牛肉粉 + 卤蛋 + 酸梅汤", 28.9, True),
        ("饮品", "冰镇酸梅汤", "酸甜解腻", 6.0, False),
        ("招牌米粉", "番茄牛腩粉", "番茄汤底，牛腩软烂", 24.8, False),
        ("招牌米粉", "猪脚粉", "猪脚软糯，汤头浓", 20.8, False),
        ("招牌米粉", "三鲜米粉", "鲜香清淡", 18.8, False),
        ("面食", "酸豆角肉末粉", "酸辣下饭", 18.8, False),
        ("加料", "自制酸萝卜", "酸脆解腻", 3.0, False),
    ],
    "卤味凉菜": [
        ("招牌泡菜", "招牌泡菜", "老坛发酵，酸辣脆爽", 6.9, False),
        ("招牌泡菜", "酸辣萝卜条", "酸辣脆爽", 5.9, False),
        ("招牌泡菜", "泡椒藕丁", "酸辣开胃", 7.9, False),
        ("招牌泡菜", "酸辣海带丝", "爽脆入味", 6.9, False),
        ("卤味凉菜", "卤鸡爪（5 只）", "软糯脱骨", 16.8, False),
        ("卤味凉菜", "凉拌木耳", "酸辣爽口", 8.8, False),
        ("卤味凉菜", "凉拌腐竹", "豆香爽口", 7.9, False),
        ("卤味凉菜", "香菜拌豆干", "清爽豆香", 8.8, False),
        ("卤味凉菜", "红油耳丝", "脆爽微辣", 15.8, False),
        ("套餐", "爽口三拼", "泡菜 + 木耳 + 腐竹", 19.9, True),
        ("套餐", "卤味拼盘（3 拼）", "三款卤味拼盘", 25.8, True),
        ("小吃", "凉拌黄瓜", "蒜香爽口", 6.9, False),
        ("小吃", "花生毛豆拼盘", "下酒小菜", 12.0, False),
        ("饮品", "冰镇酸梅汤", "酸甜解腻", 6.0, False),
        ("饮品", "柠檬气泡水", "清爽带气", 7.0, False),
        ("卤味凉菜", "卤鸭胗", "紧实弹牙", 14.8, False),
        ("卤味凉菜", "凉拌牛百叶", "爽脆入味", 18.8, False),
        ("卤味凉菜", "口水鸡", "麻辣鲜香", 22.8, False),
        ("卤味凉菜", "盐水花生", "咸香入味", 8.8, False),
        ("招牌泡菜", "海带丝", "清爽入味", 6.9, False),
    ],
    "轻食沙拉": [
        ("能量碗", "鸡胸肉能量碗", "高蛋白低脂，配糙米与当季蔬菜", 28.0, False),
        ("能量碗", "牛油果虾仁碗", "牛油果配鲜虾，清爽饱腹", 32.0, False),
        ("能量碗", "藜麦蔬菜碗", "藜麦配当季蔬菜，低卡饱腹", 24.0, False),
        ("能量碗", "三文鱼沙拉碗", "三文鱼配沙拉，高蛋白", 36.0, False),
        ("沙拉轻食", "鸡胸肉沙拉", "低卡高蛋白", 26.0, False),
        ("沙拉轻食", "牛油果沙拉", "清爽不腻", 26.0, False),
        ("沙拉轻食", "轻食三明治", "全麦面包配鸡胸", 15.8, False),
        ("沙拉轻食", "全麦鸡肉卷", "低脂饱腹", 18.8, False),
        ("沙拉轻食", "水煮蛋（2 只）", "高蛋白", 6.0, False),
        ("套餐", "减脂轻食套餐", "能量碗 + 水煮蛋 + 气泡水", 32.9, True),
        ("套餐", "工作日轻食午餐", "三明治 + 沙拉 + 美式", 29.9, True),
        ("饮品", "青柠气泡水", "清爽无负担", 9.9, False),
        ("饮品", "冷萃美式", "低卡提神", 12.0, False),
        ("能量碗", "高蛋白谷物碗", "鸡胸 + 藜麦 + 蔬菜", 30.0, False),
        ("沙拉轻食", "羽衣甘蓝沙拉", "脆爽低卡", 28.0, False),
        ("沙拉轻食", "烤鸡胸肉", "少油煎烤", 22.0, False),
        ("饮品", "低脂酸奶杯", "低卡酸甜", 12.0, False),
        ("小吃", "香菜拌木耳", "清爽解腻", 8.8, False),
        ("小吃", "花生毛豆拼盘", "下酒小菜", 12.0, False),
        ("小吃", "凉拌黄瓜", "蒜香爽口", 8.0, False),
    ],
    "日式简餐": [
        ("招牌饭", "鳗鱼饭", "现烤蒲烧鳗鱼，酱汁微甜", 38.0, False),
        ("招牌饭", "肥牛饭", "肥牛配洋葱，酱香微甜", 26.0, False),
        ("招牌饭", "亲子丼", "鸡肉滑蛋盖饭", 24.0, False),
        ("招牌饭", "照烧鸡腿饭", "照烧酱香，鸡腿多汁", 26.0, False),
        ("小食", "章鱼小丸子（6 只）", "现做现吃", 12.8, False),
        ("小食", "可乐饼（2 只）", "外酥内绵", 10.0, False),
        ("小食", "日式煎饺（5 只）", "底脆汁多", 13.8, False),
        ("小食", "天妇罗拼盘", "现炸酥脆", 18.8, False),
        ("汤与饮品", "味噌汤", "暖胃鲜香", 6.0, False),
        ("汤与饮品", "乌龙茶", "解腻清爽", 5.0, False),
        ("套餐", "日式双人餐", "鳗鱼饭 + 牛丼 + 小食 + 汤", 68.0, True),
        ("套餐", "日式单人定食", "照烧鸡腿饭 + 味噌汤 + 蒸蛋", 32.9, True),
        ("招牌饭", "牛丼", "肥牛盖饭，分量足", 25.0, False),
        ("招牌饭", "咖喱猪排饭", "咖喱浓郁，猪排酥脆", 32.0, False),
        ("小食", "蒸蛋羹", "嫩滑鲜香", 8.0, False),
        ("刺身", "三文鱼刺身", "新鲜厚切", 42.0, False),
        ("汤与饮品", "可乐（中杯）", "冰镇", 5.0, False),
        ("小吃", "香菜拌木耳", "清爽解腻", 8.8, False),
        ("小吃", "凉拌黄瓜", "蒜香爽口", 8.0, False),
        ("小吃", "花生毛豆拼盘", "下酒小菜", 12.0, False),
    ],
    "奶茶饮品": [
        ("鲜果茶", "满杯橙子", "整颗橙子现切现捣", 15.8, False),
        ("鲜果茶", "杨枝甘露", "芒果西柚西米", 16.8, False),
        ("鲜果茶", "满杯红柚", "西柚清苦回甘", 15.8, False),
        ("鲜果茶", "芝士葡萄", "整颗葡萄配芝士奶盖", 18.8, False),
        ("奶茶", "茉莉奶绿", "茉莉茶香，奶味清爽", 12.8, False),
        ("奶茶", "轻乳茶", "低糖轻负担", 11.8, False),
        ("奶茶", "珍珠奶茶", "Q 弹珍珠", 12.8, False),
        ("奶茶", "芋泥波波奶茶", "芋泥绵密", 15.8, False),
        ("奶茶", "生打椰椰", "椰香浓郁", 16.8, False),
        ("纯茶", "四季春茶", "清爽回甘", 9.9, False),
        ("纯茶", "柠檬茶", "酸甜解腻", 11.8, False),
        ("甜品", "蛋挞（2 只）", "现烤酥皮蛋挞", 8.8, False),
        ("甜品", "轻乳卷", "奶香绵软", 9.9, False),
        ("套餐", "第二杯半价双人饮", "两杯鲜果茶", 25.8, True),
        ("套餐", "下午茶套餐", "奶茶 + 蛋挞", 19.9, True),
        ("鲜果茶", "芒果冰沙", "芒果绵密", 16.8, False),
        ("奶茶", "芝士奶盖茶", "咸香奶盖", 14.8, False),
        ("小吃", "花生毛豆拼盘", "下酒小菜", 12.0, False),
        ("小吃", "凉拌黄瓜", "蒜香爽口", 8.0, False),
        ("小吃", "香菜拌木耳", "清爽解腻", 8.8, False),
    ],
    "龙虾烧烤": [
        ("招牌烧烤", "羊肉串（10 串）", "现串现烤，孜然味足", 29.9, False),
        ("招牌烧烤", "烤五花肉（5 串）", "肥瘦相间，焦香", 19.9, False),
        ("招牌烧烤", "烤茄子", "蒜蓉烤茄子", 12.0, False),
        ("招牌烧烤", "烤韭菜", "鲜嫩多汁", 8.0, False),
        ("招牌烧烤", "烤金针菇", "吸汁入味", 8.0, False),
        ("小龙虾", "蒜蓉小龙虾（1 斤）", "蒜香浓郁，虾肉弹牙", 68.0, False),
        ("小龙虾", "十三香小龙虾（1 斤）", "十三香入味", 68.0, False),
        ("小龙虾", "麻辣小龙虾（1 斤）", "麻辣过瘾", 68.0, False),
        ("下酒小菜", "花生毛豆拼盘", "下酒小菜", 12.0, False),
        ("下酒小菜", "拍黄瓜", "蒜香爽口", 10.0, False),
        ("饮品", "冰镇啤酒", "冰爽解腻", 8.0, False),
        ("饮品", "冰镇酸梅汤", "酸甜解腻", 6.0, False),
        ("套餐", "烤串套餐", "羊肉串 + 五花肉 + 烤蔬菜", 39.9, True),
        ("套餐", "小龙虾双人餐", "小龙虾 + 烤串 + 啤酒", 99.0, True),
        ("招牌烧烤", "烤鸡翅（2 只）", "外焦里嫩", 12.8, False),
        ("招牌烧烤", "烤生蚝（2 只）", "蒜蓉生蚝", 18.0, False),
        ("招牌烧烤", "烤扇贝（3 只）", "蒜蓉粉丝", 21.0, False),
        ("小龙虾", "香辣蟹（1 斤）", "香辣入味", 88.0, False),
        ("下酒小菜", "香菜拌木耳", "清爽解腻", 8.8, False),
        ("小吃", "凉拌黄瓜", "蒜香爽口", 8.0, False),
    ],
}

# ---------------------------------------------------------------------------
# 标签词库（dish_tag 的唯一来源，确定性关键词匹配）
# ---------------------------------------------------------------------------

SPICY_STRONG = ["麻辣", "香辣", "酸辣", "辣", "泡椒", "剁椒", "藤椒",
                "椒麻", "红油", "十三香"]

FLAVOR_RULES = [
    ("酸辣", ["酸辣", "泡菜", "泡椒", "酸豆角"]),
    ("麻辣", ["麻辣", "麻椒", "椒麻", "藤椒", "花椒"]),
    ("卤味", ["卤"]),
    ("咖喱", ["咖喱"]),
    ("孜然", ["孜然", "烧烤", "烤"]),
    ("蒜香", ["蒜"]),
    ("酸甜", ["酸甜", "糖醋"]),
    ("甜口", ["甜", "奶茶", "奶绿", "乳茶", "蛋糕", "蛋挞", "菠萝油",
              "杨枝甘露", "芋泥", "波波", "冰沙", "沙冰", "奶盖", "芝士",
              "轻乳", "乳卷"]),
    ("清淡", ["清淡", "沙拉", "轻食", "水煮", "清汤", "三鲜", "羽衣",
              "茶", "气泡水", "柠檬", "橙"]),
    ("香辣", ["香辣", "辣", "剁椒", "红油"]),
    ("麻酱", ["麻酱", "芝麻酱"]),
    ("咸鲜", ["鲜", "咸", "酱", "骨汤", "高汤", "味噌", "原味", "肉丝", "蛋花"]),
]

INGREDIENT_RULES = [
    ("牛肉", ["牛肉", "牛腩", "肥牛", "牛柳", "牛排", "牛河", "牛丼", "牛百叶"]),
    ("猪肉", ["猪肉", "五花", "回锅肉", "肉丝", "肉末", "排骨", "里脊",
              "肉饼", "培根", "猪脚", "猪排", "猪肝", "午餐肉"]),
    ("鸡肉", ["鸡胸", "鸡腿", "鸡翅", "鸡排", "鸡爪", "鸡杂", "鸡胗",
              "鸡架", "鸡米花", "鸡块", "鸡", "凤爪"]),
    ("鸭肉", ["鸭脖", "鸭头", "鸭翅", "鸭肠", "鸭舌", "鸭掌", "鸭锁骨",
              "鸭胗", "鸭血", "鸭"]),
    ("虾", ["虾"]),
    ("鱼", ["鳗鱼", "三文鱼", "鱼头", "鱼豆腐", "鱼", "章鱼"]),
    ("蟹", ["蟹"]),
    ("蛋", ["鹌鹑蛋", "鸡蛋", "水煮蛋", "蛋挞", "荷包蛋", "蒸蛋", "蛋"]),
    ("豆制品", ["豆腐", "臭豆腐", "油豆皮", "豆皮", "腐竹", "豆干", "千张",
                "豆奶", "豆浆", "毛豆"]),
    ("菌菇", ["菇", "木耳", "香菇", "金针菇", "口蘑", "平菇"]),
    ("面", ["拉面", "云吞面", "炒面", "泡面", "方便面", "三明治", "烧卖", "面"]),
    ("米", ["米饭", "糯米", "米粉", "河粉", "糙米", "藜麦", "寿司", "饭", "米"]),
    ("粉", ["米粉", "河粉", "宽粉", "粉丝", "粉条", "粉"]),
    ("蔬菜", ["蔬菜", "黄瓜", "藕", "土豆", "番茄", "茄子", "韭菜", "花菜",
              "萝卜", "白菜", "菠菜", "生菜", "甘蓝", "羽衣", "芹菜", "洋葱",
              "玉米", "豆角", "青椒", "彩椒", "海带", "紫菜", "橙", "柚",
              "芒果", "葡萄", "椰", "牛油果", "柠檬", "茶"]),
    ("花生", ["花生"]),
    ("香菜", ["香菜"]),
    ("葱", ["葱"]),
    ("蒜", ["蒜"]),
    ("辣椒", ["辣椒", "泡椒", "剁椒", "红油", "藤椒", "椒"]),
    ("乳制品", ["奶", "芝士", "黄油", "奶酪", "酸奶", "轻乳", "奶油", "乳茶", "奶盖"]),
    ("内脏", ["毛肚", "鸭肠", "肥肠", "鸡杂", "鸡胗", "鸭胗", "猪肝",
              "牛百叶", "耳丝", "鸭血", "百叶"]),
]

HEALTH_RULES = [
    ("低卡", ["低卡", "轻食", "沙拉", "能量碗", "藜麦", "水煮", "鸡胸",
              "羽衣甘蓝", "低脂", "减脂", "无糖"]),
    ("高蛋白", ["高蛋白", "鸡胸", "牛肉", "牛排", "虾", "蛋", "牛腩",
                "三文鱼", "肥牛", "毛肚"]),
    ("油炸", ["油炸", "炸", "酥", "脆皮", "薯条", "天妇罗", "油条", "煎"]),
    ("高糖", ["奶茶", "糖", "甜", "蛋糕", "杨枝甘露", "芋泥", "波波",
              "奶油", "冰沙", "蛋挞", "菠萝油", "奶绿", "乳茶"]),
    ("高盐", ["卤", "盐", "泡菜", "腌", "酸萝卜", "咸", "海带", "酱",
              "味噌", "腐乳"]),
]

T_SELLING = {"招牌": "招牌", "特价": "特价", "含本店招牌品": "含本店招牌品"}
T_HEALTH = {"低卡": "低卡"}


def derive_tags(name, desc):
    """按 名称 + 描述 的确定性关键词词库派生 (tag, tag_type) 有序列表。"""
    text = str(name) + " " + str(desc or "")

    # SPICY（恰好一个）
    if "不辣" in text:
        spicy = "不辣"
    elif "特辣" in text or "变态辣" in text:
        spicy = "特辣"
    elif "中辣" in text:
        spicy = "中辣"
    elif "微辣" in text:
        spicy = "微辣"
    elif any(k in text for k in SPICY_STRONG):
        spicy = "中辣"
    else:
        spicy = "不辣"

    # FLAVOR（恰好一个，首条命中）
    flavor = None
    for tag, kws in FLAVOR_RULES:
        if any(k in text for k in kws):
            flavor = tag
            break
    if flavor is None:
        flavor = "咸鲜"

    # INGREDIENT（可多个）
    ingredients = []
    for tag, kws in INGREDIENT_RULES:
        if any(k in text for k in kws):
            ingredients.append(tag)
    if not ingredients:
        ingredients = ["蔬菜"]

    # HEALTH（可多个）
    health = []
    for tag, kws in HEALTH_RULES:
        if any(k in text for k in kws):
            health.append(tag)
    if not health:
        if "高蛋白" in ingredients:
            health = ["高蛋白"]
        elif flavor == "清淡":
            health = ["低卡"]
        else:
            health = ["高盐"]

    out = [("SPICY", spicy), ("FLAVOR", flavor)]
    out += [("INGREDIENT", t) for t in ingredients]
    out += [("HEALTH", t) for t in health]
    return out


def tag_rows(dish_id, pairs):
    """去重并给出 (dish_id, tag, tag_type) 行。"""
    seen = {}
    for tt, tg in pairs:
        if tg not in seen:
            seen[tg] = tt
    return [(dish_id, tg, tt) for tg, tt in seen.items()]


# ---------------------------------------------------------------------------
# 生成
# ---------------------------------------------------------------------------

def jitter_price(base, rng):
    p = round(base * rng.uniform(0.95, 1.12), 1)
    if p <= 0:
        p = base
    return p


def build():
    random.seed(SEED)
    rng = random

    with open(BASE_JSON, "r", encoding="utf-8") as f:
        base = json.load(f)

    merchants = []          # dict
    merchant_tags = []      # (merchant_id, tag, tag_type, sort_no)
    dish_categories = []    # (id, merchant_id, name, sort_no)
    dishes = []             # dict
    dish_tag_rows = []      # (dish_id, tag, tag_type)
    spec_groups = []        # (id, dish_id, group_name, is_required, sort_no)
    spec_options = []       # (id, group_id, option_name, extra_price, is_default, sort_no)
    reviews = []            # (dish_id, merchant_id, text, source)

    REVIEW_TEXTS = [
        "咸淡适中", "分量足", "出餐快", "包装仔细", "口味正宗", "有点偏咸",
        "略油", "配送很快", "味道不错", "性价比高", "汤头浓郁", "食材新鲜",
        "辣度刚好", "送达准时", "分量偏少", "偏甜了点", "米饭偏硬",
        "整体满意", "下次还会点", "有点凉了",
    ]

    def add_tag_pairs(dish_id, pairs):
        dish_tag_rows.extend(tag_rows(dish_id, pairs))

    # ---- 精选 m1–m12（逐字还原 mock.js） ----
    for m in base["merchants"]:
        mid = m["id"]
        menu = base["menus"][mid]
        open_t, close_t = CURATED_TIMES[mid]
        merchants.append({
            "id": mid, "name": m["name"], "category": CURATED_CATEGORY[mid],
            "logo_url": img(m["name"] + "，美食摄影"),
            "rating": float(m["rating"]),
            "monthly_sales": parse_sales(m["sales"]),
            "delivery_minutes": int(m["mins"]),
            "distance_meters": parse_km(m["km"]),
            "min_order_amount": parse_min(m["minText"]),
            "delivery_fee": parse_fee(m["feeText"]),
            "is_open": 1, "open_time": open_t, "close_time": close_t,
            "notice": menu.get("notice"), "rank_label": m["rank"] or None,
        })
        for i, t in enumerate(m["promoTags"], 1):
            merchant_tags.append((mid, t, "PROMO", i))
        for i, t in enumerate(m["feastTags"], 1):
            merchant_tags.append((mid, t, "FEATURE", i))

        mdish_ids = []
        for cseq, cat in enumerate(menu["categories"], 1):
            cat_id = "%s-c%d" % (mid, cseq)
            dish_categories.append((cat_id, mid, cat["name"], cseq))
            for dseq, d in enumerate(cat["dishes"], 1):
                did = "%s-%d-%d" % (mid, cseq, dseq)
                mdish_ids.append(did)
                name = d["n"]
                desc = d.get("d")
                price = parse_price(d["p"])
                origin = float(d["o"]) if d.get("o") is not None else None
                dishes.append({
                    "id": did, "merchant_id": mid, "category_id": cat_id,
                    "name": name, "description": desc, "image_url": dish_img(name),
                    "price": price, "origin_price": origin,
                    "monthly_sales": parse_sales(d["s"]),
                    "is_available": 0 if rng.random() < 0.07 else 1,
                    "is_combo": 1 if d.get("combo") else 0,
                    "sort_no": dseq,
                })
                pairs = derive_tags(name, desc)
                for t in d.get("t") or []:
                    if t in T_SELLING:
                        pairs.append(("SELLING", T_SELLING[t]))
                    elif t in T_HEALTH:
                        pairs.append(("HEALTH", T_HEALTH[t]))
                    elif "热销榜" in t:
                        pairs.append(("RANK", t))
                if d.get("combo"):
                    pairs.append(("SELLING", "套餐"))
                if d.get("rank"):
                    pairs.append(("RANK", d["rank"]))
                add_tag_pairs(did, pairs)
                for gn, g in enumerate(d.get("specs") or [], 1):
                    gid = "%s-g%d" % (did, gn)
                    spec_groups.append((gid, did, g["g"], 1, gn))
                    for on, opt in enumerate(g["opts"], 1):
                        spec_options.append((("%s-o%d" % (gid, on)), gid, opt[0],
                                              float(opt[1]), 1 if on == 1 else 0, on))

        # 评价摘要 5–10 条
        for _ in range(rng.randint(5, 10)):
            if rng.random() < 0.5:
                did = rng.choice(mdish_ids)
            else:
                did = None
            reviews.append((did, mid, rng.choice(REVIEW_TEXTS), "MOCK"))

    # ---- 生成 m13–m40 ----
    prefixes = ["南巷", "北里", "老城根", "食光", "味见", "川页", "禾风",
                "巷陌", "西关", "东篱", "川禾", "城隍", "拾味", "云记",
                "灶台", "小满", "八斗", "食号", "禾田", "舌尖"]
    suffixes = ["（星海店）", "（星海中心店）", "（未来科技城店）", "（恒生店）",
                "（远洋店）", "（万塘店）", "（星海二店）", "（星海旗舰店）"]
    cat_word = {
        "炸鸡串串": "炸串铺", "鸭脖卤味": "卤味铺", "港式茶点": "茶餐厅",
        "湘菜快餐": "小炒", "麻辣烫": "麻辣烫", "炸鸡汉堡": "炸鸡汉堡",
        "米粉面食": "米粉铺", "卤味凉菜": "凉菜社", "轻食沙拉": "轻食",
        "日式简餐": "日式简餐", "奶茶饮品": "鲜果茶", "龙虾烧烤": "烤肉",
    }
    notices = [
        "每日新鲜现做，高峰期出餐约 15 分钟；食材每日直采。",
        "现点现做，出餐约 12 分钟，请耐心等待。",
        "食材每日新鲜直采，售完即止。",
        "支持备注口味，尽快为您出餐。",
        "下单后现做，高峰期可能稍慢。",
    ]
    promo_pool = ["爆 ¥12", "满 30 减 12", "满 20 减 6", "满 25 减 8",
                  "新客立减 5 元", "新客立减 8 元", "3 元店铺红包",
                  "1 元店铺红包", "第二杯半价", "满 50 减 15"]
    feature_pool = ["明厨亮灶", "食无忧", "极速退", "超时可免单",
                    "安心吃", "堂食店", "真材实料", "千人回购"]

    used_names = set()
    for gi in range(28):
        mid = "m%d" % (13 + gi)
        cat = CATEGORIES[gi % 12]
        while True:
            nm = "%s·%s%s" % (rng.choice(prefixes), cat_word[cat],
                              rng.choice(suffixes))
            if nm not in used_names:
                used_names.add(nm)
                break
        if gi == 3:
            rating = 3.6
        elif gi == 16:
            rating = 3.8
        else:
            rating = round(rng.uniform(4.0, 4.9), 1)
        is_open = 0 if gi == 10 else 1
        monthly_sales = rng.randint(50, 6000)
        delivery_minutes = rng.randint(18, 50)
        distance_meters = rng.randint(200, 3000)
        min_order = rng.choice([15, 18, 20, 25, 30, 38])
        delivery_fee = rng.choice([0.0, 0.0, 0.0, 1.0, 1.5, 2.0, 2.5])
        open_t, close_t = CAT_TIMES[cat]
        if rng.random() < 0.3:
            rank_label = "星海区%s热销榜 · 第 %d 名" % (cat, rng.randint(1, 8))
        else:
            rank_label = None
        merchants.append({
            "id": mid, "name": nm, "category": cat,
            "logo_url": img(nm + "，美食摄影"), "rating": rating,
            "monthly_sales": monthly_sales, "delivery_minutes": delivery_minutes,
            "distance_meters": distance_meters, "min_order_amount": min_order,
            "delivery_fee": delivery_fee, "is_open": is_open,
            "open_time": open_t, "close_time": close_t,
            "notice": rng.choice(notices), "rank_label": rank_label,
        })
        for i, t in enumerate(rng.sample(promo_pool, rng.randint(1, 3)), 1):
            merchant_tags.append((mid, t, "PROMO", i))
        for i, t in enumerate(rng.sample(feature_pool, rng.randint(0, 2)), 1):
            merchant_tags.append((mid, t, "FEATURE", i))

        pool = POOLS[cat]
        offset = rng.randint(0, 5)
        k = rng.randint(15, 20)
        picked = (pool[offset:] + pool[:offset])[:k]

        cat_seq = {}          # 分类名 -> 序号
        cat_dish_seq = {}     # 分类名 -> 已用菜序
        mdish_ids = []
        for group, dname, ddesc, dprice, combo in picked:
            if group not in cat_seq:
                n = len(cat_seq) + 1
                cat_seq[group] = n
                cat_dish_seq[group] = 0
                cat_id = "%s-c%d" % (mid, n)
                dish_categories.append((cat_id, mid, group, n))
            cat_dish_seq[group] += 1
            dseq = cat_dish_seq[group]
            did = "%s-%d-%d" % (mid, cat_seq[group], dseq)
            mdish_ids.append(did)

            price = jitter_price(dprice, rng)
            if rng.random() < 0.25:
                origin = round(price * rng.uniform(1.1, 1.35), 1)
                if origin <= price:
                    origin = round(price + 1, 1)
            else:
                origin = None
            msales = rng.randint(20, 1200)
            if "招牌" in dname:
                msales += rng.randint(100, 600)
            dishes.append({
                "id": did, "merchant_id": mid, "category_id": cat_id,
                "name": dname, "description": ddesc, "image_url": dish_img(dname),
                "price": price, "origin_price": origin, "monthly_sales": msales,
                "is_available": 0 if rng.random() < 0.07 else 1,
                "is_combo": 1 if combo else 0, "sort_no": dseq,
            })
            pairs = derive_tags(dname, ddesc)
            spicy = next(t for tt, t in pairs if tt == "SPICY")
            if "招牌" in dname:
                pairs.append(("SELLING", "招牌"))
            if combo:
                pairs.append(("SELLING", "套餐"))
                if rng.random() < 0.4:
                    pairs.append(("SELLING", "含本店招牌品"))
            if msales >= 500:
                pairs.append(("SELLING", "热销"))
            if origin is not None:
                pairs.append(("SELLING", "特价"))
            if rng.random() < 0.12:
                pairs.append(("RANK", "本店热销榜第 %d 名" % rng.randint(1, 5)))
            add_tag_pairs(did, pairs)

            if rng.random() < 0.50:
                gn = 0
                gn += 1
                gid = "%s-g%d" % (did, gn)
                spec_groups.append((gid, did, "份量", 1, gn))
                extra = rng.choice([4.0, 5.0, 6.0, 8.0, 10.0])
                spec_options.append((("%s-o1" % gid), gid, "1人份", 0.0, 1, 1))
                spec_options.append((("%s-o2" % gid), gid, "2人份", extra, 0, 2))
                if spicy != "不辣":
                    gn += 1
                    gid2 = "%s-g%d" % (did, gn)
                    spec_groups.append((gid2, did, "辣度", 1, gn))
                    for oi, onm in enumerate(["不辣", "微辣", "中辣"], 1):
                        spec_options.append((("%s-o%d" % (gid2, oi)), gid2, onm,
                                             0.0, 1 if oi == 1 else 0, oi))

        for _ in range(rng.randint(5, 10)):
            did = rng.choice(mdish_ids) if rng.random() < 0.5 else None
            reviews.append((did, mid, rng.choice(REVIEW_TEXTS), "MOCK"))

    return (merchants, merchant_tags, dish_categories, dishes, dish_tag_rows,
            spec_groups, spec_options, reviews)


# ---------------------------------------------------------------------------
# 输出 SQL
# ---------------------------------------------------------------------------

def emit(path):
    (merchants, merchant_tags, dish_categories, dishes, dish_tag_rows,
     spec_groups, spec_options, reviews) = build()

    lines = []
    lines.append("-- 20_seed.sql —— MealMind M1 种子数据（40 商家 / 约 600 菜品）")
    lines.append("-- Generated by deploy/sql/tools/seed_generate.py · seed=20261004")
    lines.append("-- 本文件由脚本自动生成，请勿手改；重新生成：")
    lines.append("--   python3 deploy/sql/tools/seed_generate.py")
    lines.append("")
    lines.append("USE mealmind;")
    lines.append("SET NAMES utf8mb4;")
    lines.append("")
    lines.append("SET FOREIGN_KEY_CHECKS=0;")
    for t in ("review_summary", "dish_spec_option", "dish_spec_group", "dish_tag",
              "dish", "dish_category", "merchant_tag", "merchant"):
        lines.append("TRUNCATE TABLE %s;" % t)
    lines.append("SET FOREIGN_KEY_CHECKS=1;")
    lines.append("")

    def insert(table, cols, rows):
        for i in range(0, len(rows), 100):
            chunk = rows[i:i + 100]
            lines.append("INSERT INTO %s (%s) VALUES" % (table, ", ".join(cols)))
            for j, r in enumerate(chunk):
                suffix = "," if j < len(chunk) - 1 else ";"
                lines.append("  " + r + suffix)
            lines.append("")

    insert("merchant",
           ["id", "name", "category", "logo_url", "rating", "monthly_sales",
            "delivery_minutes", "distance_meters", "min_order_amount",
            "delivery_fee", "is_open", "open_time", "close_time", "notice",
            "rank_label"],
           ["(%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s)" % (
               esc_str(m["id"]), esc_str(m["name"]), esc_str(m["category"]),
               esc_str(m["logo_url"]), "%.1f" % m["rating"], num(m["monthly_sales"]),
               num(m["delivery_minutes"]), num(m["distance_meters"]),
               money(m["min_order_amount"]), money(m["delivery_fee"]),
               num(m["is_open"]), esc_str(m["open_time"]), esc_str(m["close_time"]),
               esc_str(m["notice"]), esc_str(m["rank_label"]))
            for m in merchants])

    insert("merchant_tag", ["merchant_id", "tag", "tag_type", "sort_no"],
           ["(%s,%s,%s,%s)" % (esc_str(a), esc_str(b), esc_str(c), num(d))
            for (a, b, c, d) in merchant_tags])

    insert("dish_category", ["id", "merchant_id", "name", "sort_no"],
           ["(%s,%s,%s,%s)" % (esc_str(a), esc_str(b), esc_str(c), num(d))
            for (a, b, c, d) in dish_categories])

    insert("dish",
           ["id", "merchant_id", "category_id", "name", "description",
            "image_url", "price", "origin_price", "monthly_sales",
            "is_available", "is_combo", "sort_no"],
           ["(%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s)" % (
               esc_str(d["id"]), esc_str(d["merchant_id"]), esc_str(d["category_id"]),
               esc_str(d["name"]), esc_str(d["description"]), esc_str(d["image_url"]),
               money(d["price"]), money(d["origin_price"]), num(d["monthly_sales"]),
               num(d["is_available"]), num(d["is_combo"]), num(d["sort_no"]))
            for d in dishes])

    insert("dish_tag", ["dish_id", "tag", "tag_type"],
           ["(%s,%s,%s)" % (esc_str(a), esc_str(b), esc_str(c))
            for (a, b, c) in dish_tag_rows])

    insert("dish_spec_group", ["id", "dish_id", "group_name", "is_required", "sort_no"],
           ["(%s,%s,%s,%s,%s)" % (esc_str(a), esc_str(b), esc_str(c), num(d), num(e))
            for (a, b, c, d, e) in spec_groups])

    insert("dish_spec_option",
           ["id", "group_id", "option_name", "extra_price", "is_default", "sort_no"],
           ["(%s,%s,%s,%s,%s,%s)" % (esc_str(a), esc_str(b), esc_str(c),
                                     money(d), num(e), num(f))
            for (a, b, c, d, e, f) in spec_options])

    insert("review_summary", ["dish_id", "merchant_id", "summary_text", "source"],
           ["(%s,%s,%s,%s)" % (esc_str(a), esc_str(b), esc_str(c), esc_str(d))
            for (a, b, c, d) in reviews])

    with open(path, "w", encoding="utf-8", newline="\n") as f:
        f.write("\n".join(lines))
        f.write("\n")

    print("merchants=%d dishes=%d dish_tag=%d spec_group=%d spec_option=%d "
          "review=%d merchant_tag=%d dish_category=%d"
          % (len(merchants), len(dishes), len(dish_tag_rows), len(spec_groups),
             len(spec_options), len(reviews), len(merchant_tags),
             len(dish_categories)))


if __name__ == "__main__":
    emit(OUT_SQL)
