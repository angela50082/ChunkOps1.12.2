package com.chunkops.verify;

import com.chunkops.core.Mcops;
import com.chunkops.core.RegionWriter;
import com.chunkops.core.RegistrySnapshot;
import com.chunkops.core.RemapEngine;
import com.chunkops.core.SectionCodec;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * 区块级操作工具（阶段 1：文件模式，同模组集）。
 *
 * 用法：
 *   ChunkOpsTool stats <world>                        # 统计（复用 ChunkInspector.scan）
 *   ChunkOpsTool clear <world> <cx> <cz> [--dry-run]  # 清空区块方块（保留区块结构）
 *   ChunkOpsTool remove <world> <cx> <cz> [--dry-run] # 从 region 移除区块
 *   ChunkOpsTool trim <world> <x1> <z1> <x2> <z2> [--dry-run]   # 移除范围外区块（方块坐标）
 *   ChunkOpsTool copy <srcWorld> <dstWorld> <x1> <z1> <x2> <z2> [--move] [--dry-run]
 *
 * 所有写操作前自动备份 region 到 *.mcabackup；--dry-run 只打印不写入。
 */
public class ChunkOpsTool {

    public static void main(String[] args) throws IOException {
        if (args.length < 1) {
            System.out.println("Usage: see source header");
            return;
        }
        List<String> a = new ArrayList<String>();
        boolean dryRun = false;
        boolean move = false;
        boolean abortOnMissing = false;
        boolean keepRaw = false;
        String snapshotPath = null;
        String srcSnapshotPath = null;
        for (int i = 0; i < args.length; i++) {
            String s = args[i];
            if (s.equals("--dry-run")) dryRun = true;
            else if (s.equals("--move")) move = true;
            else if (s.equals("--abort")) abortOnMissing = true;
            else if (s.equals("--keep-raw")) keepRaw = true;
            else if (s.equals("--snapshot") && i + 1 < args.length) snapshotPath = args[++i];
            else if (s.equals("--snapshot-src") && i + 1 < args.length) srcSnapshotPath = args[++i];
            else a.add(s);
        }
        String cmd = a.get(0);
        if (cmd.equals("stats")) {
            ChunkInspector.scan(new File(a.get(1)), Integer.MAX_VALUE);
            return;
        }
        if (cmd.equals("buckets")) {
            require(a, 2);
            opBuckets(new File(a.get(1)), snapshotPath);
            return;
        }
        if (cmd.equals("clear")) {
            require(a, 4);
            File world = new File(a.get(1));
            int cx = Integer.parseInt(a.get(2)), cz = Integer.parseInt(a.get(3));
            opClear(world, cx, cz, dryRun);
            return;
        }
        if (cmd.equals("remove")) {
            require(a, 4);
            File world = new File(a.get(1));
            int cx = Integer.parseInt(a.get(2)), cz = Integer.parseInt(a.get(3));
            opRemove(world, cx, cz, dryRun);
            return;
        }
        if (cmd.equals("trim")) {
            require(a, 6);
            File world = new File(a.get(1));
            int x1 = Integer.parseInt(a.get(2)), z1 = Integer.parseInt(a.get(3));
            int x2 = Integer.parseInt(a.get(4)), z2 = Integer.parseInt(a.get(5));
            opTrim(world, x1, z1, x2, z2, dryRun);
            return;
        }
        if (cmd.equals("copy")) {
            require(a, 7);
            File src = new File(a.get(1));
            File dst = new File(a.get(2));
            int x1 = Integer.parseInt(a.get(3)), z1 = Integer.parseInt(a.get(4));
            int x2 = Integer.parseInt(a.get(5)), z2 = Integer.parseInt(a.get(6));
            opCopyMove(src, dst, x1, z1, x2, z2, move, dryRun, snapshotPath, srcSnapshotPath, abortOnMissing, keepRaw);
            return;
        }
        if (cmd.equals("mcops-export")) {
            // mcops-export <world> <x1> <z1> <x2> <z2> <out.mcops> --snapshot <snap>
            require(a, 7);
            opMcopsExport(new File(a.get(1)),
                    Integer.parseInt(a.get(2)), Integer.parseInt(a.get(3)),
                    Integer.parseInt(a.get(4)), Integer.parseInt(a.get(5)),
                    new File(a.get(6)), snapshotPath);
            return;
        }
        if (cmd.equals("mcops-import")) {
            // mcops-import <world> <file.mcops> --snapshot <snap> [--dry-run]
            require(a, 3);
            opMcopsImport(new File(a.get(1)), new File(a.get(2)), snapshotPath, dryRun);
            return;
        }
        System.out.println("未知命令: " + cmd);
    }

    static void require(List<String> a, int n) {
        if (a.size() < n) throw new IllegalArgumentException("参数不足: 需要 " + n + " 个");
    }

    // ------------------------------------------------------------ clear

    /** 清空区块：删除全部 Sections 与 TileEntities（方块实体），保留区块结构与其他数据。 */
    static void opClear(File world, int cx, int cz, boolean dryRun) throws IOException {
        File regionFile = regionOf(world, cx, cz);
        int index = indexOf(cx, cz);
        RegionReader rr = new RegionReader(regionFile);
        byte[] payload = rr.readChunkData(index);
        if (payload == null) {
            System.out.println("chunk (" + cx + "," + cz + ") 不存在于 " + regionFile.getName());
            return;
        }
        NbtNode root = RegionWriter.unpackChunk(payload);
        NbtNode level = root.get("Level");
        if (level != null) {
            level.asMap().remove("Sections");
            level.asMap().remove("TileEntities");
        }
        System.out.println("clear: chunk (" + cx + "," + cz + ") -> 方块清空（保留结构）" + (dryRun ? " [DRY-RUN]" : ""));
        if (!dryRun) {
            RegionWriter rw = new RegionWriter(regionFile);
            rw.setChunk(index, RegionWriter.packChunk(root));
            File bak = rw.write();
            System.out.println("  已写入（备份: " + (bak != null ? bak.getName() : "无原文件") + "）");
        }
    }

    // ------------------------------------------------------------ remove

    static void opRemove(File world, int cx, int cz, boolean dryRun) throws IOException {
        File regionFile = regionOf(world, cx, cz);
        int index = indexOf(cx, cz);
        RegionReader rr = new RegionReader(regionFile);
        if (rr.readChunkData(index) == null) {
            System.out.println("chunk (" + cx + "," + cz + ") 不存在于 " + regionFile.getName());
            return;
        }
        System.out.println("remove: chunk (" + cx + "," + cz + ") 从 " + regionFile.getName() + " 移除" + (dryRun ? " [DRY-RUN]" : ""));
        if (!dryRun) {
            RegionWriter rw = new RegionWriter(regionFile);
            rw.removeChunk(index);
            File bak = rw.write();
            System.out.println("  已写入（备份: " + (bak != null ? bak.getName() : "无原文件") + "）");
        }
    }

    // ------------------------------------------------------------ trim

    /** 移除指定方块坐标矩形之外的所有区块（主世界）。 */
    static void opTrim(File world, int x1, int z1, int x2, int z2, boolean dryRun) throws IOException {
        int minCx = Math.floorDiv(Math.min(x1, x2), 16);
        int maxCx = Math.floorDiv(Math.max(x1, x2), 16);
        int minCz = Math.floorDiv(Math.min(z1, z2), 16);
        int maxCz = Math.floorDiv(Math.max(z1, z2), 16);
        File regionDir = new File(world, "region");
        if (!regionDir.isDirectory()) {
            System.out.println("无 region 目录: " + regionDir);
            return;
        }
        int removed = 0, total = 0;
        for (File rf : regionDir.listFiles()) {
            if (!rf.getName().toLowerCase().endsWith(".mca")) continue;
            int[] rc = RegionReader.regionCoords(rf.getName());
            if (rc == null) continue;
            RegionWriter rw = null;
            RegionReader rr = new RegionReader(rf);
            for (int idx : rr.listChunkIndices()) {
                int cx = rc[0] * 32 + (idx & 31);
                int cz = rc[1] * 32 + (idx >> 5);
                total++;
                if (cx >= minCx && cx <= maxCx && cz >= minCz && cz <= maxCz) continue;
                if (rw == null) rw = new RegionWriter(rf);
                rw.removeChunk(idx);
                removed++;
            }
            if (rw != null) {
                System.out.println("trim: " + rf.getName() + " 移除 " + removed + "/" + total + " chunk" + (dryRun ? " [DRY-RUN]" : ""));
                if (!dryRun) {
                    File bak = rw.write();
                    System.out.println("  已写入（备份: " + (bak != null ? bak.getName() : "无原文件") + "）");
                }
            }
        }
        System.out.println("trim 完成: 保留范围 [x:" + minCx + ".." + maxCx + ", z:" + minCz + ".." + maxCz + "] chunk，移除 " + removed + " / 共 " + total);
    }

    // ------------------------------------------------------------ copy/move

    static void opCopyMove(File srcWorld, File dstWorld, int x1, int z1, int x2, int z2,
                           boolean move, boolean dryRun, String dstSnapshotPath, String srcSnapshotPath,
                           boolean abortOnMissing, boolean keepRaw) throws IOException {
        RegistrySnapshot srcSnap = null;
        RegistrySnapshot dstSnap = null;
        if (srcSnapshotPath != null || dstSnapshotPath != null) {
            if (srcSnapshotPath == null || dstSnapshotPath == null) {
                System.out.println("重映射模式需要同时提供 --snapshot-src 与 --snapshot");
                return;
            }
            srcSnap = RegistrySnapshot.load(new File(srcSnapshotPath));
            dstSnap = RegistrySnapshot.load(new File(dstSnapshotPath));
            if (!srcSnap.fingerprint.equals(dstSnap.fingerprint)) {
                System.out.println("[WARN] 源/目标模组集不同 fingerprint: src=" + srcSnap.fingerprint.substring(0, Math.min(8, srcSnap.fingerprint.length()))
                        + "... dst=" + dstSnap.fingerprint.substring(0, Math.min(8, dstSnap.fingerprint.length())) + "...");
            }
        }
        int minCx = Math.floorDiv(Math.min(x1, x2), 16);
        int maxCx = Math.floorDiv(Math.max(x1, x2), 16);
        int minCz = Math.floorDiv(Math.min(z1, z2), 16);
        int maxCz = Math.floorDiv(Math.max(z1, z2), 16);
        int copied = 0;
        long missingTotal = 0;
        for (int cx = minCx; cx <= maxCx; cx++) {
            for (int cz = minCz; cz <= maxCz; cz++) {
                File srcRegion = regionOf(srcWorld, cx, cz);
                if (!srcRegion.isFile()) continue;
                RegionReader rr = new RegionReader(srcRegion);
                int srcIdx = indexOf(cx, cz);
                byte[] payload = rr.readChunkData(srcIdx);
                if (payload == null) continue;
                File dstRegion = regionOf(dstWorld, cx, cz);
                if (!dstRegion.getParentFile().isDirectory()) {
                    System.out.println("目标世界缺少 region 目录: " + dstRegion.getParentFile());
                    return;
                }
                if (srcSnap != null && dstSnap != null) {
                    // 跨模组集：重映射管线
                    NbtNode root = RegionWriter.unpackChunk(payload);
                    RemapEngine.ConflictReport report = new RemapEngine.ConflictReport();
                    RemapEngine.Options options = new RemapEngine.Options();
                    options.abortOnMissing = abortOnMissing;
                    options.keepRawIds = keepRaw;
                    root = RemapEngine.remapChunk(root, srcSnap, dstSnap, options, report);
                    missingTotal += report.missingBlocks.size();
                    if (report.aborted) {
                        System.out.println("chunk (" + cx + "," + cz + ") 中止（缺失方块，--abort）");
                        continue;
                    }
                    if (!report.missingBlocks.isEmpty()) {
                        System.out.println("chunk (" + cx + "," + cz + ") 冲突: " + report.summary());
                    }
                    payload = RegionWriter.packChunk(root);
                }
                RegionWriter rw = new RegionWriter(dstRegion);
                rw.setChunk(srcIdx, payload);
                System.out.println((move ? "move" : "copy") + ": chunk (" + cx + "," + cz + ") " + srcRegion.getName() + " -> " + dstRegion.getName() + (dryRun ? " [DRY-RUN]" : ""));
                if (!dryRun) {
                    File bak = rw.write();
                    if (bak != null) System.out.println("  目标已备份: " + bak.getName());
                }
                if (move && !dryRun) {
                    RegionWriter srcRw = new RegionWriter(srcRegion);
                    srcRw.removeChunk(srcIdx);
                    srcRw.write();
                }
                copied++;
            }
        }
        System.out.println((move ? "move" : "copy") + " 完成: " + copied + " 个 chunk"
                + (srcSnap != null ? "，冲突 chunk 数（含缺失方块）: " + missingTotal : ""));
    }

    // ------------------------------------------------------------ buckets

    /** 按模组分桶统计（P0 验收项）。加载 registry-snapshot.json 名称化后分桶；无快照时输出未知 ID 报告。 */
    static void opBuckets(File world, String snapshotPath) throws IOException {
        RegistrySnapshot snap = null;
        if (snapshotPath != null) {
            snap = RegistrySnapshot.load(new File(snapshotPath));
            String fp = snap.fingerprint;
            if (fp.length() > 12) fp = fp.substring(0, 12) + "...";
            System.out.println("snapshot: " + snapshotPath + " (blocks=" + snap.blocks.size()
                    + ", biomes=" + snap.biomes.size() + ", fingerprint=" + fp + ")");
        }
        File regionDir = new File(world, "region");
        if (!regionDir.isDirectory()) {
            System.out.println("无 region 目录: " + regionDir);
            return;
        }
        java.util.Map<String, Long> modCount = new java.util.TreeMap<String, Long>();
        java.util.Map<Integer, Long> unknownIds = new java.util.TreeMap<Integer, Long>();
        long total = 0;
        int chunks = 0;
        for (File rf : regionDir.listFiles()) {
            if (!rf.getName().toLowerCase().endsWith(".mca")) continue;
            RegionReader rr = new RegionReader(rf);
            for (int idx : rr.listChunkIndices()) {
                byte[] data = rr.readChunkData(idx);
                if (data == null) continue;
                chunks++;
                NbtNode root = RegionWriter.unpackChunk(data);
                NbtNode level = root.get("Level");
                if (level == null) continue;
                for (NbtNode sec : SectionCodec.sectionsOf(level)) {
                    int[] stateIds = SectionCodec.decode(sec);
                    if (stateIds == null) continue;
                    for (int s : stateIds) {
                        total++;
                        int id = s >> 4;
                        String name = snap != null ? snap.lookupBlockName(id) : null;
                        if (name == null) {
                            Long c = unknownIds.get(id);
                            unknownIds.put(id, c == null ? 1 : c + 1);
                            continue;
                        }
                        int colon = name.indexOf(':');
                        String modid = colon > 0 ? name.substring(0, colon) : "?";
                        Long c = modCount.get(modid);
                        modCount.put(modid, c == null ? 1 : c + 1);
                    }
                }
            }
        }
        System.out.println("chunks=" + chunks + ", blocks=" + total);
        List<java.util.Map.Entry<String, Long>> sorted = new ArrayList<java.util.Map.Entry<String, Long>>(modCount.entrySet());
        java.util.Collections.sort(sorted, new java.util.Comparator<java.util.Map.Entry<String, Long>>() {
            public int compare(java.util.Map.Entry<String, Long> a, java.util.Map.Entry<String, Long> b) {
                return b.getValue().compareTo(a.getValue());
            }
        });
        System.out.println("--- 方块按 modid 分桶 (top 20) ---");
        for (int i = 0; i < Math.min(20, sorted.size()); i++) {
            java.util.Map.Entry<String, Long> e = sorted.get(i);
            System.out.println("  " + e.getKey() + ": " + e.getValue()
                    + " (" + String.format("%.2f", 100.0 * e.getValue() / Math.max(1, total)) + "%)");
        }
        long unknownTotal = 0;
        for (long v : unknownIds.values()) unknownTotal += v;
        System.out.println("--- 未知 ID（快照未覆盖，模组集可能不一致）: " + unknownTotal + " 方块 / " + unknownIds.size() + " 种 ---");
        if (!unknownIds.isEmpty()) {
            List<java.util.Map.Entry<Integer, Long>> us = new ArrayList<java.util.Map.Entry<Integer, Long>>(unknownIds.entrySet());
            java.util.Collections.sort(us, new java.util.Comparator<java.util.Map.Entry<Integer, Long>>() {
                public int compare(java.util.Map.Entry<Integer, Long> a, java.util.Map.Entry<Integer, Long> b) {
                    return b.getValue().compareTo(a.getValue());
                }
            });
            for (int i = 0; i < Math.min(8, us.size()); i++) {
                java.util.Map.Entry<Integer, Long> e = us.get(i);
                System.out.println("  unknown id=" + e.getKey() + " x" + e.getValue());
            }
        } else {
            System.out.println("  (无未知 ID：快照完整覆盖本存档)");
        }
    }

    // ------------------------------------------------------------ mcops

    /** 导出选区为 .mcops 名称化剪贴板文件。 */
    static void opMcopsExport(File world, int x1, int z1, int x2, int z2, File outFile, String snapshotPath) throws IOException {
        if (snapshotPath == null) {
            System.out.println("mcops-export 需要 --snapshot <registry-snapshot.json>（名称化用）");
            return;
        }
        RegistrySnapshot snap = RegistrySnapshot.load(new File(snapshotPath));
        int minCx = Math.floorDiv(Math.min(x1, x2), 16);
        int maxCx = Math.floorDiv(Math.max(x1, x2), 16);
        int minCz = Math.floorDiv(Math.min(z1, z2), 16);
        int maxCz = Math.floorDiv(Math.max(z1, z2), 16);
        List<NbtNode> chunks = new ArrayList<NbtNode>();
        for (int cx = minCx; cx <= maxCx; cx++) {
            for (int cz = minCz; cz <= maxCz; cz++) {
                File regionFile = regionOf(world, cx, cz);
                if (!regionFile.isFile()) continue;
                RegionReader rr = new RegionReader(regionFile);
                byte[] payload = rr.readChunkData(indexOf(cx, cz));
                if (payload == null) continue;
                chunks.add(RegionWriter.unpackChunk(payload));
            }
        }
        byte[] data = Mcops.exportChunks(chunks, snap, snap.fingerprint, "ChunkOps112 export " + world.getName());
        if (outFile.getParentFile() != null) outFile.getParentFile().mkdirs();
        java.nio.file.Files.write(outFile.toPath(), data);
        System.out.println("mcops-export: " + chunks.size() + " chunks -> " + outFile.getAbsolutePath()
                + " (" + data.length + " bytes)");
    }

    /** 导入 .mcops 到目标世界（经目标快照重映射）。 */
    static void opMcopsImport(File world, File mcopsFile, String snapshotPath, boolean dryRun) throws IOException {
        if (snapshotPath == null) {
            System.out.println("mcops-import 需要 --snapshot <registry-snapshot.json>（重映射用）");
            return;
        }
        RegistrySnapshot snap = RegistrySnapshot.load(new File(snapshotPath));
        byte[] data = java.nio.file.Files.readAllBytes(mcopsFile.toPath());
        Mcops.ImportReport report = new Mcops.ImportReport();
        List<NbtNode> chunks = Mcops.importChunks(data, snap, report);
        System.out.println("mcops-import: " + mcopsFile.getName() + " -> " + world.getName() + " | " + report.summary());
        for (NbtNode chunkRoot : chunks) {
            NbtNode level = chunkRoot.get("Level");
            int cx = 0, cz = 0;
            if (level != null) {
                NbtNode xp = level.get("xPos");
                NbtNode zp = level.get("zPos");
                if (xp != null) cx = ((Number) xp.value).intValue();
                if (zp != null) cz = ((Number) zp.value).intValue();
            }
            File regionFile = regionOf(world, cx, cz);
            if (!regionFile.getParentFile().isDirectory()) {
                System.out.println("目标世界缺少 region 目录: " + regionFile.getParentFile());
                return;
            }
            System.out.println("  -> chunk (" + cx + "," + cz + ") " + regionFile.getName() + (dryRun ? " [DRY-RUN]" : ""));
            if (!dryRun) {
                RegionWriter rw = new RegionWriter(regionFile);
                rw.setChunk(indexOf(cx, cz), RegionWriter.packChunk(chunkRoot));
                File bak = rw.write();
                if (bak != null) System.out.println("    备份: " + bak.getName());
            }
        }
        if (!report.missingPalette.isEmpty()) {
            System.out.println("缺失方块（已回退 air）: " + report.missingPalette);
        }
    }

    // ------------------------------------------------------------ helpers

    static File regionOf(File world, int cx, int cz) {
        int rx = Math.floorDiv(cx, 32);
        int rz = Math.floorDiv(cz, 32);
        return new File(world, "region" + File.separator + "r." + rx + "." + rz + ".mca");
    }

    static int indexOf(int cx, int cz) {
        return (cz & 31) * 32 + (cx & 31);
    }
}
