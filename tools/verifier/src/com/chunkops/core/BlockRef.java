package com.chunkops.core;

/**
 * canonical 方块表示（阶段 1/2 的名称化中间格式）。
 *
 * 文件模式（无活注册表）下 name 的解析依赖 {@link RegistrySnapshot}；
 * 无快照时 name 回退为 "unknown:<id>:<meta>"（不丢信息）。
 */
public class BlockRef {

    public final String name;   // "modid:block" 或 "unknown:<id>:<meta>"
    public final int meta;      // 0-15

    public BlockRef(String name, int meta) {
        this.name = name;
        this.meta = meta;
    }

    /** 由数字 stateId 构造：id = stateId>>4, meta = stateId&15。 */
    public static BlockRef fromStateId(int stateId) {
        return new BlockRef("unknown:" + (stateId >> 4) + ":" + (stateId & 15), stateId & 15);
    }

    public int stateIdOr(int unknownId) {
        if (name.startsWith("unknown:")) {
            return unknownId << 4 | meta;
        }
        return -1; // 需要注册表解析
    }

    @Override
    public String toString() {
        return name + "#" + meta;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof BlockRef)) return false;
        BlockRef r = (BlockRef) o;
        return meta == r.meta && name.equals(r.name);
    }

    @Override
    public int hashCode() {
        return name.hashCode() * 31 + meta;
    }
}
