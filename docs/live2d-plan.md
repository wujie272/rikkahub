# RikkaHub Live2D 悬浮角色整合方案

## 一、架构设计

```
┌─────────────────────────────────────────────────────────────────┐
│ FloatingTriggerBall                                              │
│  ├── LiquidFloatingContainer (外壳: 液态吸附尾巴 + 拖拽)        │
│  │    ├── ImageView (简单图标模式)      ← 现有，默认            │
│  │    └── Live2DGLSurfaceView (Live2D模式) ← 新增，可切换       │
│  │                                                             │
│  ├── 模式切换: setMode(Mode.ICON | Mode.LIVE2D)                │
│  └── 模型管理: loadModel(path) / clearModel()                  │
│                                                                  │
│ Live2DRenderer (JNI 桥接层)                                      │
│  ├── nativeInit() / nativeRelease()                             │
│  ├── nativeLoadModel(path)                                      │
│  ├── nativeOnTouch() / nativeOnTouchesEnded()                   │
│  ├── nativeSetRandomSpeakEnabled()                              │
│  └── nativeSetExpression(name)                                  │
│                                                                  │
│ JNI → C++ (alive2d 库)                                           │
│  ├── LAppLive2DManager (模型管理)                               │
│  ├── LAppModel (Cubism 3/4/5 加载+渲染)                        │
│  ├── MotionSequencer (动作序列)                                 │
│  └── ControllerEngine (参数控制器)                              │
│                                                                  │
│ ndk-build → libalive2d.so (arm64-v8a)                           │
└─────────────────────────────────────────────────────────────────┘
```

## 二、文件清单

### 2.1 新增代码 (RikkaHub 项目内)

| 文件 | 说明 | 预估行数 |
|------|------|----------|
| `ui/overlay/Live2DRenderer.kt` | GLSurfaceView 管理 + JNI 桥接 | 150 |
| `ui/overlay/Live2DModelManager.kt` | 模型文件扫描、选择、导入 | 100 |
| 设置页 UI 更新 (SettingFloatingPage.kt) | 模式切换、模型选择 UI | +80 |
| OverlayManager 更新 | 新增模式管理方法 | +20 |

### 2.2 引入外部代码

| 来源 | 内容 | 集成方式 |
|------|------|----------|
| `EasyLive2D/alive2d` | C++ 渲染引擎 + JNI 桥接 | 复制 `alive2d/` 目录到项目 |
| Live2D 官方 | Cubism SDK Core (`Core/`) | 手动下载，不纳入 Git |
| deskpet | 交互参考 (触摸/菜单/气泡) | 参考不复制 |

### 2.3 目录结构

```
rikkahub/
├── app/
│   ├── build.gradle.kts          ← + ndkVersion + CMake 配置
│   └── src/main/
│       ├── cpp/                  ← C++ 源码 (来自 alive2d)
│       │   ├── CMakeLists.txt
│       │   ├── LAppLive2DManager.cpp
│       │   ├── LAppModel.cpp
│       │   ├── JniBridgeC.cpp
│       │   └── ...
│       ├── assets/live2d/        ← 内置示例模型 (可选)
│       └── java/me/rerere/rikkahub/ui/overlay/
│           ├── Live2DRenderer.kt  ← 新增
│           └── Live2DModelManager.kt ← 新增
├── Core/                         ← Live2D SDK (不纳入 Git)
│   ├── include/
│   └── src/
└── docs/live2d-plan.md           ← 本文档
```

## 三、构建配置变更

### 3.1 app/build.gradle.kts

```kotlin
android {
    ndkVersion = "27.0.12077973"  // 指定 NDK 版本

    defaultConfig {
        ndk {
            abiFilters += "arm64-v8a"  // 只编 64 位
        }
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }
}
```

### 3.2 GitHub Actions CI

在 `.github/workflows/` 的构建步骤中，AGP 9.2.1 会自动下载 NDK，
不需要额外配置。只要 `ndkVersion` 在 `build.gradle.kts` 里指定了就行。

## 四、Live2D 模型文件

### 4.1 支持的格式

| 格式 | 说明 |
|------|------|
| `.model3.json` | Cubism 3/4/5 标准模型 |
| `.moc3` | 模型骨骼文件 |
| `.moc` | Cubism 2 模型 (需额外支持) |
| `.zip` | 压缩包，可一键导入 |

### 4.2 文件大小

| 模型 | 文件数 | 总大小 |
|------|--------|--------|
| 标准 Live2D 角色 | 5-15 个文件 | 5-30 MB |
| 仅模型数据 | 2-5 个文件 | 2-10 MB |

### 4.3 存储位置

```
内部存储:
  /data/data/jaye.rikkahub/files/live2d/models/
    ├── my-model/
    │   ├── my-model.model3.json
    │   ├── my-model.moc3
    │   ├── texture_00.png
    │   └── ...
    └── ...

外部存储 (可选):
  /storage/emulated/0/RikkaHub/live2d/
    └── ...
```

## 五、用户交互流程

### 5.1 首次使用

```
设置 → Floating UI → 显示模式
  │
  ├── ○ 简单图标 (当前)
  │
  └── ● Live2D 角色
        │
        ├── [选择模型…] → 打开文件选择器
        │     ├── 内部存储 (已导入的模型)
        │     └── 浏览文件 (.model3.json / .zip)
        │
        └── [导入模型] → SAF 文件选择器
              ├── 选择 .zip / .model3.json
              ├── 解压到 app 内部目录
              └── 自动加载显示
```

### 5.2 日常使用

```
悬浮球显示 Live2D 角色：
  ├── 空闲时：播放待机动画 (呼吸、眨眼)
  ├── 触摸时：跟随手指视线 (眼睛追踪)
  ├── 点击时：触发点击动作 (摸头、挥手)
  ├── 拖拽时：暂停动画，跟随移动
  ├── 贴边时：半透明待机
  └── AI 执行时：显示思考状态动画
```

### 5.3 设置页面

```
┌──────────────────────────────────────┐
│  Floating UI                          │
│                                       │
│  ┌─ 显示模式 ──────────────────────┐  │
│  │ ○ 简单图标 (省电)                │  │
│  │ ● Live2D 角色                    │  │
│  │   ┌──────────────────────────┐   │  │
│  │   │ 当前: 神乐七奈           │   │  │
│  │   │ [选择模型] [导入] [清除]  │   │  │
│  │   └──────────────────────────┘   │  │
│  └──────────────────────────────────┘  │
│                                       │
│  ┌─ Live2D 设置 ───────────────────┐  │
│  │ 模型大小: [====●=====] 1.0x    │  │
│  │ 启用触摸交互: [●]               │  │
│  │ 启用随机动作: [●]               │  │
│  │ 启用音效:     [○]               │  │
│  └──────────────────────────────────┘  │
│                                       │
│  ┌─ 关于 Live2D ───────────────────┐  │
│  │ This application uses Live2D   │  │
│  │ Cubism SDK.                     │  │
│  └──────────────────────────────────┘  │
└──────────────────────────────────────┘
```

## 六、开发步骤 (按顺序)

### Step 1: 拉入 alive2d 源码
```
cp -r path/to/alive2d/alive2d/src/main/cpp app/src/main/cpp
cp -r path/to/alive2d/alive2d/src/main/java/com/arkueid/alive2d \
      app/src/main/java/com/arkueid/alive2d
```

### Step 2: 下载 Live2D SDK Core
```
从 live2d.com 下载 Cubism SDK for Native R5
解压后把 Core/ 目录放到项目根目录
```

### Step 3: 配置 CMake + NDK
修改 app/build.gradle.kts，添加 ndkVersion 和 externalNativeBuild

### Step 4: 创建 Live2DRenderer.kt
封装 GLSurfaceView + JNI 调用，暴露 Kotlin API

### Step 5: 创建 Live2DModelManager.kt
模型文件扫描、选择、导入逻辑

### Step 6: 修改 FloatingTriggerBall
添加模式切换，支持 ImageView ↔ GLSurfaceView 互换

### Step 7: 更新设置页
添加显示模式切换、模型选择、Live2D 配置

### Step 8: 测试 CI 编译
push 到 GitHub，确认 NDK 编译通过，APK 正常

## 七、风险和注意事项

| 风险 | 影响 | 缓解措施 |
|------|------|----------|
| CI 编译时间增加 | +3-5 分钟 | 只编译 arm64-v8a |
| APK 体积增加 | +10-30 MB | 模型不内置，用户自选 |
| 低端手机性能 | 掉帧 | 默认关闭，用户手动开启 |
| 模型版权 | 用户自备模型 | 不内置任何模型文件 |
| NDK 版本兼容 | AGP 9.2.1 要求 NDK 27+ | 指定明确版本 |

## 八、总结

```
总工作量: 约 3-5 天
新增代码: ~350 行 Kotlin + ~2000 行 C++ (来自 alive2d)
新增依赖: NDK + CMake + Live2D Cubism SDK Core
APK 增量: +10MB (仅引擎，不含模型)
编译方式: CI 自动编译，手机 Termux 不用管
授权: 个人开发者免费
```
