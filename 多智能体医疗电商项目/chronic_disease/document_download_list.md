# 慢性病管理知识库 - 文档下载清单

> 除下载 PDF 外，各知识源还维护了**人工精编的高密度 md 文档**（位于各源子目录下，随 `--incremental` 入库）。
> 这些 md 针对常见问答做了关键词密度设计，检索命中率远高于整本 PDF，是提升命中率的主要来源：
>
> - `disease/高血压疾病科普.md`：定义、诊断标准、常见症状、原发性高血压、危害
> - `medication/高血压药物治疗指征与原则.md`：启动药物治疗指征、联合用药、药物分类与不良反应
> - `medication/降压药依从性与停药指导.md`：能否自行停药、漏服处理、五大类降压药
> - `lifestyle/高血压患者运动指导.md`：运动量、有氧/抗阻运动、老年患者、注意事项
> - `lifestyle/高血压患者饮食管理指南.md`：限盐（<5g/日）、DASH 饮食、总体饮食原则
> - `lab/临床常用检验指标参考与解读.md`：血钾、血糖、血脂、肾功能、甲功、血细胞参考区间与解读
> - `risk/高血压危险信号与就医指征.md`：就医指征、血压骤升应对、高危人群与风险评估
> - `risk/高血压急症识别与就医指引.md`：急症识别、自我监测建议
>
> 新增 md 后执行 `python chronic_disease/document_loader/ingest_fast.py --incremental` 即可增量入库。

## 一、高血压相关文档

### 1. 中国高血压防治指南
- 来源: 中华医学会心血管病学分会
- 下载: http://www.nccd.org.cn/News/Detail/123456  (搜索"中国高血压防治指南2024 PDF")
- 备用: 百度搜索"中国高血压防治指南 PDF"获取最新版

### 2. 高血压患者健康管理服务规范
- 来源: 国家卫健委
- 下载: http://www.nhc.gov.cn/ 搜索"高血压患者健康管理"
- 备用: 各省卫健委官网可下载

## 二、糖尿病相关文档

### 3. 中国2型糖尿病防治指南
- 来源: 中华医学会糖尿病学分会
- 下载: https://diab.cma.org.cn/ 搜索"2型糖尿病防治指南"
- 备用: 搜索"中国2型糖尿病防治指南2024版 PDF"

### 4. 糖尿病健康教育手册
- 来源: 中国疾控中心慢病中心
- 下载: https://ncncd.chinacdc.cn/ 搜索"糖尿病健康教育"

## 三、慢性病通用文档

### 5. 中国居民膳食指南(2022)
- 来源: 中国营养学会
- 下载: https://www.cnsoc.org/ 搜索"中国居民膳食指南2022"
- 备用: 搜索"中国居民膳食指南2022 PDF 下载"

### 6. 常见慢性病用药指南
- 来源: 国家卫健委合理用药专家委员会
- 下载: http://www.nhc.gov.cn/ 搜索"慢性病用药指南"

## 四、检验指标参考

### 7. 临床常用检验项目参考区间
- 来源: 国家卫健委
- 下载: http://www.nhc.gov.cn/ 搜索"临床检验参考区间"
- 备用: 百度搜索"常用检验项目参考值范围 PDF"

> ⚠️ **待替换**：`lab/WST405-2012_血细胞分析参考区间.pdf` 下载错了文件。
> 实测该 PDF 共 3 页，内容是**浙江省临床检验中心文件（浙临检〔2013〕05 号）转发通知 +
> 卫生部通告（卫通〔2012〕23 号）**，正文只是列出 8 项标准的编号与名称（其中一行是
> "WS/T 405—2012 血细胞分析参考区间"），**不含任何参考区间表格**——所以它在库里只有 6 个块，
> 并不是切分把内容切没了。
> 需要从国家卫健委"信息公开 → 卫生计生标准"或全国标准信息公共服务平台
> （std.samr.gov.cn）取 **WS/T 405-2012 正式文本**（带参考区间表的版本）替换同名文件，
> 然后执行 `python chronic_disease/document_loader/ingest_fast.py --source lab --incremental`。
> 同目录的 `WST404.5-2015`、`WST404.10-2022` 经核对是真标准（含 ICS/CCS 页眉与参考区间表），无需替换。

## 五、急救指南

### 8. 常见急症处理指南
- 来源: 中国医师协会急诊医师分会
- 下载: 搜索"常见急症识别与处理指南 PDF"

---

## 快速下载方式

### 方式一：手动下载（推荐）
1. 打开以上链接，搜索对应文档
2. 下载 PDF 格式文件
3. 放入 `chronic_disease/data/` 目录下对应子文件夹：
   - `chronic_disease/data/disease/`  → 疾病科普类
   - `chronic_disease/data/medication/` → 用药类
   - `chronic_disease/data/lifestyle/`  → 饮食运动类
   - `chronic_disease/data/lab/`        → 检验指标类
   - `chronic_disease/data/risk/`       → 风险预警类

### 方式二：使用公开数据集
- 中文医学知识图谱 CMeKG: https://github.com/king-yyf/CMeKG_tools
- 中文医学问答数据集: https://github.com/zhangwenzhe/Medical-Question-Answering

### 方式三：爬取公开医疗科普文章
- 丁香园科普: https://dxy.com/
- 腾讯医典: https://baike.qq.com/health/
- 百度健康医典: https://baike.baidu.com/health/