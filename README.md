# WoTB wotbiosad

一个 LSPosed 模块：让《坦克世界闪击战》**网易渠道安卓版**（`com.netease.wotb`）在登录时以**iOS 平台身份**换取 WGNI 令牌，从而在安卓端进入同一网易账号的 **iOS 侧车库**。

---

## ⚠️ 免责声明 / 风险警告（使用前必读）

- **本工具会修改网易《坦克世界闪击战》客户端的登录流程，明确违反《网易用户使用协议》及游戏相关用户协议。** 使用本工具产生的**一切后果由使用者自行承担**。

- **账号处罚风险**：本工具构造的登录凭据存在可被服务端识别的异常特征，网易/官方随时可能加入检测。**你的账号可能被警告、封禁、回收，包括其中所有虚拟财产，作者不承担任何责任，也无法挽回。**

- 仅限用于**本人拥有并登录过的账号**，仅限个人学习研究用途。严禁用于盗取他人账号、账号交易、代练、商业化等任何损害他人或平台利益的行为。

- 本项目与网易（NetEase）、Wargaming 均无任何关联，亦未获得任何授权。

- 本项目按"现状"提供，**不提供任何明示或默示的担保**。作者不对因使用本软件而产生的任何直接或间接损失负责。下载、编译或使用本软件即表示你已阅读、理解并接受上述全部条款。

- **如果不同意以上任何一条，请立即停止使用并删除本仓库。**

### Disclaimer (EN)

This module modifies the login flow of *World of Tanks Blitz* (NetEase CN edition) on
Android, which **violates the game's Terms of Service**. Your account may be
suspended or banned — **use at your own risk**. For personal study/research on
accounts you own only. Provided "as is", without warranty of any kind. Not
affiliated with NetEase or Wargaming.

---

## 背景：为什么同一个网易账号有两份车库

《坦克世界闪击战》国服中，同一网易通行证在 iOS 端和安卓端对应**两个互相独立的游戏账号（车库）**。实测表明两端分流的根因在网易账号体系：

- 登录后由网易 GAS 签发的身份中，账号名与 JWT `sub` 都携带平台命名空间： `<sdkuid>@ios.netease.win.163.com:netease:ios`（iOS 端）；
- 登录请求 sauth 里的 `platform` 字段与之一一对应：安卓端为 `ad`，iOS 端为 `ios`；
- `gameid` 两端相同（`ma67`）。

也就是说，WGNI 按"平台身份"下发不同的游戏账号令牌。本模块即利用这一点，在安卓端把 sauth 的平台字段改写为 `ios`。

## 工作原理

登录链路：

```
网易 UniSDK 登录
  → 产出 sauth JSON（platform / source_platform / sessionid …）
  → native 将 sauth_json POST 给 WGNI
  → WGNI 按平台身份下发令牌（access_token + id_token）
  → 凭令牌登录游戏服，进入对应平台的车库
```

模块在 sauth 组装阶段把 `platform` / `source_platform` 从 `ad` 改写为 `ios`，采用三层拦截：

| 层 | 拦截点 | 说明 |
|---|---|---|
| A | `org.json.JSONObject.put` / `putOpt` | 组装期改写，保证后续散列字段自洽 |
| B | `org.json.JSONObject.toString` | 序列化期兜底，覆盖不走 `put` 的组装路径 |
| C | `getSauthJson` 族方法 + `ClassLoader.loadClass` 扫描 | 在 sauth 产出点改写；方法名是 JNI 字面量引用，不受壳混淆影响；扫描器兜底插件 ClassLoader 晚加载 |

`sauth` 内的 `sessionid` 是网易 GAS 的签名令牌，**本模块不修改它**。

## 构建

要求：JDK 17+、Android SDK（compileSdk 34）。纯 Java 模块，无 NDK/CMake 依赖。

```sh
./gradlew assembleDebug
# 产物: app/build/outputs/apk/debug/app-debug.apk
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

> Windows 下若报 "Gradle requires JVM 17"，请把 `JAVA_HOME` 指向 JDK 17+。

## 使用

1. 设备需要 root + [LSPosed](https://github.com/LSPosed/LSPosed)；
2. LSPosed 管理器 → 模块 → 启用 **WoTB wotbiosad**；
3. 作用域勾选 **`com.netease.wotb`**（网易渠道安卓版）；
4. 强停并重新打开游戏客户端；
5. 登录后即进入该网易账号的 iOS 侧车库。

### 验证是否生效

logcat（tag `WotB-IOS2A` / `WotBIOS2A`）或 trace 文件 `/data/data/com.netease.wotb/files/wotb_ios2a.log` 中出现：

```
[Sauth] put platform: ad -> ios
```

即表示改写已生效。

## 配置

`SauthPlatformHook.java` 顶部的静态字段：

| 字段 | 默认 | 说明 |
|---|---|---|
| `ENABLED` | `true` | 总开关 |
| `TARGET_PLATFORM` | `"ios"` | 目标平台码（UniSDK 短码：ios / hm / pc / web） |
| `REWRITE_KEYS` | `platform, source_platform` | 参与改写的字段 |
| `SOURCE_VALUES` | `ad, android…` | 命中才改写的源值 |
| `HOOK_JSON_PUT` / `HOOK_JSON_TOSTRING` / `HOOK_SAUTH_METHODS` / `HOOK_CLASS_SCANNER` | `true` | 分层开关（排障用） |
| `EXTRA_OVERRIDES` | 空 | 任意字段强制覆盖，如 `addOverride("gameid", "…")` |

## 排障

1. trace/log 中无 `[Sauth]` 改写行：确认 LSPosed 作用域、模块已启用并强停重启游戏；
2. 改写生效但登录被拒（疑似 `step`/`step2` 完整性校验）：关闭 `HOOK_JSON_TOSTRING`只保留 A 层（组装期改写，散列自洽）后重试；
3. 改写生效但车库不变：说明服务端策略调整，可用 `EXTRA_OVERRIDES` 试验其它字段，或对同一账号在两端各抓一次 sauth 做 diff 定位新的区分维度。

## 已知限制

- 依赖网易服务端当前的行为（按 sauth 平台字段路由）。官方随时可能收紧校验使本模块失效，或对异常登录进行处罚——见顶部风险警告；
- `sessionid` 由 GAS 签名，无法跨平台伪造，本模块也不尝试；
- 仅在网易国服（`com.netease.wotb`）验证过，其它渠道/海外版不适用。

## 目录结构

```
wotbiosad/
├── app/src/main/
│   ├── java/com/blizzard225/wotbiosad/
│   │   ├── MainHook.java            # 模块入口（壳脱壳后挂载、兜底重试）
│   │   └── SauthPlatformHook.java   # sauth 平台改写（三层拦截）
│   ├── AndroidManifest.xml          # xposed 元数据，scope=com.netease.wotb
│   └── assets/xposed_init           # 入口声明
└── build.gradle / settings.gradle / gradle.properties
```

## 许可证

[MIT](LICENSE)。再次提醒：许可证仅约束代码的使用与分发，**不改变使用本模块违反游戏用户协议的事实，账号处罚风险由使用者自行承担**。
