"""提示词热更新（prompt_store）的单元测试：覆盖层校验、降级与 YAML 解析。

    全部用例不连 Nacos：热更新线程在 pytest 进程内不会启动（见 start_prompt_refresh
    的闸门），这里直接调用 PromptStore / parse_prompts_yaml 验证核心逻辑——
    占位符契约、坏内容拒绝、Nacos 缺席时回落内置默认。
"""
import pytest

from core import prompt_store
from core.prompt_store import PromptStore, parse_prompts_yaml, _extract_fields
from core.prompts import ChronicDiseasePrompts  # noqa: F401 —— 导入即完成默认值注册


@pytest.fixture(autouse=True)
def _clean_store():
    """每条用例从纯默认状态出发，结束后也清干净，避免用例间互相污染。"""
    PromptStore.clear_overrides()
    yield
    PromptStore.clear_overrides()


def test_no_override_returns_builtin_default():
    """未启用 Nacos / 尚未拉到配置时，拿到的必须是内置默认值（逐字节一致）。"""
    assert ChronicDiseasePrompts.router_prompt() == prompt_store.PromptStore._defaults["router_prompt"]
    assert ChronicDiseasePrompts.disease_system_prompt().startswith("\n你是一位资深医学科普专家")
    assert PromptStore.source() == "default"


def test_valid_override_is_applied():
    """合法覆盖（只改文案、占位符原样）应整体生效。"""
    default = ChronicDiseasePrompts.disease_system_prompt()
    overridden = default.replace("资深医学科普专家", "三甲医院科普专家（Nacos 版）")
    accepted, rejected = PromptStore.apply({"disease_system_prompt": overridden}, source="nacos(test)")
    assert (accepted, rejected) == (1, [])
    assert "三甲医院科普专家（Nacos 版）" in ChronicDiseasePrompts.disease_system_prompt()
    assert PromptStore.source() == "nacos(test)"


def test_override_missing_placeholder_is_rejected():
    """删掉默认模板里的占位符 → 运行时必然 KeyError，必须整条拒绝。"""
    broken = ChronicDiseasePrompts.router_prompt().replace("{query}", "用户问题")
    accepted, rejected = PromptStore.apply({"router_prompt": broken}, source="nacos(test)")
    assert accepted == 0 and len(rejected) == 1 and "占位符不符" in rejected[0]
    assert "{query}" in ChronicDiseasePrompts.router_prompt()


def test_override_extra_placeholder_is_rejected():
    """多出的占位符调用方不会传 → .format 同样 KeyError，一并拒绝。"""
    broken = ChronicDiseasePrompts.answer_prompt() + "\n附加字段：{extra}"
    accepted, rejected = PromptStore.apply({"answer_prompt": broken}, source="nacos(test)")
    assert accepted == 0 and "占位符不符" in rejected[0]


def test_override_unclosed_brace_is_rejected():
    """大括号没闭合的模板在 .format 时才炸，必须在入库前拦下。"""
    broken = ChronicDiseasePrompts.no_knowledge_prompt().replace("{question}", "{question")
    accepted, rejected = PromptStore.apply({"no_knowledge_prompt": broken}, source="nacos(test)")
    assert accepted == 0 and any("未闭合" in r for r in rejected)


def test_unknown_name_and_empty_content_are_rejected():
    accepted, rejected = PromptStore.apply(
        {"not_a_prompt": "任意", "router_prompt": "   "}, source="nacos(test)")
    assert accepted == 0 and len(rejected) == 2


def test_partial_rejection_keeps_previous_valid_override():
    """部分条目被拒时，合法条目生效、被拒条目沿用上一份覆盖。"""
    good = ChronicDiseasePrompts.lab_system_prompt().replace("临床检验专家", "检验医学专家")
    PromptStore.apply({"lab_system_prompt": good}, source="nacos(first)")
    PromptStore.apply({"lab_system_prompt": "{lab_system_prompt 坏了"}, source="nacos(second)")
    assert "检验医学专家" in ChronicDiseasePrompts.lab_system_prompt()


def test_clear_overrides_restores_defaults():
    PromptStore.apply({"risk_system_prompt": "覆盖版"}, source="nacos(test)")
    assert ChronicDiseasePrompts.risk_system_prompt() == "覆盖版"
    PromptStore.clear_overrides()
    assert ChronicDiseasePrompts.risk_system_prompt().startswith("\n你是一位急诊医学专家")


def test_extract_fields_ignores_escaped_braces():
    assert _extract_fields("{{字面量}} {query} {history_section}") == {"query", "history_section"}


def test_parse_prompts_yaml_shapes():
    # 真实 dataId 文件末尾带换行，clip 行为保留单个结尾换行
    ok = parse_prompts_yaml("prompts:\n  router_prompt: |\n    内容\n")
    assert ok == {"router_prompt": "内容\n"}
    assert parse_prompts_yaml("没有prompts段: true") is None
    assert parse_prompts_yaml("just a [broken: yaml") is None
    assert parse_prompts_yaml("") is None
