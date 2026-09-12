# IDEM-STOP-04 幂等契约

> Synthetic Demo Data — 仅用于 MineGuard 检索评测。

幂等契约 IDEM-STOP-04 用于停止检测任务。相同 idempotency_key 的重复请求必须返回同一业务结果，不得重复执行设备写操作；审批、执行回执和最终状态仍需关联到同一任务。
