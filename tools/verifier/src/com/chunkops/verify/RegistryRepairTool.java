package com.chunkops.verify;

import com.chunkops.core.RegistryDrift;
import com.chunkops.core.RegistryRepair;
import com.chunkops.core.RegistrySnapshot;
import com.chunkops.core.SnapshotStore;

import java.io.File;

/**
 * 注册表编号修复命令行工具（离线可用，不需要启动游戏）。
 *
 * 用法：
 *   RegistryRepairTool drift &lt;旧快照.json&gt; &lt;当前快照.json&gt;
 *       只算漂移表，打印摘要（改了哪些、缺了哪些）。
 *
 *   RegistryRepairTool scan  &lt;维度目录&gt; &lt;旧快照.json&gt; &lt;当前快照.json&gt;
 *       干跑：扫描存档，报告有多少区块/palette 项需要改写（不写盘）。
 *
 *   RegistryRepairTool apply &lt;维度目录&gt; &lt;旧快照.json&gt; &lt;当前快照.json&gt; [--mark &lt;存档目录&gt;]
 *       真修复：改写 palette（自动 .mcabackup 备份 + 回读校验），--mark 时把当前快照写入存档目录作为新指纹。
 *
 *   RegistryRepairTool shift &lt;快照.json&gt; &lt;输出.json&gt; &lt;偏移量&gt;
 *       造一个「假想的另一套编号」快照（注册表 id 整体 ±偏移，meta 不动），用于自测修复流程。
 *
 *   RegistryRepairTool names &lt;维度目录&gt; &lt;快照.json&gt; [区块索引]
 *       把某区块的方块按名字统计（核对修复前后语义是否一致）。
 */
public class RegistryRepairTool {

    public static void main(String[] args) throws Exception {
        if (args.length < 2) {
            usage();
            return;
        }
        String cmd = args[0];
        if ("drift".equals(cmd)) {
            RegistrySnapshot oldSnap = RegistrySnapshot.load(new File(args[1]));
            RegistrySnapshot curSnap = RegistrySnapshot.load(new File(args[2]));
            RegistryDrift d = RegistryDrift.build(oldSnap, curSnap);
            System.out.println("旧快照: " + args[1] + "  mappingHash=" + SnapshotStore.shortHash(SnapshotStore.mappingHash(oldSnap)));
            System.out.println("当前快照: " + args[2] + "  mappingHash=" + SnapshotStore.shortHash(SnapshotStore.mappingHash(curSnap)));
            System.out.println("漂移: " + d.summary());
            System.out.println("  编号变化样例: " + sample(d, 8));
            System.out.println("  当前注册表缺失样例: " + d.missingSamples(8));
        } else if ("scan".equals(cmd) || "apply".equals(cmd)) {
            File dimDir = new File(args[1]);
            RegistryDrift d = RegistryDrift.build(
                    RegistrySnapshot.load(new File(args[2])),
                    RegistrySnapshot.load(new File(args[3])));
            boolean apply = "apply".equals(cmd);
            RegistryRepair.Report r = RegistryRepair.repair(dimDir, d, apply);
            printReport(r);
            if (apply && args.length >= 6 && "--mark".equals(args[4])) {
                File worldDir = new File(args[5]);
                File snapFile = new File(args[3]);
                File gameDir = guessGameDir(worldDir);
                String hash = SnapshotStore.mappingHash(RegistrySnapshot.load(snapFile));
                File arch = SnapshotStore.archiveFile(gameDir, snapFile);
                SnapshotStore.writeWorldMark(worldDir, new SnapshotStore.WorldMark(
                        hash, arch.getName(), System.currentTimeMillis()));
                System.out.println("已写入世界指纹: " + SnapshotStore.worldMarkFile(worldDir)
                        + "  (hash=" + SnapshotStore.shortHash(hash) + ", snapshot=" + arch.getName() + ")");
            }
        } else if ("audit".equals(cmd)) {
            // 审计：按 old 快照给每个 stateId 取名字 → 用 new 快照查回 id → 再用 new 快照取名字，
            // 名字应当不变。变了就说明"同一 id 对应多个名字"（无效 meta 归一化）导致了映射歧义。
            RegistrySnapshot oldSnap = RegistrySnapshot.load(new File(args[1]));
            RegistrySnapshot curSnap = RegistrySnapshot.load(new File(args[2]));
            java.util.Map<Integer, String> seenName = new java.util.TreeMap<Integer, String>();
            long checked = 0, unstable = 0, dupName = 0;
            java.util.Map<String, Integer> badExample = new java.util.TreeMap<String, Integer>();
            for (RegistrySnapshot.BlockEntry e : oldSnap.blocks) {
                if (e.id == 0) continue;
                String canonical = oldSnap.lookupBlockName(e.id);   // 反向表（唯一）
                Integer curId = curSnap.lookupBlockId(canonical);
                if (curId == null) continue;
                String back = curSnap.lookupBlockName(curId.intValue());
                checked++;
                if (!canonical.equals(back)) {
                    unstable++;
                    if (badExample.size() < 12) badExample.put(canonical + " → " + back, 1);
                }
                if (!e.name.equals(canonical)) dupName++;
            }
            System.out.println("按名字对齐的往返检查: 共 " + checked + " 个状态，不稳定 " + unstable
                    + "（旧快照里「同 id 多名」的条目数 " + dupName + "）");
            for (String s : badExample.keySet()) System.out.println("  ⚠ " + s);
        } else if ("layer".equals(cmd)) {
            // 底层体检：正常世界的 Y=0 section 最底层应该是基岩（minecraft:bedrock#15）。
            // 若编号被改错（例如按错误的映射表"修复"过），这里会显示成别的方块——最直接的判据。
            File dimDir = new File(args[1]);
            RegistrySnapshot s = RegistrySnapshot.load(new File(args[2]));
            String regionName = args.length >= 4 ? args[3] : "r.0.0.mca";
            java.io.File region = new java.io.File(new java.io.File(dimDir, "region"), regionName);
            RegionReader rr = new RegionReader(region);
            java.util.Map<String, Long> bottom = new java.util.TreeMap<String, Long>();
            int chunkCount = 0;
            int noBedrock = 0;
            for (int idx = 0; idx < com.chunkops.verify.RegionReader.CHUNKS_PER_REGION; idx++) {
                byte[] payload = rr.readChunkData(idx);
                if (payload == null) continue;
                com.chunkops.verify.NbtNode root;
                try {
                    root = com.chunkops.core.RegionWriter.unpackChunk(payload);
                } catch (Exception e) {
                    continue;
                }
                com.chunkops.verify.NbtNode level = root.get("Level");
                if (level == null) continue;
                for (com.chunkops.verify.NbtNode sec : com.chunkops.core.SectionCodec.sectionsOf(level)) {
                    if (com.chunkops.core.SectionCodec.sectionY(sec) != 0) continue;
                    int[] ids = com.chunkops.core.SectionCodec.decode(sec);
                    if (ids == null) continue;
                    chunkCount++;
                    boolean hasBedrock = false;
                    for (int i = 0; i < 256; i++) { // y=0 这一层（索引 0..255）
                        String name = s.lookupBlockName(ids[i]);
                        if (name == null) name = "UNKNOWN:" + ids[i];
                        if ("minecraft:bedrock#15".equals(name) || name.startsWith("minecraft:bedrock")) hasBedrock = true;
                        if (ids[i] == 0) name = "(air)";
                        Long c = bottom.get(name);
                        bottom.put(name, c == null ? 1 : c + 1);
                    }
                    if (!hasBedrock) noBedrock++;
                }
            }
            System.out.println(regionName + " Y=0 section 数 " + chunkCount
                    + "，其中底层无基岩的 " + noBedrock + (chunkCount == 0 ? "" : ("（无基岩比例 "
                    + (100 * noBedrock / Math.max(1, chunkCount)) + "%）")));
            int n = 0;
            for (java.util.Map.Entry<String, Long> e : bottom.entrySet()) {
                if (n++ >= 10) { System.out.println("  …"); break; }
                System.out.println("  底层 " + e.getKey() + " x" + e.getValue());
            }
        } else if ("shift".equals(cmd)) {
            RegistrySnapshot s = RegistrySnapshot.load(new File(args[1]));
            int off = Integer.parseInt(args[3]);
            for (RegistrySnapshot.BlockEntry e : s.blocks) {
                int regId = e.id >> 4;
                if (regId == 0) continue; // 注册号 0 = 空气，真实环境里不会重排
                e.id = ((regId + off) << 4) | (e.id & 15);
            }
            for (RegistrySnapshot.BiomeEntry e : s.biomes) {
                e.id = e.id + off;
            }
            s.save(new File(args[2]));
            System.out.println("已生成偏移快照: " + args[2] + "（方块注册号 " + (off >= 0 ? "+" : "") + off + "）");
        } else if ("names".equals(cmd)) {
            File dimDir = new File(args[1]);
            RegistrySnapshot s = RegistrySnapshot.load(new File(args[2]));
            String regionName = args.length >= 4 ? args[3] : "r.0.0.mca";
            java.util.Map<String, Long> counts = new java.util.TreeMap<String, Long>();
            long total = 0;
            long unknown = 0;
            int chunks = 0;
            java.io.File region = new java.io.File(new java.io.File(dimDir, "region"), regionName);
            RegionReader rr = new RegionReader(region);
            for (int idx = 0; idx < com.chunkops.verify.RegionReader.CHUNKS_PER_REGION; idx++) {
                byte[] payload = rr.readChunkData(idx);
                if (payload == null) continue;
                com.chunkops.verify.NbtNode root;
                try {
                    root = com.chunkops.core.RegionWriter.unpackChunk(payload);
                } catch (Exception e) {
                    continue;
                }
                com.chunkops.verify.NbtNode level = root.get("Level");
                if (level == null) continue;
                chunks++;
                for (com.chunkops.verify.NbtNode sec : com.chunkops.core.SectionCodec.sectionsOf(level)) {
                    int[] ids = com.chunkops.core.SectionCodec.decode(sec);
                    if (ids == null) continue;
                    for (int stateId : ids) {
                        if (stateId == 0) continue;
                        total++;
                        String name = s.lookupBlockName(stateId);
                        if (name == null) {
                            name = "UNKNOWN:" + stateId;
                            unknown++;
                        }
                        Long c = counts.get(name);
                        counts.put(name, c == null ? 1 : c + 1);
                    }
                }
            }
            System.out.println(regionName + " 区块 " + chunks + "，非空气方块 " + total
                    + "，种类 " + counts.size() + "，UNKNOWN " + unknown);
            for (java.util.Map.Entry<String, Long> e : counts.entrySet()) {
                System.out.println("  " + e.getKey() + " x" + e.getValue());
            }
        } else {
            usage();
        }
    }

    private static String sample(RegistryDrift d, int n) {
        StringBuilder sb = new StringBuilder();
        int i = 0;
        for (java.util.Map.Entry<Integer, Integer> e : d.stateMap.entrySet()) {
            if (i++ >= n) { sb.append("…"); break; }
            sb.append(e.getKey()).append("→").append(e.getValue()).append(" ");
        }
        return sb.length() == 0 ? "（无）" : sb.toString();
    }

    public static void printReport(RegistryRepair.Report r) {
        System.out.println((r.applied ? "[修复]" : "[干跑]") + " region " + r.regionFiles
                + "，扫描区块 " + r.chunksSeen + "，需修改 " + r.chunksChanged
                + (r.applied ? "，已写入 " + r.chunksWritten : ""));
        System.out.println("  palette 项 " + r.paletteEntries + " → 改写 " + r.paletteRemapped
                + "；生物群系项 " + r.biomeEntries + " → 改写 " + r.biomeRemapped
                + "；legacy 整段重建 " + r.legacySections);
        if (r.unmappablePalette > 0 || r.unmappableBiomes > 0) {
            System.out.println("  ⚠ 当前注册表里已不存在的方块项 " + r.unmappablePalette
                    + "（保留原值）、群系项 " + r.unmappableBiomes);
            int n = 0;
            for (java.util.Map.Entry<String, Long> e : r.unmappableNames.entrySet()) {
                if (n++ >= 8) { System.out.println("    …"); break; }
                System.out.println("    " + e.getKey() + " x" + e.getValue());
            }
        }
        if (r.verifyFailed > 0) System.out.println("  ⚠ 回读校验失败 " + r.verifyFailed + " 个区块");
        for (String s : r.notes) System.out.println("  注: " + s);
        System.out.println("  用时 " + r.elapsedMs + " ms");
    }

    /** 由存档目录猜 gameDir：…/saves/&lt;世界&gt; → …；否则用存档目录本身。 */
    private static File guessGameDir(File worldDir) {
        File saves = worldDir.getParentFile();
        if (saves != null && "saves".equalsIgnoreCase(saves.getName())) {
            File game = saves.getParentFile();
            if (game != null) return game;
        }
        return worldDir;
    }

    private static void usage() {
        System.out.println("用法:");
        System.out.println("  RegistryRepairTool drift <旧快照.json> <当前快照.json>");
        System.out.println("  RegistryRepairTool scan  <维度目录> <旧快照.json> <当前快照.json>");
        System.out.println("  RegistryRepairTool apply <维度目录> <旧快照.json> <当前快照.json> [--mark <存档目录>]");
        System.out.println("  RegistryRepairTool shift <快照.json> <输出.json> <偏移量>");
        System.out.println("  RegistryRepairTool names <维度目录> <快照.json> [区块索引]");
    }
}
