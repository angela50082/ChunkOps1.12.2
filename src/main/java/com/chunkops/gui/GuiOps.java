package com.chunkops.gui;

import com.chunkops.core.RegionWriter;
import com.chunkops.core.SectionCodec;
import com.chunkops.verify.ChunkOpsTool;
import com.chunkops.verify.NbtNode;
import com.chunkops.verify.RegionReader;
import net.minecraft.block.Block;
import net.minecraft.util.ResourceLocation;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * 编辑器操作封装（阶段 3）：GUI 调用 chunkops-core 的区块操作，
 * 含 session.lock 检测与活注册表统计（游戏内无需快照即可名称化）。
 */
public class GuiOps {

    /** 存档是否被占用（session.lock 存在）。注意：游戏关闭后可能残留。 */
    public static boolean hasSessionLock(File worldDir) {
        return new File(worldDir, "session.lock").isFile();
    }

    public static String removeChunk(File worldDir, int cx, int cz, boolean dryRun) {
        try {
            ChunkOpsTool.opRemove(worldDir, cx, cz, dryRun);
            return "移除区块 (" + cx + "," + cz + ")" + (dryRun ? " [干跑]" : "");
        } catch (Exception e) {
            return "移除失败: " + e.getMessage();
        }
    }

    public static String clearChunk(File worldDir, int cx, int cz, boolean dryRun) {
        try {
            ChunkOpsTool.opClear(worldDir, cx, cz, dryRun);
            return "清空区块 (" + cx + "," + cz + ")" + (dryRun ? " [干跑]" : "");
        } catch (Exception e) {
            return "清空失败: " + e.getMessage();
        }
    }

    /** 剪裁：删除选区外的全部区块。 */
    public static String trimArea(File worldDir, int minCx, int maxCx, int minCz, int maxCz, boolean dryRun) {
        try {
            ChunkOpsTool.opTrim(worldDir, minCx * 16, minCz * 16, maxCx * 16 + 15, maxCz * 16 + 15, dryRun);
            return "剪裁完成（保留 [" + minCx + ".." + maxCx + ", " + minCz + ".." + maxCz + "]）" + (dryRun ? " [干跑]" : "");
        } catch (Exception e) {
            return "剪裁失败: " + e.getMessage();
        }
    }

    /**
     * 复制选区：读取 chunk 原始 payload 到内存剪贴板（同模组集原样）。
     */
    public static java.util.Map<Long, byte[]> copyArea(File worldDir, int minCx, int maxCx, int minCz, int maxCz) {
        java.util.Map<Long, byte[]> clipboard = new java.util.HashMap<Long, byte[]>();
        try {
            for (int cx = minCx; cx <= maxCx; cx++) {
                for (int cz = minCz; cz <= maxCz; cz++) {
                    int rx = Math.floorDiv(cx, 32);
                    int rz = Math.floorDiv(cz, 32);
                    File region = new File(new File(worldDir, "region"), "r." + rx + "." + rz + ".mca");
                    if (!region.isFile()) continue;
                    RegionReader rr = new RegionReader(region);
                    byte[] payload = rr.readChunkData((cz & 31) * 32 + (cx & 31));
                    if (payload != null) {
                        clipboard.put(((long) cx << 32) | (cz & 0xFFFFFFFFL), payload);
                    }
                }
            }
        } catch (Exception e) {
            return null;
        }
        return clipboard;
    }

    /**
     * 粘贴：把剪贴板 chunk 写到「以 (targetCx,targetCz) 为左上角的目标区域」
     * （剪贴板内容按源选区 origin 偏移平移），同模组集原样写入，自动建目录/备份。
     * 返回日志文本。
     */
    public static String pasteArea(File worldDir, java.util.Map<Long, byte[]> clipboard,
                                   int originCx, int originCz, int targetCx, int targetCz) {
        if (clipboard == null || clipboard.isEmpty()) return "剪贴板为空";
        int n = 0;
        int errors = 0;
        // 目标存在则记录（日志用）
        for (java.util.Map.Entry<Long, byte[]> e : clipboard.entrySet()) {
            long key = e.getKey();
            int srcCx = (int) (key >> 32);
            int srcCz = (int) (key & 0xFFFFFFFFL);
            int dx = srcCx - originCx;
            int dz = srcCz - originCz;
            int cx = targetCx + dx;
            int cz = targetCz + dz;
            try {
                int rx = Math.floorDiv(cx, 32);
                int rz = Math.floorDiv(cz, 32);
                File region = new File(new File(worldDir, "region"), "r." + rx + "." + rz + ".mca");
                File parent = region.getParentFile();
                if (!parent.isDirectory() && !parent.mkdirs()) return "目标缺少 region 目录";
                com.chunkops.core.RegionWriter rw = new com.chunkops.core.RegionWriter(region);
                rw.setChunk((cz & 31) * 32 + (cx & 31), e.getValue());
                rw.write();
                n++;
            } catch (Exception ex) {
                errors++;
                if (errors <= 3) {
                    logLastError = "粘贴失败 (" + cx + "," + cz + "): " + ex.getMessage();
                }
            }
        }
        if (errors > 0) return "粘贴完成: " + n + " 个区块（含 " + errors + " 个失败: " + logLastError + "）";
        return "粘贴完成: " + n + " 个区块 → 目标 (" + targetCx + "," + targetCz + ")";
    }

    private static String logLastError = "";

    // ------------------------------------------------------------ stats

    /**
     * 选区统计（结构版，供 UI 列表）：方块名级计数。
     * 返回 Object[]{Integer chunks, Long total, Integer unknownTypes, Long unknownCount, List<String[]> entries}
     * entries[i] = {方块名, 数量, 百分比字符串}（按数量降序，最多 500 条）。
     */
    public static Object[] statsAreaList(File worldDir, int minCx, int maxCx, int minCz, int maxCz) {
        try {
            Map<String, Long> blockCount = new TreeMap<String, Long>();
            long total = 0;
            long unknownTotal = 0;
            int unknownTypes = 0;
            int chunks = 0;
            for (int cx = minCx; cx <= maxCx; cx++) {
                for (int cz = minCz; cz <= maxCz; cz++) {
                    int rx = Math.floorDiv(cx, 32);
                    int rz = Math.floorDiv(cz, 32);
                    File region = new File(new File(worldDir, "region"), "r." + rx + "." + rz + ".mca");
                    if (!region.isFile()) continue;
                    RegionReader rr = new RegionReader(region);
                    byte[] payload = rr.readChunkData((cz & 31) * 32 + (cx & 31));
                    if (payload == null) continue;
                    chunks++;
                    NbtNode root = RegionWriter.unpackChunk(payload);
                    NbtNode level = root.get("Level");
                    if (level == null) continue;
                    for (NbtNode sec : SectionCodec.sectionsOf(level)) {
                        int[] stateIds = SectionCodec.decode(sec);
                        if (stateIds == null) continue;
                        for (int s : stateIds) {
                            total++;
                            if (s == 0) continue;
                            int id = s >> 4;
                            ResourceLocation rl = Block.REGISTRY.getNameForObject(Block.getBlockById(id));
                            if (rl == null) {
                                unknownTotal++;
                                continue;
                            }
                            String name = rl.toString();
                            Long c = blockCount.get(name);
                            blockCount.put(name, c == null ? 1 : c + 1);
                        }
                    }
                }
            }
            unknownTypes = 0; // 未知 id 类型数不在此统计（见文本版）
            List<String[]> sorted = new ArrayList<String[]>();
            List<Map.Entry<String, Long>> list = new ArrayList<Map.Entry<String, Long>>(blockCount.entrySet());
            Collections.sort(list, new Comparator<Map.Entry<String, Long>>() {
                public int compare(Map.Entry<String, Long> a, Map.Entry<String, Long> b) {
                    return b.getValue().compareTo(a.getValue());
                }
            });
            for (int i = 0; i < Math.min(500, list.size()); i++) {
                Map.Entry<String, Long> e = list.get(i);
                sorted.add(new String[]{e.getKey(), String.valueOf(e.getValue()),
                        String.format("%.2f%%", 100.0 * e.getValue() / Math.max(1, total))});
            }
            return new Object[]{Integer.valueOf(chunks), Long.valueOf(total),
                    Integer.valueOf(unknownTypes), Long.valueOf(unknownTotal), sorted};
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * 选区统计：解码方块 → 活注册表名称化 → 按 modid 分桶。
     * 返回多行文本（总数 + top 12 modid + 未知 id 数）。
     */
    public static String statsArea(File worldDir, int minCx, int maxCx, int minCz, int maxCz) {
        try {
            Map<String, Long> modCount = new TreeMap<String, Long>();
            Map<Integer, Long> unknownIds = new TreeMap<Integer, Long>();
            long total = 0;
            int chunks = 0;
            for (int cx = minCx; cx <= maxCx; cx++) {
                for (int cz = minCz; cz <= maxCz; cz++) {
                    int rx = Math.floorDiv(cx, 32);
                    int rz = Math.floorDiv(cz, 32);
                    File region = new File(new File(worldDir, "region"), "r." + rx + "." + rz + ".mca");
                    if (!region.isFile()) continue;
                    RegionReader rr = new RegionReader(region);
                    byte[] payload = rr.readChunkData((cz & 31) * 32 + (cx & 31));
                    if (payload == null) continue;
                    chunks++;
                    NbtNode root = RegionWriter.unpackChunk(payload);
                    NbtNode level = root.get("Level");
                    if (level == null) continue;
                    for (NbtNode sec : SectionCodec.sectionsOf(level)) {
                        int[] stateIds = SectionCodec.decode(sec);
                        if (stateIds == null) continue;
                        for (int s : stateIds) {
                            total++;
                            if (s == 0) continue;
                            int id = s >> 4;
                            ResourceLocation rl = Block.REGISTRY.getNameForObject(Block.getBlockById(id));
                            if (rl == null) {
                                Long c = unknownIds.get(id);
                                unknownIds.put(id, c == null ? 1 : c + 1);
                                continue;
                            }
                            String name = rl.toString();
                            int colon = name.indexOf(':');
                            String modid = colon > 0 ? name.substring(0, colon) : name;
                            Long c = modCount.get(modid);
                            modCount.put(modid, c == null ? 1 : c + 1);
                        }
                    }
                }
            }
            List<Map.Entry<String, Long>> sorted = new ArrayList<Map.Entry<String, Long>>(modCount.entrySet());
            Collections.sort(sorted, new Comparator<Map.Entry<String, Long>>() {
                public int compare(Map.Entry<String, Long> a, Map.Entry<String, Long> b) {
                    return b.getValue().compareTo(a.getValue());
                }
            });
            long unknownTotal = 0;
            for (long v : unknownIds.values()) unknownTotal += v;
            StringBuilder sb = new StringBuilder();
            sb.append("统计: ").append(chunks).append(" 区块 / ").append(total).append(" 方块");
            for (int i = 0; i < Math.min(12, sorted.size()); i++) {
                Map.Entry<String, Long> e = sorted.get(i);
                sb.append("\n  ").append(e.getKey()).append(": ").append(e.getValue())
                        .append(String.format(" (%.2f%%)", 100.0 * e.getValue() / Math.max(1, total)));
            }
            sb.append("\n  未知 id: ").append(unknownTotal).append(" 方块 / ").append(unknownIds.size()).append(" 种");
            return sb.toString();
        } catch (Exception e) {
            return "统计失败: " + e.getMessage();
        }
    }
}
