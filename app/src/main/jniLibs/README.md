# JNI 原生库

本目录放置 Live2D 渲染所需的 .so 文件。

## 需要的文件

```
jniLibs/arm64-v8a/
├── libluajit.so    ← LuaJIT 运行时 (需要手动编译)
└── libzstd-jni.so  ← Zstd 解压库 (从 Maven AAR 提取)
```

libbandoripet.so 由 CMake 在 Gradle 构建时自动编译，无需手动放置。

## 获取 libluajit.so

1. 克隆 LuaJIT: git clone https://github.com/LuaJIT/LuaJIT.git
2. 用 NDK 交叉编译 Android arm64 版本:
   ```
   cd LuaJIT
   make HOST_CC="gcc" CROSS=arm64-v8a- CC=aarch64-linux-android-gcc
   ```
   或从 https://github.com/EasyLive2D/Live2D-v2-Lua 获取预编译版本

## 获取 libzstd-jni.so

从 Maven 依赖中提取:
1. 在本地编译一次项目
2. 从 build/cache 或 Gradle 缓存中找到 zstd-jni AAR
3. 解压后提取 jni/arm64-v8a/libzstd-jni-*.so
