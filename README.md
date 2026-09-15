# ChunkOps112 · 区块编辑器（Minecraft 1.12.2 Forge）

> 一个**在游戏内运行的区块级地图编辑器**：像看地图一样浏览你的存档，框选区块做 **删除 / 清空 / 剪裁 / 统计**，
> 并把建筑**跨存档、跨维度**搬运过去——搬运走「方块名称」而不是数字 ID，尽量不串方块。

![Minecraft](https://img.shields.io/badge/Minecraft-1.12.2-62B47A)
![Forge](https://img.shields.io/badge/Forge-14.23.5.x-orange)
![License](https://img.shields.io/badge/License-MIT-blue)

---

## 这是什么

**ChunkOps112** 是一个客户端模组（单人存档用），对标 **MCA Selector**，但**不脱离游戏**：
它直接读取/写入存档的 `region/*.mca` 文件，在游戏里给你一张 MCA 风格的 2D 地图界面，
不用退出游戏、不用把存档搬进别的工具，就能做区块级编辑与搬运。

典型用途：

- 删掉卡地形 / 报错的区块，让游戏重新生成；
- 剪裁掉多余区域（只保留框选的部分）；
- 查看某片区域都由什么方块组成（按方块名统计数量）；
- 把一座建筑从 A 存档搬到 B 存档、从主世界搬到别的维度。

## 主要功能

| 功能 | 说明 |
|---|---|
| 🗺️ **地图式浏览** | 直接读存档文件渲染，无需先加载世界；颜色 = 方块地图色 × 生物群系调色 × 高度/坡度明暗（有立体感） |
| 🧭 **多维度** | 主世界 / 下界 / 末地 / 模组维度（自动扫描存档下的 `DIM*` 目录） |
| 🧹 **区块操作** | 框选后：**移除**（删掉让游戏重生成）、**清空**（保留空区块）、**剪裁**（删除框选之外）、**统计** |
| 📋 **跨存档复制粘贴** | 复制框选区域 → 切到另一个存档/维度 → 粘贴；按「方块名称 + meta」搬运，同一整合包内不串方块 |
| 🧱 **完整保留数据** | 搬运时连同**方块实体（箱子/机器等）、实体、光照、生物群系、模组区块数据**一起带走 |
| 🔒 **只读模式** | 一键切换，防止误写存档 |
| 💾 **自动备份** | 每次写入前自动生成 `.mcabackup` 备份 |
| ⚡ **持久地图缓存** | 浏览过的区域缓存到 `chunkops/cache/`，二次浏览 / 重启后秒开（按区块时间戳自动失效） |

## 和类似工具的区别

同类工具其实不少，但**没有一个是"在游戏内 + 直接改存档文件 + 按方块名称跨存档搬运"的组合**：

| 工具 | 形态 | 区块删除/剪裁 | 跨存档搬建筑 | 模组方块 | 要不要关游戏 |
|---|---|---|---|---|---|
| **ChunkOps112（本模组）** | **游戏内 mod** | ✅ | ✅ **按方块名重映射**，兼容 REID/JEID | ✅ 用游戏原生注册表 | ❌ 不用（主菜单即可） |
| [MCA Selector](https://github.com/Querz/mcaselector) | 桌面程序 | ✅（核心功能） | ⚠️ 整块导出/导入原始数据，**不做名称级重映射** | ⚠️ 不依赖游戏注册表，1.12 数字 ID 场景受限 | ✅ 需要 |
| [Amulet Editor](https://www.amuletmc.com/)（MCEdit 后继） | 桌面程序 | ✅ | ✅ 3D 复制粘贴 | ⚠️ 需要版本/方块映射，跨模组集易出错 | ✅ 需要 |
| [WorldEdit](https://enginehub.org/worldedit/) | 游戏内（需进世界） | ✅ | ✅ 蓝图粘贴（1.12 蓝图是数字 ID） | ⚠️ 跨整合包粘贴易串块 | 需在世界内 |
| [Litematica](https://github.com/maruohon/litematica) | 游戏内（全息投影） | ❌ | ⚠️ 只是"照着搭"，不写存档 | ✅ | 需在世界内 |
| Chunk-Pregenerator | 游戏内 mod | ✅（删除/重新生成） | ❌ | — | 需在世界内 |

**本模组存在的理由**：给**大型模组整合包**（尤其是用 REID/JEID、方块数字 ID 会重排的环境）提供一个"在游戏里、
不关游戏、不串方块"的存档编辑与搬运工具。这也是它名字里 "ChunkOps" 的由来——区块操作（operations），
而不是又一个 3D 建筑编辑器。

## 安装

1. 安装 **Minecraft 1.12.2 + Forge**（14.23.5.28xx）；
2. 把 `chunkops-0.1.0.jar` 放进 `mods/` 目录；
3. 启动游戏即可（**客户端模组**，服务端不需要）。

> 已在大型整合包环境实测（587 个模组、使用 REID/JEID 的 1.12.2 整合包）。

## 怎么打开编辑器（两个入口）

1. **主菜单 →「区块编辑器」按钮**；
2. **主菜单 →「模组」→ 列表最上方的 `! ChunkOps 区块编辑器` →「配置」→「打开区块编辑器」**
   —— 这条路**始终可用**：主菜单被 FancyMenu / CustomMainMenu 之类的美化模组改造时，第 1 个入口的按钮可能被隐藏，请用这个。

## 快速上手

### 1. 看图与框选
- **左键拖动**：框选区块（再次拖动重新框选）
- **中键拖动**：平移地图 ｜ **滚轮**：缩放 ｜ **右键**：取消框选
- 左下角实时显示光标指向的**区块坐标 / 方块坐标**

### 2. 做操作（右侧「工具」面板或快捷键）
| 按钮 | 快捷键 | 作用 |
|---|---|---|
| 移除选区 | `R` | 删除框选区块（之后进游戏由游戏重新生成） |
| 清空选区 | `C` | 保留区块但清空内容 |
| 剪裁 | `T` | 删除框选**之外**的所有区块（只留框选部分） |
| 统计 | `S` | 统计框选内的方块种类与数量（可过滤 / 排序） |
| 复制 | `Ctrl+C` | 把框选区域复制到剪贴板（名称化） |
| 粘贴 | `Ctrl+V` | 进入粘贴预览 → 拖动定位 → 再按一次 `Ctrl+V` 确认写入 |
| 只读: 开/关 | — | 打开后禁止一切写入操作 |
| 帮助 | `F1` / `?` | 快捷键说明 |

### 3. 跨存档搬运建筑
1. 选好**源存档**（左上「存档」下拉）→ 框选建筑 → `Ctrl+C`；
2. 用「存档」下拉切到目标存档（必要时用「维度」下拉切换维度）；
3. `Ctrl+V` **预览**（半透明幽灵图）→ 左键拖动到目标位置 → 再按 `Ctrl+V` **确认写入**；
4. 进游戏查看效果。

## ⚠️ 重要限制与注意事项（务必先读）

### 1️⃣ 编辑前后不要改动 mods 目录（最重要）

在 **REID / JEID 环境**下，部分模组（如 Forestry、EnderIO、Quark 一类）的**方块注册编号会随 mods 目录内容变化而重排**。
也就是说：**只要你增删或替换了 mods 里的任何 jar**，存档里这些模组的方块编号就"过期"了，
再进游戏可能表现为：**方块变成别的变体、变成空气、或复制粘贴后错位**。

👉 因此：

- 对存档做区块操作（尤其复制粘贴）时，**保持 mods 目录与写档时完全一致**；
- 不要在"复制 → 粘贴"之间更换 mod / 更新整合包 / 更换本模组 jar；
- 需要更新模组时：先备份存档，更新后**在当前会话里重新保存一次相关区域**再继续编辑。

**自检方法**：每次启动游戏后看一眼 `logs/latest.log` 里的 `[ChunkOps-diag]` 行——
其中的 `storageStateId` 就是本次会话的"存储编号代际"，只要它保持不变，说明 mods 目录没被扰动。

### 2️⃣ 必须先备份存档

编辑器直接写 `region` 文件。虽然**每次写入都会自动生成 `.mcabackup` 备份**，但仍请：

- 操作前手动复制一份整个存档目录；
- 不要在游戏正占用该存档时写入（编辑器会提示检测到 `session.lock`）；
- 建议先开「只读」浏览，确认无误再关掉只读做写入。

### 3️⃣ 跨整合包搬运不保证一致

复制粘贴走「方块名称 + meta」映射：

- **同一整合包内**：正常搬运，不串方块；
- **跨整合包**：只有**两边都存在同名方块**才能正确映射，缺失的方块会回退（可能变成空气）；
- 复制时若源区域含"当前注册表不认识"的编号，编辑器会给出**警告 + 涉及的区块坐标**，此时搬运结果不可靠。

### 4️⃣ 粘贴 = 整块覆盖

粘贴会**用源区块完整覆盖目标区块**（包括原有的方块实体 / 实体 / 生物群系）。
请确认目标区域没有你不想丢的东西（先用预览看清位置）。

### 5️⃣ 地图是"示意地图"

地图颜色由方块的**地图色（MapColor）× 生物群系调色 × 高度/坡度明暗**合成，
用于辨别地形与建筑轮廓，**不是逐方块贴图渲染**（玻璃等透明方块按纹理色近似显示）。

### 6️⃣ 其它

- 只支持 **单人存档**（直读存档文件）；多人服务器上的区块不在本工具范围内；
- 大范围操作（几万区块的剪裁/统计）会耗时，请耐心等待，不要中途强退；
- 首次浏览某区域需要解码存档（有持久缓存加速，之后很快）；
- 仅支持 **Minecraft 1.12.2**。

## 常见问题

**Q：进游戏后发现方块变成别的方块 / 变成空气了？**
先确认 **mods 目录自写档以来有没有变动**（装/删/换过任何 mod 或本模组 jar）。若变过，参照"限制 1"处理；
若没变过，把 `logs/latest.log` 里的 `[ChunkOps-diag]` 行发出来对照。

**Q：复制时弹出「源区 N 种方块编号会话不认识」的警告？**
说明源区域包含**旧会话 / 旧 mod 列表写入的编号**，这些方块无法可靠还原。按提示**在当前会话里重新放置这些方块**后再复制；
警告里会同时列出涉及的区块坐标，便于定位。

**Q：地图上有一块是空的 / 纯色？**
那是**尚未生成的区域**（虚空 / 未探索）。编辑器只显示存档里真实存在的区块。

**Q：怎么恢复自动备份？**
写入前生成的备份形如 `r.0.1.mca.1788337345679.mcabackup`，把它的名字改回 `r.0.1.mca`（覆盖同名文件）即可回滚到那次写入之前。

**Q：缓存占空间怎么办？**
地图缓存位于 `<游戏目录>/chunkops/cache/<存档名>/<维度>/`，可直接删除，下次浏览会重新生成。

## 反馈与贡献

- 仓库：<https://github.com/angela50082/ChunkOps1.12.2>
- 遇到问题请开 [Issue](https://github.com/angela50082/ChunkOps1.12.2/issues)，并附上：
  1. 你的整合包/模组列表（或整合包名 + 版本）；
  2. `logs/latest.log` 里的 `[ChunkOps-diag]` 行；
  3. 出问题时的截图或复现步骤（"复制 → 粘贴 → 进游戏查看"具体哪一步、哪个方块不对）。
- 欢迎 PR；改代码前建议先读 `docs/设计文档.md` 与 `docs/项目状态与交接简报.md`。

## 许可证

本项目采用 **MIT License**，详见 [LICENSE](LICENSE)。

## 免责声明

本模组会**直接修改你的存档文件**。请务必先备份。作者不对任何存档损坏、数据丢失或其它损失负责；
使用即表示你理解并接受这一点。

## 致谢

- [Minecraft Forge](https://files.minecraftforge.net/) —— 模组开发框架
- **MCA Selector** —— 区块编辑器交互的灵感来源
- 测试期间帮忙复现问题、提供存档样本的玩家

---

<details>
<summary><b>开发者信息（构建 / 验证工具 / 目录结构）</b></summary>

### 目录结构

```
ChunkOps112/
├── src/main/java/com/chunkops/   # Forge 模组源码（GUI / 渲染 / 缓存）
├── tools/verifier/               # 纯 Java 数据层与验证工具（零依赖，可脱离 Forge 运行）
├── docs/设计文档.md              # 设计方案
├── docs/项目状态与交接简报.md     # 当前状态 / 交接
└── report/格式验证报告.md         # 阶段 0 格式实测报告
```

### 构建

```bash
# 需要 JDK 8 与 Gradle 4.9（ForgeGradle 2.3 的限制）
export JAVA_HOME=<jdk8 目录>
export GRADLE_USER_HOME=<repo>/.gradle-home
gradle build            # 产物：build/libs/chunkops-0.1.0.jar
```

- Forge 必须用 **1.12.2-14.23.5.2847**（唯一带 userdev 的 1.12.2 构建；2854/2860 会导致 ForgeGradle extractUserdev 404）；
- 开发客户端：`gradle runClient`（需要图形环境）。

### 验证工具（纯 Java，可离线跑）

```bash
javac -encoding UTF-8 -d tools/verifier/out $(find tools/verifier/src -name '*.java')

# 用快照把 region 里每个 palette 项解码成方块名（核对存盘与快照是否一致）
java -cp tools/verifier/out com.chunkops.verify.SnapCheck <region.mca> <registry-snapshot.json>
```

其它工具：`SectionRoundTripCheck`（section 往返一致性）、`JeidEncodeCheck`（JEID 编码往返）、
`PasteScan`（粘贴区域方块多样性）、`PaletteDump`（原始 palette dump）、`DiffRegions`（备份对比）等。

</details>

<details>
<summary><b>English summary (for international visitors)</b></summary>

**ChunkOps112** is an **in-game chunk editor** for **Minecraft 1.12.2 (Forge)** — an MCA-Selector-like 2D map UI
that reads/writes your save's `region/*.mca` files directly, so you can browse, delete, clear, trim and
**copy-paste builds between saves and dimensions** without leaving the game.

Block transfers are **name-based** (`modid:block#meta`), so builds survive ID remapping within the same modpack;
tile entities, entities, light and biome data are carried along.

**Key limitation:** in REID/JEID environments some mods (Forestry, EnderIO, Quark, …) renumber their block IDs
whenever the **`mods/` folder changes**. Keep your mod list identical between editing sessions, otherwise
existing regions may be re-interpreted (wrong variants / air). Always back up your world first.

Client-side, single-player, Minecraft 1.12.2 only. License: **MIT**.

</details>
