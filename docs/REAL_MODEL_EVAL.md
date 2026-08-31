# 真实模型评测

当前状态：真实 DeepSeek 已完成独立评测，原始固定基线 9/30（30%），Planning v2 两轮均为 29/30（96.67%）。结果与边界见 [当前评测总览](EVAL_REPORT.md) 和 [规划器改进报告](PLANNING_IMPROVEMENT.md)。

冻结 Planning v2 后新增的 24 条留出题首轮 **21/24（87.50%）**，31 次真实请求、54,070 Token，未针对结果调参重测。见 [留出结果与原始 JSON](HOLDOUT_EVAL.md)。它与固定回归使用不同分母，不互相替代。

以下保留旧版离线运行说明，其中的状态只描述那次离线运行，不代表项目当前状态。本文件不再被离线评测覆盖。

<details>
<summary>历史确定性运行状态（不是当前项目状态）</summary>

运行状态： NOT RUN

此文件随确定性评测生成，不代表独立真实模型评测的最新状态。真实 DeepSeek 结果见 `docs/DEEPSEEK_ACCEPTANCE.md`，逐调用回执保存在独立运行目录，不覆盖本确定性快照。已提供超时和进程内调用上限，尚无货币预算硬限额。使用说明见 `docs/DEEPSEEK_SETUP.md`。

</details>
