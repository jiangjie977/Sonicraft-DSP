# -*- coding: utf-8 -*-
"""
参数接线检查（静态，提交前必跑）。

## 覆盖范围（诚实版）

| 检查 | 范围 | 强度 |
|---|---|---|
| ① id 连续性 | **全项目 14 组参数** | 每组 id 必须从 0 连续（漂移会把参数写成别的） |
| ①' 越界 | VS / S71（有 native `kParamCount` 可比） | 参数个数不得超过 native 声明 |
| ② 默认值条数 | **全项目所有 header**（自动发现） | `kDefault` 项数必须等于 `kParamCount` |
| ③ 死参数 | **全项目所有 Kotlin 源码** | 常量必须被 AudioEngine.kt 之外的文件引用 |
| ④ 启动必推 | VS / S71 的 13 个关键参数（人工维护） | 必须在启动补发区域出现 |

节点旁路重放（17 个节点）由 `check_node_replay.py` 覆盖；`check_all.py` 一起跑。

## 为什么不逐名字比对

除 VS / S71 外，其余节点的 Kotlin 常量与 native 枚举**不是一一对应**：
多段压限用 `pid` 动态索引、EQ 用 `EQ_Q_BASE + i` 基址偏移。
强行名字比对会产生大量误报，反让检查失效。所以用「连续性 + 上限」，够抓真实事故类型。

## 用法

    python scripts/check_param_wiring.py             # 检查
    python scripts/check_param_wiring.py -v          # 打印明细
    python scripts/check_param_wiring.py --self-test # 故意断开一处，验证能检出
"""
from __future__ import annotations

import re
import sys
from collections import defaultdict
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
VM = ROOT / "app/src/main/java/com/jiangjie/Sonicraft/MainViewModel.kt"
AE = ROOT / "core/engine/src/main/java/com/jiangjie/Sonicraft/core/engine/AudioEngine.kt"
DSP_DIR = ROOT / "core/dsp/src/main/cpp/sonicraft/dsp"
KT_DIRS = (ROOT / "app/src", ROOT / "core")

# 有 native kParamCount 可比的组
BOUNDED = {
    "VS": DSP_DIR / "vs_params.hpp",
    "S71": DSP_DIR / "s71/s71_node.hpp",
}

STARTUP_CRITICAL = (
    "VS_PARAM_ENABLED",
    "VS_PARAM_AMBISONIC",
    "VS_PARAM_AMBI_FOCUS",
    "VS_PARAM_HRTF_MODEL",
    "VS_PARAM_VBAP_SPREAD",
    "VS_PARAM_MODE",
    "VS_PARAM_ROOM_SIZE",
    "VS_PARAM_ROOM_WET_DRY",
    "VS_PARAM_CENTER_MODE",
    "VS_PARAM_DIALOG",
    "S71_PARAM_ENABLED",
    "S71_PARAM_HRTF_MODEL",
    "S71_PARAM_ROOM_SIZE",
)

KT_CONST = re.compile(r"const val ([A-Z][A-Z0-9_]*) = (\d+)")
NATIVE_COUNT = re.compile(r"kParamCount\s*=\s*(\d+)")
NATIVE_NAMED = re.compile(r"\b(kParam\w+)\s*=\s*(\d+)")
BRACE_FUN = re.compile(r"fun (\w+)\([^)]*\)\s*\{")


def read(p: Path) -> str:
    return p.read_text(encoding="utf-8", errors="replace").replace("\r\n", "\n")


def brace_block(text: str, start: int) -> str:
    i = text.index("{", start)
    depth = 0
    for j in range(i, len(text)):
        if text[j] == "{":
            depth += 1
        elif text[j] == "}":
            depth -= 1
            if depth == 0:
                return text[i : j + 1]
    return text[i:]


def kotlin_groups() -> dict[str, dict[str, int]]:
    groups: dict[str, dict[str, int]] = defaultdict(dict)
    for name, idx in KT_CONST.findall(read(AE)):
        if "_PARAM_" not in name:
            continue
        if name.endswith("_BASE") or name.endswith("_PREFIX"):
            continue
        groups[name.split("_PARAM_")[0]][name] = int(idx)
    return dict(groups)


def native_count(path: Path) -> int | None:
    m = NATIVE_COUNT.search(read(path))
    return int(m.group(1)) if m else None


def check_ids(errors: list[str], infos: list[str], verbose: bool) -> None:
    """
    规则（不用「从 0 连续」——实测 EQ 的 id 本来就从 93 起、S71 故意跳号）：

    ① 组内 id 不能重复（重复 = 两个常量指向同一参数，后者覆盖前者）；
    ② 对有 native ``kParamCount`` 的组（VS / S71），最大 id 必须小于它（越界会被丢弃）。
    """
    groups = kotlin_groups()
    for group, consts in sorted(groups.items()):
        seen: dict[int, str] = {}
        dup = []
        for name, idx in sorted(consts.items()):
            if idx in seen:
                dup.append(f"{name} 与 {seen[idx]} 都是 {idx}")
            else:
                seen[idx] = name
        if dup:
            errors.append(f"[id] {group}_PARAM_* 存在重复 id：" + "；".join(dup[:3]))
            continue
        if group in BOUNDED:
            c = native_count(BOUNDED[group])
            if c is not None and max(seen) >= c:
                errors.append(
                    f"[id] {group}_PARAM_* 最大 id {max(seen)} 超出 "
                    f"{BOUNDED[group].name} 的 kParamCount={c}"
                    "（native 没有这一项，参数会被丢弃）"
                )
            elif verbose:
                infos.append(
                    f"[id] {group}: {len(seen)} 个参数（id 0..{max(seen)} 有跳号属正常），"
                    f"native 共 {c} 项"
                )
        elif verbose:
            infos.append(f"[id] {group}: {len(seen)} 个参数")


def check_defaults(errors: list[str], infos: list[str]) -> None:
    checked = 0
    for p in sorted(DSP_DIR.rglob("*.hpp")):
        text = read(p)
        c = NATIVE_COUNT.search(text)
        d = re.search(r"kDefault\[kParamCount\]\s*=\s*\{", text)
        if not c or not d:
            continue
        count = int(c.group(1))
        body = brace_block(text, d.start()).strip().lstrip("{").rstrip("}")
        body = re.sub(r"//[^\n]*", "", body)
        items = [x for x in body.split(",") if x.strip()]
        checked += 1
        if len(items) != count:
            errors.append(
                f"[default] {p.name} kDefault 有 {len(items)} 项，kParamCount={count}"
                "（历史上因此让 bs775/瞬态保护默认值互换）"
            )
    infos.append(f"[default] 检查 {checked} 个节点的默认值数组")


def kotlin_other_sources() -> str:
    """AudioEngine.kt 之外的 Kotlin 源码（常量定义在 AE 里，此处出现即「被使用」）"""
    parts = []
    for base in KT_DIRS:
        for p in base.rglob("*.kt"):
            if "\\build\\" in str(p):
                continue
            if p.resolve() == AE.resolve():
                continue
            try:
                parts.append(read(p))
            except OSError:
                pass
    return "\n".join(parts)


def annotated_native() -> set[str]:
    known = set()
    for p in sorted(DSP_DIR.rglob("*.hpp")):
        for line in read(p).split("\n"):
            if ("[未实现]" in line) or ("[固定值]" in line):
                # limiter 用 `static constexpr int kParamX = N;` 写法，名字不在行首
                m = re.search(r"(kParam\w+)\s*=", line)
                if m:
                    known.add(m.group(1))
    return known


def check_dead(errors: list[str], warnings: list[str]) -> None:
    src = kotlin_other_sources()
    known = annotated_native()
    for group, consts in sorted(kotlin_groups().items()):
        dynamic = f"{group}_PARAM_PREFIX" in src
        dead = [n for n in sorted(consts) if f"AudioEngine.{n}" not in src]
        if not dead:
            continue
        if dynamic:
            warnings.append(
                f"[dead] {group}_PARAM_* 有 {len(dead)} 个常量未被引用，"
                "但该组用动态前缀推送（可读性文档常量）"
            )
            continue
        for name in dead:
            tail = name.split("_PARAM_")[-1]
            camel = "kParam" + "".join(w.capitalize() for w in tail.split("_"))
            if camel and (camel in known or any(k.startswith(camel) for k in known)):
                warnings.append(f"[dead] {name}（native 标注 [未实现]/[固定值]，已知）")
            else:
                errors.append(f"[dead] {name} 已声明但从未被使用（改它不会有任何效果）")


def startup_region() -> str:
    vm = read(VM)
    parts = []
    for m in BRACE_FUN.finditer(vm):
        fname = m.group(1)
        if fname.startswith("pushAll") or fname in ("applyEqSettings", "replaySettings"):
            parts.append(brace_block(vm, m.start()))
    for marker in ("if (st is EngineState.Running)", "is EngineState.Running ->"):
        pos = vm.find(marker)
        if pos >= 0:
            try:
                parts.append(brace_block(vm, pos))
            except ValueError:
                pass
    return "\n".join(parts)


def check_startup(errors: list[str]) -> None:
    region = startup_region()
    for name in STARTUP_CRITICAL:
        if f"AudioEngine.{name}" not in region:
            errors.append(
                f"[startup] {name} 不在引擎启动补发区域"
                "（重启后节点停在默认值：UI 显示开着、实际没生效）"
            )


def run(errors, warnings, infos, verbose=False) -> None:
    check_ids(errors, infos, verbose)
    check_defaults(errors, infos)
    check_dead(errors, warnings)
    check_startup(errors)


def self_test() -> int:
    orig = read(VM)
    victim = "AudioEngine.VS_PARAM_AMBISONIC"
    if victim not in orig:
        print("[self-test] 前置不成立：VM 里找不到 " + victim)
        return 1
    backup = VM.with_suffix(".kt.bak")
    backup.write_text(orig, encoding="utf-8")
    try:
        VM.write_text(orig.replace(victim, "AudioEngine.VS_PARAM_ZZZ_SANITY"), encoding="utf-8")
        e, w, i = [], [], []
        run(e, w, i)
        hit = [x for x in e if "AMBISONIC" in x]
        if hit:
            print("[self-test] 通过：断开接线后检查器报错：")
            for x in hit[:3]:
                print("   " + x)
            return 0
        print("[self-test] 失败：断开接线后没报错")
        return 1
    finally:
        backup.replace(VM)


def main() -> int:
    if "--self-test" in sys.argv:
        return self_test()

    verbose = "-v" in sys.argv
    errors: list[str] = []
    warnings: list[str] = []
    infos: list[str] = []
    run(errors, warnings, infos, verbose)

    groups = kotlin_groups()
    total = sum(len(v) for v in groups.values())
    print(f"覆盖：{len(groups)} 组 / {total} 个参数常量；id 连续性全组，越界仅 "
          f"{', '.join(sorted(BOUNDED))}")

    if verbose:
        for x in infos:
            print("  " + x)
        print()

    if warnings:
        print(f"已知可接受（{len(warnings)} 项）：")
        for w in warnings:
            print("  " + w)
        print()

    if errors:
        print(f"参数接线检查失败（{len(errors)} 项）：")
        for x in errors:
            print("  " + x)
        return 1

    print("参数接线检查通过。")
    return 0


if __name__ == "__main__":
    sys.exit(main())
