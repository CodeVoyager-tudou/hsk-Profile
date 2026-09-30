"""慢性病系统离线评测脚本：用黄金题集量化检索召回质量与特殊通道行为。

    两种模式：
      · 默认（仅检索层）：不调 LLM，零费用、几十秒跑完。判定标准是
        「期望知识源进入重排后候选」且「期望关键词覆盖过半」。
      · --full（完整链路）：再跑一遍多智能体问答，额外评最终回答的关键词覆盖，慢且消耗额度。

    特殊通道（greeting / emergency）优先于检索判定 —— 它们本身就是安全兜底。

    用法（项目根目录执行）：
        python chronic_disease/run_eval.py            # 仅检索层评测
        python chronic_disease/run_eval.py --full     # 完整链路评测
    明细结果写入 chronic_disease/eval_results.csv。
"""
import csv
import json
import os
import sys
import argparse
import time

project_root = os.path.abspath(os.path.join(os.path.dirname(__file__), ".."))
sys.path.insert(0, project_root)
os.chdir(project_root)  # 配置/模型路径均按项目根目录相对定位

from base.logger import logger
from chronic_disease.core.preprocess import check_greeting, check_emergency
from chronic_disease.document_loader.vector_store import create_vector_store

EVAL_DATA = os.path.join("chronic_disease", "eval_data.json")
RESULT_CSV = os.path.join("chronic_disease", "eval_results.csv")


def keyword_coverage(text: str, keywords: list) -> float:
    """期望关键词在文本中的命中率"""
    if not keywords:
        return 1.0
    hit = sum(1 for kw in keywords if kw in text)
    return hit / len(keywords)


def keyword_locality(text: str, keywords: list, max_span: int) -> bool:
    """所有关键词是否在长度不超过 max_span 的窗口内共现。

    【为什么需要这个判据】
        keyword_coverage 是在"多个检索块的拼接全文"里数关键词：只要词出现过就算命中，
        哪怕它们分处相隔很远的句子、甚至不同的文档。对"家庭血压 135/85""糖尿病诊断 7.0 与 11.1"
        这类结构性答案，词都在但不成句，等于答非所问 —— 页眉页脚残留、表格单元格与正文交错，
        都会让关键词同现却不可读。max_span 用"共现窗口"把这种情况判为不通过。
        题集里用可选的 max_span 字段启用（不填则该题只看覆盖率，行为与原来完全一致）。
    """
    if not keywords or not max_span:
        return True
    positions = [[i for i in range(len(text)) if text.startswith(kw, i)] for kw in keywords]
    if any(not pos for pos in positions):
        return False
    # 候选窗口左端取**所有**关键词的出现位置：不能只从第一个关键词起步——
    # 语料里关键词的自然顺序未必与题集书写顺序一致（"血红蛋白→白细胞→血小板"），
    # 只从"白细胞"起步会把写在它前面的"血红蛋白"判定为不在窗口内，产生假失败。
    for start in sorted({i for pos in positions for i in pos}):
        if all(any(start <= i < start + max_span for i in pos) for pos in positions):
            return True
    return False


def evaluate(full: bool = False):
    """跑完整套题集并把逐题明细写入 eval_results.csv，同时在日志里打印汇总指标。

    full=True 时额外构造 coordinator，对每题跑一次真实问答（需要 LLM 可用）。
    """
    with open(EVAL_DATA, encoding="utf-8") as f:
        items = json.load(f)

    vs = create_vector_store(rebuild=False)
    coordinator = None
    if full:
        from chronic_disease.core.coordinator import ChronicDiseaseCoordinator
        coordinator = ChronicDiseaseCoordinator(vs)

    rows = []
    stats = {"total": 0, "pass": 0, "source_hit": 0, "retrieval_items": 0}
    coverage_sum = 0.0

    for item in items:
        qid, question = item["id"], item["question"]
        expected = item["expected_source"]
        keywords = item.get("expected_keywords", [])
        stats["total"] += 1
        t0 = time.time()

        if expected == "emergency":
            # 急症通道：命中关键词即通过（这是安全兜底，优先级高于检索）
            tip = check_emergency(question)
            passed = tip is not None and keyword_coverage(tip, keywords) > 0
            detail = tip or "未命中急症通道"
        elif expected == "greeting":
            resp = check_greeting(question)
            passed = resp is not None and keyword_coverage(resp, keywords) > 0
            detail = (resp or "未命中问候语")[:80]
        else:
            # 检索层评测：不带过滤检索，看期望知识源是否进入重排后候选
            docs = vs.hybrid_search_with_reranker(question, k=5)
            stats["retrieval_items"] += 1
            hit_sources = [d.metadata.get("source") for d in docs]
            source_hit = expected in hit_sources
            if source_hit:
                stats["source_hit"] += 1
            context = "\n".join(d.page_content for d in docs)
            coverage = keyword_coverage(context, keywords)
            coverage_sum += coverage
            # 检索层通过标准：命中期望知识源 且 关键词覆盖过半（题集给了 max_span 时还要求关键词共现）
            max_span = item.get("max_span")
            local_ok = keyword_locality(context, keywords, max_span)
            passed = source_hit and coverage >= 0.5 and local_ok
            detail = f"来源{hit_sources} | 关键词覆盖{coverage:.0%}"
            if max_span and not local_ok:
                detail += f" | 关键词未在 {max_span} 字窗口内共现"

            if full and coordinator is not None:
                # 完整链路：评最终回答的关键词覆盖
                answer = coordinator.query(question)
                ans_coverage = keyword_coverage(answer, keywords)
                detail += f" | 回答覆盖{ans_coverage:.0%}"
                passed = passed or (ans_coverage >= 0.5)

        if passed:
            stats["pass"] += 1
        rows.append({
            "id": qid, "question": question, "expected_source": expected,
            "passed": "PASS" if passed else "FAIL",
            "detail": detail, "elapsed_s": f"{time.time() - t0:.2f}"
        })
        logger.info(f"[{qid}] {'PASS' if passed else 'FAIL'} {question} -> {detail}")

    with open(RESULT_CSV, "w", newline="", encoding="utf-8-sig") as f:
        writer = csv.DictWriter(f, fieldnames=list(rows[0].keys()))
        writer.writeheader()
        writer.writerows(rows)

    print("=" * 60)
    print(f"评测完成：{stats['pass']}/{stats['total']} 通过（{stats['pass'] / max(stats['total'], 1):.0%}）")
    if stats["retrieval_items"]:
        print(f"检索知识源命中率：{stats['source_hit']}/{stats['retrieval_items']}"
              f"（{stats['source_hit'] / stats['retrieval_items']:.0%}）")
        print(f"检索关键词平均覆盖：{coverage_sum / stats['retrieval_items']:.0%}")
    print(f"明细已写入：{RESULT_CSV}")


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description="慢性病系统离线评测")
    parser.add_argument("--full", action="store_true",
                        help="完整链路评测（调用 LLM，慢且消耗额度）；默认仅评检索层与特殊通道")
    args = parser.parse_args()
    evaluate(full=args.full)
