# ChunkOps112

1.12.2 模组兼容区块编辑器（对标 MCA Selector）。设计文档见 `docs/设计文档.md`。

## 目录结构

```
ChunkOps112/
├── docs/设计文档.md          # 设计方案（v0.3：主菜单 MCA 式编辑器，格式实测修正）
├── docs/阶段1-环境准备指南.md # 构建前置：JDK8/Gradle4.9 已就绪，网络恢复后执行
├── build.gradle             # ForgeGradle 2.3（需 JDK8 + Gradle 4.9）
├── src/main/java/com/chunkops/   # Forge 模组源码
├── tools/verifier/          # 阶段 0 纯 Java 格式验证工具（不依赖 Forge/网络）
├── report/格式验证报告.md     # 阶段 0 实测报告（清单 1-9 全部完成）
└── build-verifier.bat       # 验证工具一键编译
```

## 开发环境（阶段 0 已验证 ✅）

- JDK 8：`C:\Gradle\jdk8`（1.8.0_202）✅
- Gradle 4.9：`C:\Gradle\gradle-4.9` ✅（`GRADLE_USER_HOME` 用 `.gradle-home`）
- **构建已跑通**：`gradle build` → `build/libs/chunkops-0.1.0.jar` ✅
- **坑**：forge 必须用 `1.12.2-14.23.5.2847`（2854/2860 无 userdev jar，FG2.3 extractUserdev 404）
- 开发客户端：`gradle runClient`（需图形环境）

## 验证工具用法

```bash
# 编译（JDK 8）
javac -encoding UTF-8 -d tools/verifier/out $(find tools/verifier -name '*.java')

# 查看指定区块结构
java -cp tools/verifier/out com.chunkops.verify.ChunkInspector <世界目录> <chunkX> <chunkZ>

# 扫描世界全部 region 统计
java -cp tools/verifier/out com.chunkops.verify.ChunkInspector <世界目录> --scan
```
