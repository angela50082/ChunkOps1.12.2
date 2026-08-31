# ChunkOps112

1.12.2 模组兼容区块编辑器（对标 MCA Selector）。设计文档见 `docs/设计文档.md`。

## 目录结构

```
ChunkOps112/
├── docs/设计文档.md          # 设计方案（v0.2：主菜单 MCA 式编辑器）
├── build.gradle             # ForgeGradle 2.3（需 JDK8 + Gradle 4.9）
├── src/main/java/com/chunkops/   # Forge 模组源码
├── tools/verifier/          # 阶段 0 纯 Java 格式验证工具（不依赖 Forge/网络）
└── report/                  # 验证报告输出
```

## 开发环境要求（阶段 0）

- JDK 8（ForgeGradle 2.3 仅支持 JDK 8；注意 JAVA_HOME 必须指向 JDK 8，不能是新版 JDK）
- Gradle 4.9（项目使用 wrapper 固定版本）
- IntelliJ IDEA（可选，命令行构建亦可）

## 验证工具用法

```bash
# 编译（JDK 8）
javac -encoding UTF-8 -d tools/verifier/out $(find tools/verifier -name '*.java')

# 查看指定区块结构
java -cp tools/verifier/out com.chunkops.verify.ChunkInspector <世界目录> <chunkX> <chunkZ>

# 扫描世界全部 region 统计
java -cp tools/verifier/out com.chunkops.verify.ChunkInspector <世界目录> --scan
```
