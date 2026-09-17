package com.chunkops.core;

import com.chunkops.verify.NbtNode;
import com.chunkops.verify.RegionReader;

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * 注册表编号修复（阶段 B）：用「旧快照 + 当前快照」的漂移表，把存档里的编号改写为当前会话的编号。
 *
 * 为什么能修：区块里的方块本体是「palette 索引」，真正表示方块的是 palette 数组里的 stateId；
 * 所以 **JEID 世界只需要原地改写 Palette int[] 的每一项**，Blocks/Data 索引与光照都不用碰——
 * 一个区块只有几十到几百个 palette 项，几万区块也就几秒。legacy 世界（无 Palette）才需要
 * 解出 4096 项、重映射、整段重建。
 *
 * 安全措施：默认 dry-run（只统计不写）；apply 时每个 region 写盘前由 RegionWriter 自动生成
 * .mcabackup 备份，写后回读校验（重新解析每个已写区块，Level 缺失则计入失败）。
 */
public final class RegistryRepair {

    /** 修复报告。 */
    public static class Report {
        public boolean applied;
        public int regionFiles;
        public int chunksSeen;
        public int chunksChanged;
        public int chunksWritten;
        public long paletteEntries;
        public long paletteRemapped;
        public long biomeEntries;
        public long biomeRemapped;
        public int legacySections;
        public long unmappablePalette;
        public long unmappableBiomes;
        public int skippedRegions;
        public int verifyFailed;
        public long elapsedMs;
        public final Map<String, Long> unmappableNames = new TreeMap<String, Long>();
        public final List<String> notes = new ArrayList<String>();
        /** 本次真正写过的 region 文件（撤销修复时按这些文件回滚）。 */
        public final List<File> writtenRegions = new ArrayList<File>();

        public void addUnmappable(String name) {
            Long c = unmappableNames.get(name);
            unmappableNames.put(name, c == null ? 1 : c + 1);
        }
    }

    private RegistryRepair() {
    }

    /**
     * 扫描（必要时修复）一个维度目录（含 region/）。
     *
     * @param dimDir 维度目录（主世界=存档根，其它=DIM&lt;id&gt;）
     * @param drift  漂移表（{@link RegistryDrift#build}）
     * @param apply  false=dry-run 只统计；true=写盘（自动备份 + 回读校验）
     */
    public static Report repair(File dimDir, RegistryDrift drift, boolean apply) {
        Report report = new Report();
        report.applied = apply;
        long t0 = System.currentTimeMillis();
        try {
            if (dimDir == null) {
                report.notes.add("维度目录为空");
                return report;
            }
            File regionDir = new File(dimDir, "region");
            if (!regionDir.isDirectory()) {
                report.notes.add("没有 region 目录: " + regionDir);
                return report;
            }
            if (drift == null || (drift.isEmpty() && !drift.hasMissing())) {
                report.notes.add("编号一致，无需修复");
                return report;
            }
            File[] regions = regionDir.listFiles();
            if (regions == null) {
                report.notes.add("无法列出 region 目录");
                return report;
            }
            java.util.Arrays.sort(regions);
            for (File region : regions) {
                if (!region.isFile() || !region.getName().endsWith(".mca")) continue;
                report.regionFiles++;
                try {
                    repairRegion(region, drift, apply, report);
                } catch (Exception e) {
                    report.skippedRegions++;
                    report.notes.add("跳过 " + region.getName() + ": " + e.getMessage());
                }
            }
        } finally {
            report.elapsedMs = System.currentTimeMillis() - t0;
        }
        return report;
    }

    /** 单个 region：读 → 重映射 → （apply 时）写 + 回读校验。 */
    private static void repairRegion(File region, RegistryDrift drift, boolean apply, Report report)
            throws IOException {
        List<Integer> changedIndex = new ArrayList<Integer>();
        List<byte[]> changedPayload = new ArrayList<byte[]>();
        RandomAccessFile raf = new RandomAccessFile(region, "r");
        try {
            long len = raf.length();
            byte[] header = new byte[RegionReader.SECTOR_BYTES];
            raf.readFully(header);
            for (int idx = 0; idx < RegionReader.CHUNKS_PER_REGION; idx++) {
                int loc = readInt(header, idx * 4);
                if (loc == 0) continue;
                int sectorOffset = (loc >>> 8) * RegionReader.SECTOR_BYTES;
                int sectorCount = loc & 0xFF;
                if (sectorCount <= 0 || (long) sectorOffset + (long) sectorCount * RegionReader.SECTOR_BYTES > len) {
                    continue;
                }
                raf.seek(sectorOffset);
                byte[] head = new byte[5];
                raf.readFully(head);
                int dataLen = ((head[0] & 0xFF) << 24) | ((head[1] & 0xFF) << 16)
                        | ((head[2] & 0xFF) << 8) | (head[3] & 0xFF);
                int total = 4 + dataLen;
                if (total < 5 || total > sectorCount * RegionReader.SECTOR_BYTES) continue;
                byte[] payload = new byte[total];
                System.arraycopy(head, 0, payload, 0, 5);
                raf.readFully(payload, 5, total - 5);

                report.chunksSeen++;
                NbtNode root;
                try {
                    root = RegionWriter.unpackChunk(payload);
                } catch (Exception e) {
                    report.notes.add(region.getName() + "#" + idx + " 解析失败，已跳过");
                    continue;
                }
                NbtNode level = root == null ? null : root.get("Level");
                if (level == null) continue;
                if (remapChunk(level, drift, report)) {
                    report.chunksChanged++;
                    if (apply) {
                        changedIndex.add(Integer.valueOf(idx));
                        changedPayload.add(RegionWriter.packChunk(root));
                    }
                }
            }
        } finally {
            raf.close();
        }

        if (!apply || changedIndex.isEmpty()) return;

        RegionWriter writer = new RegionWriter(region);
        for (int i = 0; i < changedIndex.size(); i++) {
            writer.setChunk(changedIndex.get(i).intValue(), changedPayload.get(i));
        }
        writer.write(); // 自动 .mcabackup 备份 + 原子写
        report.chunksWritten += changedIndex.size();
        report.writtenRegions.add(region);

        // 回读校验：写进去的区块必须还能解析出 Level
        RegionReader rr = new RegionReader(region);
        for (int i = 0; i < changedIndex.size(); i++) {
            int idx = changedIndex.get(i).intValue();
            try {
                byte[] back = rr.readChunkData(idx);
                NbtNode root = back == null ? null : RegionWriter.unpackChunk(back);
                if (root == null || root.get("Level") == null) report.verifyFailed++;
            } catch (Exception e) {
                report.verifyFailed++;
            }
        }
    }

    /**
     * 重映射单个 chunk 的 Level（就地修改）。
     * @return 是否有改动
     */
    static boolean remapChunk(NbtNode level, RegistryDrift drift, Report report) {
        boolean dirty = false;

        // ---- sections ----
        for (NbtNode sec : SectionCodec.sectionsOf(level)) {
            NbtNode pal = sec.get("Palette");
            if (pal != null && pal.type == NbtNode.TAG_INT_ARRAY) {
                // JEID：palette 项就是 stateId，原地改写即可（Blocks/Data 是索引，不动）
                int[] vals = (int[]) pal.value;
                for (int k = 0; k < vals.length; k++) {
                    int old = vals[k];
                    if (old == 0) continue; // stateId 0 = 空气，恒定不参与重映射
                    report.paletteEntries++;
                    Integer nv = drift.stateMap.get(Integer.valueOf(old));
                    if (nv != null) {
                        vals[k] = nv.intValue();
                        report.paletteRemapped++;
                        dirty = true;
                    } else {
                        String missing = drift.missingStates.get(Integer.valueOf(old));
                        if (missing != null) {
                            report.unmappablePalette++;
                            report.addUnmappable(missing);
                        }
                    }
                }
                continue;
            }
            // legacy / 1.13 palette：解出 4096 项 → 重映射 → 重建（保留 Y 与光照）
            int[] ids = SectionCodec.decode(sec);
            if (ids == null) continue;
            boolean secDirty = false;
            for (int i = 0; i < ids.length; i++) {
                if (ids[i] == 0) continue;
                Integer nv = drift.stateMap.get(Integer.valueOf(ids[i]));
                if (nv != null) {
                    ids[i] = nv.intValue();
                    secDirty = true;
                } else {
                    String missing = drift.missingStates.get(Integer.valueOf(ids[i]));
                    if (missing != null) {
                        report.unmappablePalette++;
                        report.addUnmappable(missing);
                    }
                }
            }
            if (secDirty) {
                NbtNode newSec = SectionCodec.encodeSection(ids, false);
                NbtNode y = sec.get("Y");
                if (y != null) newSec.asMap().put("Y", y);
                for (String light : new String[]{"BlockLight", "SkyLight"}) {
                    NbtNode l = sec.get(light);
                    if (l != null) newSec.asMap().put(light, l);
                }
                List<NbtNode> list = level.get("Sections").asList();
                list.set(list.indexOf(sec), newSec);
                report.legacySections++;
                dirty = true;
            }
        }

        // ---- biomes ----
        NbtNode biomesNode = level.get("Biomes");
        if (biomesNode != null) {
            if (biomesNode.type == NbtNode.TAG_INT_ARRAY) {
                int[] arr = (int[]) biomesNode.value;
                boolean changed = false;
                for (int i = 0; i < arr.length; i++) {
                    report.biomeEntries++;
                    Integer nv = drift.biomeMap.get(Integer.valueOf(arr[i]));
                    if (nv != null) {
                        arr[i] = nv.intValue();
                        report.biomeRemapped++;
                        changed = true;
                    } else if (drift.missingBiomes.containsKey(Integer.valueOf(arr[i]))) {
                        report.unmappableBiomes++;
                    }
                }
                if (changed) dirty = true;
            } else if (biomesNode.type == NbtNode.TAG_BYTE_ARRAY) {
                byte[] arr = (byte[]) biomesNode.value;
                int[] vals = new int[arr.length];
                boolean changed = false;
                boolean needInt = false;
                for (int i = 0; i < arr.length; i++) {
                    int old = arr[i] & 0xFF;
                    vals[i] = old;
                    report.biomeEntries++;
                    Integer nv = drift.biomeMap.get(Integer.valueOf(old));
                    if (nv != null) {
                        vals[i] = nv.intValue();
                        report.biomeRemapped++;
                        changed = true;
                        if (nv.intValue() > 255) needInt = true;
                    } else if (drift.missingBiomes.containsKey(Integer.valueOf(old))) {
                        report.unmappableBiomes++;
                    }
                }
                if (changed) {
                    if (needInt) {
                        level.asMap().put("Biomes", NbtNode.intArrayNode(vals));
                    } else {
                        byte[] out = new byte[vals.length];
                        for (int i = 0; i < vals.length; i++) out[i] = (byte) vals[i];
                        level.asMap().put("Biomes", NbtNode.byteArrayNode(out));
                    }
                    dirty = true;
                }
            }
        }
        return dirty;
    }

    private static int readInt(byte[] b, int off) {
        return ((b[off] & 0xFF) << 24) | ((b[off + 1] & 0xFF) << 16)
                | ((b[off + 2] & 0xFF) << 8) | (b[off + 3] & 0xFF);
    }
}
