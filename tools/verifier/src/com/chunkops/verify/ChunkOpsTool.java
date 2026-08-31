package com.chunkops.verify;

import com.chunkops.core.RegionWriter;

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
        for (String s : args) {
            if (s.equals("--dry-run")) dryRun = true;
            else if (s.equals("--move")) move = true;
            else a.add(s);
        }
        String cmd = a.get(0);
        if (cmd.equals("stats")) {
            ChunkInspector.scan(new File(a.get(1)), Integer.MAX_VALUE);
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
            opCopyMove(src, dst, x1, z1, x2, z2, move, dryRun);
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

    static void opCopyMove(File srcWorld, File dstWorld, int x1, int z1, int x2, int z2, boolean move, boolean dryRun) throws IOException {
        int minCx = Math.floorDiv(Math.min(x1, x2), 16);
        int maxCx = Math.floorDiv(Math.max(x1, x2), 16);
        int minCz = Math.floorDiv(Math.min(z1, z2), 16);
        int maxCz = Math.floorDiv(Math.max(z1, z2), 16);
        int copied = 0;
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
        System.out.println((move ? "move" : "copy") + " 完成: " + copied + " 个 chunk");
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
