# -*- coding: utf-8 -*-
"""
DSP 节点旁路重放「双路径一致性」检查。

## 它防的是哪类 bug
项目里有**两条**「引擎重建后补发参数」的路径：
  - 前台建链（电源键启停 / MediaProjection / Loopback）→ MainViewModel.applyEqSettings()
  - 无界面自愈 headless restore                    → AudioChainManager.replaySettings()

engine.prepare() 会把所有节点重置为 bypass=true，所以两条路径都必须把
每个节点的旁路状态补回去。历史上已经 4 次「加了新节点只补了一条」：
  - 重放只发 param 0
  - compressor_sidechain_hp 只有重放没有写入端
  - bass_enhance_mode 用 getFloat 读 Int 键导致整个重放静默中断
  - 低频管家 3 个槽没进 applyEqSettings（P1：开关全开却没音效）

本脚本静态对比两条路径的 setBypass 覆盖集合，缺一个就非零退出。

## 用法
  python scripts/check_node_replay.py
  python scripts/check_node_replay.py --self-test   # 自检：故意删一项看能不能报错

接入：dsp-commit-closure skill 第 1 步「验证先行」。
"""
from __future__ import annotations

import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
VM = ROOT / "app/src/main/java/com/jiangjie/Sonicraft/MainViewModel.kt"
ACM = ROOT / "app/src/main/java/com/jiangjie/Sonicraft/AudioChainManager.kt"
EG = ROOT / "app/src/main/java/com/jiangjie/Sonicraft/EffectGroups.kt"
ENGINE = ROOT / "core/engine/src/main/java/com/jiangjie/Sonicraft/core/engine/AudioEngine.kt"

TOP_FUN = re.compile(
    r"^    (?:(?:private|internal|protected|suspend|override|inline|open) )*fun \w+"
)
BYPASS = re.compile(r"setBypass\(\s*AudioEngine\.(\w+_NODE_INDEX)", re.S)
SLOT = re.compile(r'EffectSlotSpec\("[^"]+",\s*AudioEngine\.(\w+_NODE_INDEX)\)')
CONST = re.compile(r"const val (\w+_NODE_INDEX)\s*=")


def read(p: Path) -> str:
    return p.read_text(encoding="utf-8")


def body_of(src: str, signature: str) -> str:
    """按「下一个同缩进顶层 fun」切出函数体，避开 Kotlin 字符串模板里的花括号。"""
    lines = src.split("\n")
    start = None
    for i, line in enumerate(lines):
        if signature in line and line.lstrip().startswith(("fun ", "private fun ", "internal fun ")):
            start = i
            break
    if start is None:
        raise RuntimeError(f"找不到函数: {signature}")
    end = len(lines)
    for i in range(start + 1, len(lines)):
        if TOP_FUN.match(lines[i]):
            end = i
            break
    return "\n".join(lines[start:end])


def bypass_nodes(src: str) -> set[str]:
    return set(BYPASS.findall(src))


def reachable_bypass(vm: str, root_sig: str) -> set[str]:
    """根函数 + 它直接调用的同类私有辅助函数（一层展开，覆盖 applyLowfreqBypass 这类统一入口）。"""
    root = body_of(vm, root_sig)
    covered = bypass_nodes(root)
    for name in set(re.findall(r"\b(apply\w+)\(\)", root)):
        sig = f"fun {name}("
        if sig in vm:
            covered |= bypass_nodes(body_of(vm, sig))
    return covered


def collect(selftest: bool = False) -> tuple[set[str], set[str], set[str]]:
    vm, acm, eg, engine = read(VM), read(ACM), read(EG), read(ENGINE)

    all_nodes = set(CONST.findall(engine))
    front = reachable_bypass(vm, "private fun applyEqSettings()")
    headless = bypass_nodes(body_of(acm, "private fun replaySettings()"))
    # headless 的表驱动部分在 EffectGroups 里
    headless |= set(SLOT.findall(eg))

    if selftest:
        # 自检：模拟「低频管家三槽没补进前台」——应当被报出来
        front -= {"DYNAMIC_BASS_NODE_INDEX", "BASS_ENHANCE_NODE_INDEX", "VIRTUAL_BASS_NODE_INDEX"}
    return all_nodes, front, headless


def main() -> int:
    selftest = "--self-test" in sys.argv
    all_nodes, front, headless = collect(selftest)

    missing_front = headless - front          # headless 有、前台没有 → 本次 P1 的形状
    missing_headless = front - headless       # 反向遗漏也一起查
    unused = all_nodes - (front | headless)   # 定义了但两条路径都没重放

    print(f"AudioEngine 节点常量: {len(all_nodes)}")
    print(f"前台 applyEqSettings 旁路覆盖: {len(front)}")
    print(f"headless replaySettings 旁路覆盖: {len(headless)}")

    problems = []
    if missing_front:
        problems.append("前台建链漏重放旁路（重启后开关全开但没音效）: " + ", ".join(sorted(missing_front)))
    if missing_headless:
        problems.append("headless 路径漏重放旁路: " + ", ".join(sorted(missing_headless)))
    if unused:
        problems.append("两条路径都没重放（或该节点无需 bypass，请确认）: " + ", ".join(sorted(unused)))

    if selftest:
        ok = bool(missing_front)
        print("\n[SELF-TEST] 期望检出前台缺失 3 个节点 → " + ("检出 ✅" if ok else "未检出 ❌"))
        return 0 if ok else 1

    if problems:
        print("\n[FAIL] 双路径旁路重放不一致：")
        for p in problems:
            print("  - " + p)
        print("\n新增 DSP 节点时必须同时补两处；更推荐把 bypass 统一成"
              " EffectGroups 表驱动，让前台也走同一份描述。")
        return 1

    print("\n[PASS] 两条重放路径旁路覆盖一致，所有节点都有归属。")
    return 0


if __name__ == "__main__":
    sys.exit(main())
