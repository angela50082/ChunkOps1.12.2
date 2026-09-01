package com.chunkops.core;

import com.chunkops.verify.NbtNode;
import com.chunkops.verify.NbtReader;

import java.util.ArrayList;
import java.util.List;

/**
 * Section（Y 层）编解码器：legacy 数组（Blocks/Add/Data）为主，palette 兼容。
 *
 * 统一中间表示：int[4096] stateId（stateId = blockId << 4 | meta，与 1.12 语义一致）。
 * 索引顺序：y*256 + z*16 + x。
 * 半字节顺序（NibbleArray 语义）：x 偶 → 低半字节，x 奇 → 高半字节。
 */
public class SectionCodec {

    public static final int BLOCKS_PER_SECTION = 4096;

    // ------------------------------------------------------------ decode

    /** 解码一个 section NBT 为 stateId 数组；无法解码时返回 null（并说明原因）。 */
    public static int[] decode(NbtNode section) {
        NbtNode blocksNode = section.get("Blocks");
        NbtNode palette = section.get("Palette");
        if (blocksNode != null && blocksNode.type == NbtNode.TAG_BYTE_ARRAY) {
            // JEID：Blocks = palette 索引 byte[4096]，Palette = int[] stateId（支持 id>4095，实测）
            if (palette != null && palette.type == NbtNode.TAG_INT_ARRAY) {
                return decodeJeidIndex(section, (byte[]) blocksNode.value, (int[]) palette.value);
            }
            return decodeLegacy(section, (byte[]) blocksNode.value);
        }
        NbtNode blockStates = section.get("BlockStates");
        if (palette != null && blockStates != null
                && palette.type == NbtNode.TAG_LIST && blockStates.type == NbtNode.TAG_LONG_ARRAY) {
            return decodePalette(palette, (long[]) blockStates.value);
        }
        return null; // 空 section 或无数据
    }

    /**
     * JEID 索引数组格式（GreedyCraft 实测，2026-09-01）：
     * Blocks byte[4096] = Palette int[] 的索引；Palette 项 = stateId（id<<4|meta，id 可 >4095）；
     * Data nibble 为兼容字段（palette 已含全状态，忽略）。
     */
    static int[] decodeJeidIndex(NbtNode section, byte[] indices, int[] palValues) {
        int[] stateIds = new int[BLOCKS_PER_SECTION];
        for (int i = 0; i < BLOCKS_PER_SECTION; i++) {
            int idx = indices[i] & 0xFF;
            stateIds[i] = idx >= 0 && idx < palValues.length ? palValues[idx] : 0;
        }
        return stateIds;
    }

    static int[] decodeLegacy(NbtNode section, byte[] blocks) {
        byte[] data = section.get("Data") != null ? (byte[]) section.get("Data").value : null;
        byte[] add = section.get("Add") != null ? (byte[]) section.get("Add").value : null;
        int[] stateIds = new int[BLOCKS_PER_SECTION];
        for (int i = 0; i < BLOCKS_PER_SECTION; i++) {
            int id = blocks[i] & 0xFF;
            if (add != null) {
                int nib = (i & 1) == 0 ? (add[i >> 1] & 0xF) : ((add[i >> 1] >> 4) & 0xF);
                id |= nib << 8;
            }
            int meta = data != null ? nibble(data[i >> 1], (i & 1) == 0) : 0;
            stateIds[i] = id << 4 | meta;
        }
        return stateIds;
    }

    static int[] decodePalette(NbtNode palette, long[] blockStates) {
        List<NbtNode> pal = palette.asList();
        int bits = bitsFor(pal.size());
        int[] stateIds = new int[BLOCKS_PER_SECTION];
        for (int i = 0; i < BLOCKS_PER_SECTION; i++) {
            int idx = (int) getPacked(blockStates, i, bits);
            if (idx >= 0 && idx < pal.size()) {
                stateIds[i] = ((Number) pal.get(idx).value).intValue();
            }
        }
        return stateIds;
    }

    // ------------------------------------------------------------ encode

    /**
     * 按世界格式编码 section：jeid=true → JEID palette（Blocks=索引+Palette int[]，必须的——JEID 的
     * reid mixin 读取时把 Blocks 一律按 palette 索引解释，无 legacy 分支，写 legacy 会越界崩溃，实测）；
     * jeid=false → legacy（id>4095 自动转 palette）。
     */
    public static NbtNode encodeSection(int[] stateIds, boolean jeid) {
        if (jeid) return encodePalette(stateIds);
        return encodeLegacy(stateIds);
    }

    /** 由 stateId 数组编码为 section：存在 id>0xFFF 方块时用 palette（JEID 兼容），否则 legacy 数组。 */
    public static NbtNode encodeLegacy(int[] stateIds) {
        for (int s : stateIds) {
            if ((s >> 4) > 0xFFF) return encodePalette(stateIds);
        }
        byte[] blocks = new byte[BLOCKS_PER_SECTION];
        byte[] data = new byte[BLOCKS_PER_SECTION / 2];
        byte[] add = null;
        boolean needAdd = false;
        for (int i = 0; i < BLOCKS_PER_SECTION; i++) {
            int stateId = stateIds[i];
            int id = stateId >> 4;
            int meta = stateId & 15;
            if (id > 255) needAdd = true;
        }
        if (needAdd) add = new byte[BLOCKS_PER_SECTION / 2];
        for (int i = 0; i < BLOCKS_PER_SECTION; i++) {
            int stateId = stateIds[i];
            int id = stateId >> 4;
            int meta = stateId & 15;
            blocks[i] = (byte) (id & 0xFF);
            int half = i >> 1;
            int nib = meta & 0xF;
            if ((i & 1) == 0) {
                data[half] = (byte) ((data[half] & 0xF0) | nib);
            } else {
                data[half] = (byte) ((data[half] & 0x0F) | (nib << 4));
            }
            if (add != null) {
                int hi = (id >> 8) & 0xF;
                if ((i & 1) == 0) {
                    add[half] = (byte) ((add[half] & 0xF0) | hi);
                } else {
                    add[half] = (byte) ((add[half] & 0x0F) | (hi << 4));
                }
            }
        }
        NbtNode sec = NbtNode.compound();
        sec.asMap().put("Blocks", NbtNode.byteArrayNode(blocks));
        sec.asMap().put("Data", NbtNode.byteArrayNode(data));
        if (add != null) sec.asMap().put("Add", NbtNode.byteArrayNode(add));
        // 光照默认填充（游戏加载必需：NibbleArray 0 字节会崩——实测）；调用方可覆盖
        byte[] sky = new byte[BLOCKS_PER_SECTION / 2];
        java.util.Arrays.fill(sky, (byte) 0xFF);
        sec.asMap().put("SkyLight", NbtNode.byteArrayNode(sky));
        sec.asMap().put("BlockLight", NbtNode.byteArrayNode(new byte[BLOCKS_PER_SECTION / 2]));
        return sec;
    }

    /** 全空气判断。 */
    public static boolean isAllAir(int[] stateIds) {
        for (int s : stateIds) {
            if (s != 0) return false;
        }
        return true;
    }

    /**
     * palette 编码（JEID 索引数组格式，decodeJeidIndex 的逆操作）：
     * Palette int[]（stateId，支持 >4095）+ Blocks byte[4096]（索引）+ Data nibble（meta 冗余）。
     * 注意：非 1.13 blockStates 格式（JEID 实测定案）。
     */
    public static NbtNode encodePalette(int[] stateIds) {
        java.util.Map<Integer, Integer> stateToIdx = new java.util.HashMap<Integer, Integer>();
        java.util.List<Integer> pal = new java.util.ArrayList<Integer>();
        byte[] idxs = new byte[stateIds.length];
        for (int i = 0; i < stateIds.length; i++) {
            Integer idx = stateToIdx.get(stateIds[i]);
            if (idx == null) {
                idx = Integer.valueOf(pal.size());
                stateToIdx.put(stateIds[i], idx);
                pal.add(Integer.valueOf(stateIds[i]));
            }
            idxs[i] = (byte) idx.intValue();
        }
        int[] palVals = new int[pal.size()];
        for (int i = 0; i < pal.size(); i++) palVals[i] = pal.get(i).intValue();
        byte[] data = new byte[BLOCKS_PER_SECTION / 2];
        for (int i = 0; i < stateIds.length; i++) {
            int meta = stateIds[i] & 15;
            int half = i >> 1;
            if ((i & 1) == 0) data[half] = (byte) ((data[half] & 0xF0) | meta);
            else data[half] = (byte) ((data[half] & 0x0F) | (meta << 4));
        }
        NbtNode sec = NbtNode.compound();
        sec.asMap().put("Palette", NbtNode.intArrayNode(palVals));
        sec.asMap().put("Blocks", NbtNode.byteArrayNode(idxs));
        sec.asMap().put("Data", NbtNode.byteArrayNode(data));
        return sec;
    }

    // ------------------------------------------------------------ helpers

    public static int bitsFor(int paletteSize) {
        int bits = 4;
        while ((1L << bits) < paletteSize) bits++;
        return bits;
    }

    public static long getPacked(long[] arr, int index, int bits) {
        int bitIndex = index * bits;
        int longIndex = bitIndex >>> 6;
        int offset = bitIndex & 63;
        long value;
        if (offset + bits <= 64) {
            value = arr[longIndex] >>> offset;
        } else {
            value = (arr[longIndex] >>> offset) | (arr[longIndex + 1] << (64 - offset));
        }
        long mask = bits == 64 ? -1L : (1L << bits) - 1;
        return value & mask;
    }

    /** 写入打包索引（getPacked 的逆操作，跨 long 边界兼容）。 */
    public static void setPacked(long[] arr, int index, int bits, int value) {
        int bitIndex = index * bits;
        int longIndex = bitIndex >>> 6;
        int offset = bitIndex & 63;
        long v = value & (bits == 64 ? -1L : (1L << bits) - 1);
        if (offset + bits <= 64) {
            arr[longIndex] |= v << offset;
        } else {
            arr[longIndex] |= v << offset;
            arr[longIndex + 1] |= v >>> (64 - offset);
        }
    }

    public static int nibble(byte b, boolean evenIndex) {
        return evenIndex ? (b & 0xF) : ((b >> 4) & 0xF);
    }

    /** 解析 section 的 Y 值（无 Y 时返回 -1）。 */
    public static int sectionY(NbtNode section) {
        NbtNode y = section.get("Y");
        return y != null ? ((Number) y.value).intValue() : -1;
    }

    /** 读取区块的所有 section（供操作层使用）。 */
    public static List<NbtNode> sectionsOf(NbtNode level) {
        NbtNode secs = level.get("Sections");
        if (secs == null || secs.type != NbtNode.TAG_LIST) return new ArrayList<NbtNode>();
        return secs.asList();
    }
}
