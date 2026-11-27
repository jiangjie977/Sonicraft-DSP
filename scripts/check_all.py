# -*- coding: utf-8 -*-
"""
提交前静态检查总入口。

    python scripts/check_all.py

依次跑：
  1. check_node_replay.py  —— 两条「引擎重建后补发」路径的 setBypass 覆盖一致
  2. check_param_wiring.py —— 参数 id 一致 / 默认值条数 / 无死参数 / 启动必推

任一项非零退出，整体非零。接入 dsp-commit-closure skill 的「验证先行」步骤。

## 为什么要有这两个脚本

这两类 bug 的共同点是：**编译通过、运行不崩溃、只是"没生效"**。
历史上已发生：
  - 加了节点只补了一条重放路径（4 次）；
  - 参数只在 UI 事件里推送，重启后回到默认值（Ambisonic 整整两天没生效）；
  - `kDefault` 与枚举错位，开关默认值反了。
靠真机听感发现这类问题成本极高，所以在提交前用静态检查拦掉。
"""
from __future__ import annotations

import subprocess
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
CHECKS = [
    ("节点重放双路径一致", "scripts/check_node_replay.py"),
    ("参数接线（id/默认值/死参数/启动必推）", "scripts/check_param_wiring.py"),
]


def main() -> int:
    failed = []
    for title, rel in CHECKS:
        script = ROOT / rel
        if not script.exists():
            print(f"[跳过] {title}（缺 {rel}）")
            continue
        print(f"=== {title} ===")
        r = subprocess.run([sys.executable, str(script)], cwd=str(ROOT))
        if r.returncode != 0:
            failed.append(title)
        print()

    if failed:
        print("检查未通过：" + "、".join(failed))
        return 1
    print("全部静态检查通过。")
    return 0


if __name__ == "__main__":
    sys.exit(main())
