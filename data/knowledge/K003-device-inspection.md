# 摄像头与监控设备巡检

> Synthetic Demo Data — 仅供 MineGuard 演示。

设备状态分为 ONLINE、OFFLINE、DEGRADED。OFFLINE 需要检查供电、网络与边缘节点；DEGRADED 需要检查画面抖动、丢帧、遮挡和延迟。LOW_LIGHT 低照度事件应结合补光、镜头清洁和安装角度处理。

巡检必须记录设备编号、检查时间、现象、处置动作和恢复验证。改变检测任务运行状态属于系统写操作，应经过授权并在执行后验证。
