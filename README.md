# SonySelfieRemote v0.4.2 — A7R II Connection Fix

本版重点修复 A7R II 的连接逻辑，同时保留 v0.4 的自拍工作流。

## 连接修复

- 不再把 Camera Remote API 固定为 `:10000`。
- A7R II / A7 II 时代 Sony Alpha 优先探测 `http://192.168.122.1:8080/sony/camera`。
- 同时保留 `:10000` 兼容其他 Sony Camera Remote API 设备。
- 尝试读取 `:64321` 上的 Sony UPnP / ScalarWebAPI 设备描述，优先使用相机自己公布的 API 地址。
- 手填 IP 时只探测该主机，不会失败后偷偷跳到 `10.0.0.1`。
- 可以直接填写：
  - `192.168.122.1`
  - `192.168.122.1:8080`
  - `http://192.168.122.1:8080/sony/camera`
- 连接状态会显示实际正在尝试/最终连接的 `IP:端口`，便于实机排错。

## A7R II 正确准备方式

1. 相机进入 **Applications → Smart Remote Embedded**。
2. 等相机显示 Wi‑Fi/等待手机连接的画面。
3. Android 手机连接相机的 Wi‑Fi。
4. 打开本 App，IP 留空直接点“连接相机”。
5. 若自动发现失败，可填写 `192.168.122.1:8080` 再连接。

连接后 App 会调用 `startRecMode`，再调用 `startLiveview`。

## 自拍默认流程

`实时取景 → 15秒倒计时 → 拍3张 → 每张间隔1秒 → 回传本组预览 → 显示5秒 → 自动回到实时取景`

其余功能沿用 v0.4.1。
