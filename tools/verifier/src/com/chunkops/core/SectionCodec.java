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
        if (blocksNode != null && blocksNode.type == NbtNode.TAG_BYTE_ARRAY) {
            return decodeLegacy(section, (byte[]) blocksNode.value);
        }
        NbtNode palette = section.get("Palette");
        NbtNode blockStates = section.get("BlockStates");
        if (palette != null && blockStates != null
                && palette.type == NbtNode.TAG_LIST && blockStates.type == NbtNode.TAG_LONG_ARRAY) {
            return decodePalette(palette, (long[]) blockStates.value);
        }
        return null; // 空 section 或无数据
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

    /** 由 stateId 数组编码为 legacy 数组 section（Blocks/Data/可选 Add）。 */
    public static NbtNode encodeLegacy(int[] stateIds) {
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
