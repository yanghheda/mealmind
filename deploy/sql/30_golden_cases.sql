-- Golden 评测用例（50 条）：STANDARD 30 / BOUNDARY 12 / NEGATIVE 8
-- expected_constraints 字段格式（EvalService 解析）：
--   { "tasteTags": [...], "budgetMax": 30, "avoidTags": [...], "maxDelivery": 30, "minCandidates": 1, "expectEmpty": false }
-- 所有用例基于 20_seed.sql 的菜品与标签数据设计，设计口径如下：
--   1) 口味标签只能用 dish_tag 里真实存在的值（SPICY 仅 不辣/中辣/微辣；FLAVOR 见种子）。
--      「特辣」只作为规格选项名存在，菜品标签里没有，故用例不使用。
--   2) 忌口标签只能用理解器词表里有的值（见 understander.py 的 _AVOID_KEYWORDS），
--      且必须能从句子里解析出来（「不吃 X」「不要 X」这种紧邻写法最稳）。
--   3) 空结果（expectEmpty）只靠「不可能的时效」或「低预算 + 忌口」构造：
--      商家配送时长最小 18 分钟（maxDelivery ≤ 17 必空）；
--      价格 ≤ 1 元的菜品都带「蛋」标签（budgetMax 1 + avoidTags 蛋 必空）；
--      注意理解器预算下限为 1 元、时效下限 10 分钟，故不能用更小的数字。
--   4) expectEmpty 用例仍需过理解校验：expected 的 tasteTags/avoidTags/budgetMax 必须能从用例文本解析出来
--      （maxDelivery 不参与理解校验，因此是最稳的空结果杠杆）。
--   5) 口味必须与时效/预算组合在种子里可达，否则 Top1 口味必然不命中（属用例设计缺陷，不是系统问题）：
--      例：酸辣菜品最短配送 23 分钟，就不能配「20 分钟内送达」。
--
-- 导入务必带字符集参数，否则中文会双重编码（表现为期望值比对永远不等）：
--   docker exec -i infra-mysql mysql -umealmind -pmealmind_dev_pw --default-character-set=utf8mb4 \
--     mealmind < deploy/sql/30_golden_cases.sql
--
-- 用 INSERT ... ON DUPLICATE KEY UPDATE 而非 DELETE/TRUNCATE：
-- golden_case 被 eval_result 外键引用（fk_eval_result_case），DELETE/TRUNCATE 都会被拒
-- （ERROR 1451 / 1701），一旦评测跑过就再也导不进去。UPSERT 可反复执行且保留历史结果。
INSERT INTO golden_case (id, case_type, input_text, expected_constraints, expected_points, rubric_version, annotated_by) VALUES

-- ============ STANDARD (30) ============
('gc-s01','STANDARD','想吃点辣的，预算 30 以内',
 '{"tasteTags":["辣"],"budgetMax":30,"minCandidates":1}', '{"note":"单维口味+预算"}', 'v1.2', 'auto'),
('gc-s02','STANDARD','微辣的菜，不要香菜',
 '{"tasteTags":["微辣"],"avoidTags":["香菜"],"minCandidates":1}', '{"note":"口味+忌口"}', 'v1.2', 'auto'),
('gc-s03','STANDARD','预算 20 块钱以内的午餐',
 '{"budgetMax":20,"minCandidates":1}', '{"note":"纯预算约束"}', 'v1.2', 'auto'),
('gc-s04','STANDARD','半小时内能送到的，口味清淡',
 '{"maxDelivery":30,"tasteTags":["清淡"],"minCandidates":1}', '{"note":"时效+口味"}', 'v1.2', 'auto'),
('gc-s05','STANDARD','我不吃辣，预算 25',
 '{"tasteTags":["不辣"],"budgetMax":25,"minCandidates":1}', '{"note":"不辣+预算"}', 'v1.2', 'auto'),
('gc-s06','STANDARD','来份中辣的，别放香菜和葱花',
 '{"tasteTags":["中辣"],"avoidTags":["香菜","葱"],"minCandidates":1}', '{"note":"多忌口"}', 'v1.2', 'auto'),
('gc-s07','STANDARD','今晚想吃甜口的，45 分钟内送到',
 '{"tasteTags":["甜口"],"maxDelivery":45,"minCandidates":1}', '{"note":"甜口+时效"}', 'v1.2', 'auto'),
('gc-s08','STANDARD','中辣重口味，预算 40',
 '{"tasteTags":["中辣"],"budgetMax":40,"minCandidates":1}', '{"note":"中辣+预算"}', 'v1.2', 'auto'),
('gc-s09','STANDARD','不吃鸡肉，其他都行，预算 35',
 '{"avoidTags":["鸡肉"],"budgetMax":35,"minCandidates":1}', '{"note":"食材忌口"}', 'v1.2', 'auto'),
('gc-s10','STANDARD','酸辣口味，45 分钟内送达',
 '{"tasteTags":["酸辣"],"maxDelivery":45,"minCandidates":1}', '{"note":"口味+时效"}', 'v1.2', 'auto'),
('gc-s11','STANDARD','午餐，咸鲜口味，30 块以内',
 '{"tasteTags":["咸鲜"],"budgetMax":30,"minCandidates":1}', '{"note":"场景+口味+预算"}', 'v1.2', 'auto'),
('gc-s12','STANDARD','不要豆制品，微辣，预算 28',
 '{"avoidTags":["豆制品"],"tasteTags":["微辣"],"budgetMax":28,"minCandidates":1}', '{"note":"忌口+口味+预算"}', 'v1.2', 'auto'),
('gc-s13','STANDARD','晚餐，不辣，40 分钟送到',
 '{"tasteTags":["不辣"],"maxDelivery":40,"minCandidates":1}', '{"note":"场景+口味+时效"}', 'v1.2', 'auto'),
('gc-s14','STANDARD','想吃辣但怕太辣，微辣就行，预算 22',
 '{"tasteTags":["微辣"],"budgetMax":22,"minCandidates":1}', '{"note":"辣度细分"}', 'v1.2', 'auto'),
('gc-s15','STANDARD','忌香菜忌辣椒，清淡，预算 30',
 '{"avoidTags":["香菜","辣椒"],"tasteTags":["清淡"],"budgetMax":30,"minCandidates":1}', '{"note":"双忌口+清淡+预算"}', 'v1.2', 'auto'),
('gc-s16','STANDARD','加班餐，要快，25 分钟内，预算 18',
 '{"maxDelivery":25,"budgetMax":18,"minCandidates":1}', '{"note":"紧时效+低预算"}', 'v1.2', 'auto'),
('gc-s17','STANDARD','想吃点蔬菜，不辣，预算 20',
 '{"avoidTags":[],"tasteTags":["不辣"],"budgetMax":20,"minCandidates":1}', '{"note":"清淡低预算"}', 'v1.2', 'auto'),
('gc-s18','STANDARD','中辣，不要猪肉，预算 32',
 '{"tasteTags":["中辣"],"avoidTags":["猪肉"],"budgetMax":32,"minCandidates":1}', '{"note":"口味+忌口+预算"}', 'v1.2', 'auto'),
('gc-s19','STANDARD','早餐，清淡不辣，15 块以内',
 '{"tasteTags":["不辣"],"budgetMax":15,"minCandidates":1}', '{"note":"早餐低预算"}', 'v1.2', 'auto'),
('gc-s20','STANDARD','夜宵，中辣，预算 50',
 '{"tasteTags":["中辣"],"budgetMax":50,"minCandidates":1}', '{"note":"夜宵高预算"}', 'v1.2', 'auto'),
('gc-s21','STANDARD','不吃虾，微辣，30 分钟送到',
 '{"avoidTags":["虾"],"tasteTags":["微辣"],"maxDelivery":30,"minCandidates":1}', '{"note":"忌口+口味+时效"}', 'v1.2', 'auto'),
('gc-s22','STANDARD','预算 25，不要葱不要香菜，咸鲜',
 '{"budgetMax":25,"avoidTags":["葱","香菜"],"tasteTags":["咸鲜"],"minCandidates":1}', '{"note":"预算+多忌口+口味"}', 'v1.2', 'auto'),
('gc-s23','STANDARD','一个人吃，简单点，不辣，20 以内',
 '{"tasteTags":["不辣"],"budgetMax":20,"minCandidates":1}', '{"note":"单人简餐"}', 'v1.2', 'auto'),
('gc-s24','STANDARD','要够味，中辣以上，预算 38',
 '{"tasteTags":["中辣"],"budgetMax":38,"minCandidates":1}', '{"note":"中辣+预算"}', 'v1.2', 'auto'),
('gc-s25','STANDARD','忌牛肉，微辣，45 分钟内',
 '{"avoidTags":["牛肉"],"tasteTags":["微辣"],"maxDelivery":45,"minCandidates":1}', '{"note":"忌口+口味+时效"}', 'v1.2', 'auto'),
('gc-s26','STANDARD','下午茶，甜口，预算 15',
 '{"tasteTags":["甜口"],"budgetMax":15,"minCandidates":1}', '{"note":"下午茶甜口"}', 'v1.2', 'auto'),
('gc-s27','STANDARD','午餐套餐，不辣，预算 30',
 '{"tasteTags":["不辣"],"budgetMax":30,"minCandidates":1}', '{"note":"套餐+口味+预算"}', 'v1.2', 'auto'),
('gc-s28','STANDARD','想吃粉，酸辣，预算 20',
 '{"tasteTags":["酸辣"],"budgetMax":20,"minCandidates":1}', '{"note":"粉+口味+预算"}', 'v1.2', 'auto'),
('gc-s29','STANDARD','少油少盐，不辣，预算 25',
 '{"tasteTags":["不辣"],"budgetMax":25,"minCandidates":1}', '{"note":"健康清淡"}', 'v1.2', 'auto'),
('gc-s30','STANDARD','两人份，微辣，预算 60',
 '{"tasteTags":["微辣"],"budgetMax":60,"minCandidates":1}', '{"note":"双人高预算"}', 'v1.2', 'auto'),

-- ============ BOUNDARY (12) ============
('gc-b01','BOUNDARY','预算正好 30 块，微辣',
 '{"tasteTags":["微辣"],"budgetMax":30,"minCandidates":1}', '{"note":"预算等于上限边界"}', 'v1.2', 'auto'),
('gc-b02','BOUNDARY','预算 1 块钱以内的菜',
 '{"budgetMax":1,"minCandidates":1}', '{"note":"极低预算（命中 1 元档菜品）"}', 'v1.2', 'auto'),
('gc-b03','BOUNDARY','10 分钟内必须送到，不辣',
 '{"tasteTags":["不辣"],"maxDelivery":10,"expectEmpty":true}', '{"note":"极紧时效→空结果"}', 'v1.2', 'auto'),
('gc-b04','BOUNDARY','不吃辣，微辣的也不行',
 '{"tasteTags":["不辣"],"minCandidates":1}', '{"note":"完全不辣"}', 'v1.2', 'auto'),
('gc-b05','BOUNDARY','辣度要中辣，预算 100',
 '{"tasteTags":["中辣"],"budgetMax":100,"minCandidates":1}', '{"note":"极高预算+中辣"}', 'v1.2', 'auto'),
('gc-b06','BOUNDARY','我要的菜必须同时有中辣和香辣',
 '{"tasteTags":["中辣","香辣"],"minCandidates":1}', '{"note":"复合口味同时满足"}', 'v1.2', 'auto'),
('gc-b07','BOUNDARY','忌香菜忌葱忌蒜，微辣，预算 30',
 '{"avoidTags":["香菜","葱","蒜"],"tasteTags":["微辣"],"budgetMax":30,"minCandidates":1}', '{"note":"大量忌口"}', 'v1.2', 'auto'),
('gc-b08','BOUNDARY','预算 5 块，要吃饱',
 '{"budgetMax":5,"minCandidates":1}', '{"note":"极低预算（命中 5 元档菜品）"}', 'v1.2', 'auto'),
('gc-b09','BOUNDARY','必须中辣以上，10 分钟送到',
 '{"tasteTags":["中辣"],"maxDelivery":10,"expectEmpty":true}', '{"note":"极紧时效+口味→空结果"}', 'v1.2', 'auto'),
('gc-b10','BOUNDARY','不吃鸡肉，不吃猪肉，不吃牛肉，不吃鱼，不吃虾，预算 25',
 '{"avoidTags":["鸡肉","猪肉","牛肉","鱼","虾"],"budgetMax":25,"minCandidates":1}', '{"note":"全素多忌口"}', 'v1.2', 'auto'),
('gc-b11','BOUNDARY','预算 50，不要香菜不要辣不要豆制品不要鸡肉',
 '{"budgetMax":50,"avoidTags":["香菜","豆制品","鸡肉"],"tasteTags":["不辣"],"minCandidates":1}', '{"note":"高预算多约束"}', 'v1.2', 'auto'),
('gc-b12','BOUNDARY','只要中辣，15 分钟内送到',
 '{"tasteTags":["中辣"],"maxDelivery":15,"expectEmpty":true}', '{"note":"极端时效+口味→空结果"}', 'v1.2', 'auto'),

-- ============ NEGATIVE (8) —— 全部期望空结果 ============
('gc-n01','NEGATIVE','我要吃恐龙肉，10 分钟内送到',
 '{"maxDelivery":10,"expectEmpty":true}', '{"note":"不存在的食材 + 不可能时效→空"}', 'v1.2', 'auto'),
('gc-n02','NEGATIVE','要中辣大餐，12 分钟内送到',
 '{"tasteTags":["中辣"],"maxDelivery":12,"expectEmpty":true}', '{"note":"不可能时效→空"}', 'v1.2', 'auto'),
('gc-n03','NEGATIVE','10 分钟内必须送到任何菜',
 '{"maxDelivery":10,"expectEmpty":true}', '{"note":"时效低于所有商家→空"}', 'v1.2', 'auto'),
('gc-n04','NEGATIVE','预算 1 块以内，不要蛋',
 '{"budgetMax":1,"avoidTags":["蛋"],"expectEmpty":true}', '{"note":"低预算+忌口覆盖全部低价品→空"}', 'v1.2', 'auto'),
('gc-n05','NEGATIVE','17 分钟内送到，不辣就行',
 '{"tasteTags":["不辣"],"maxDelivery":17,"expectEmpty":true}', '{"note":"时效低于所有商家→空"}', 'v1.2', 'auto'),
('gc-n06','NEGATIVE','预算 2 块，还要中辣，10 分钟内送到',
 '{"tasteTags":["中辣"],"budgetMax":2,"maxDelivery":10,"expectEmpty":true}', '{"note":"预算+时效双死→空"}', 'v1.2', 'auto'),
('gc-n07','NEGATIVE','预算 5 块，12 分钟内送到',
 '{"budgetMax":5,"maxDelivery":12,"expectEmpty":true}', '{"note":"时效低于所有商家→空"}', 'v1.2', 'auto'),
('gc-n08','NEGATIVE','要天上飞的龙肉，微辣，15 分钟内',
 '{"tasteTags":["微辣"],"maxDelivery":15,"expectEmpty":true}', '{"note":"不存在食材 + 不可能时效→空"}', 'v1.2', 'auto')
ON DUPLICATE KEY UPDATE
  case_type = VALUES(case_type),
  input_text = VALUES(input_text),
  expected_constraints = VALUES(expected_constraints),
  expected_points = VALUES(expected_points),
  rubric_version = VALUES(rubric_version),
  annotated_by = VALUES(annotated_by);
