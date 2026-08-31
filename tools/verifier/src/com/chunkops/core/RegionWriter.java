package com.chunkops.core;

import com.chunkops.verify.NbtNode;
import com.chunkops.verify.NbtReader;
import com.chunkops.verify.NbtWriter;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.util.HashMap;
import java.util.Map;
import java.util.zip.DeflaterOutputStream;

/**
 * Region（.mca）写回器：整文件重建策略。
 *
 * - 读入：location 表 + 时间戳表 + 所有 chunk 原始数据（懒加载缓存）
 * - 修改：setChunk(index, chunkPayload) / removeChunk(index)
 * - 写回：备份原文件 → 重建头部（location = (扇区<<8)|扇区数，时间戳=now）→ 顺序写数据区
 * - 原子写：先写 .tmp 再替换
 *
 * chunkPayload = 4B 长度前缀 + 1B 压缩类型 + NBT（与 RegionReader.readChunkData 输出一致）。
 */
public class RegionWriter {

    private final File file;
    private final Map<Integer, byte[]> chunks = new HashMap<Integer, byte[]>(); // 变更后的数据
    private final int[] timestamps = new int[RegionConstants.CHUNKS_PER_REGION];
    private boolean loaded = false;

    public RegionWriter(File file) {
        this.file = file;
    }

    public File getFile() {
        return file;
    }

    private void loadIfNeeded() throws IOException {
        if (loaded) return;
        loaded = true;
        if (!file.isFile()) return;
        RandomAccessFile raf = new RandomAccessFile(file, "r");
        try {
            for (int i = 0; i < RegionConstants.CHUNKS_PER_REGION; i++) {
                raf.seek(i * 4L);
                int loc = raf.readInt();
                raf.seek(i * 4L + RegionConstants.SECTOR_BYTES);
                timestamps[i] = raf.readInt();
                if (loc == 0) continue;
                int sectorOffset = (loc >>> 8) * RegionConstants.SECTOR_BYTES;
                int sectorCount = loc & 0xFF;
                if (sectorCount <= 0 || sectorOffset + (long) sectorCount * RegionConstants.SECTOR_BYTES > raf.length()) {
                    continue; // 越界条目（脏数据），跳过
                }
                raf.seek(sectorOffset);
                byte[] head = new byte[5];
                raf.readFully(head);
                int dataLen = ((head[0] & 0xFF) << 24) | ((head[1] & 0xFF) << 16)
                        | ((head[2] & 0xFF) << 8) | (head[3] & 0xFF);
                int total = 4 + dataLen;
                if (total < 5 || total > sectorCount * RegionConstants.SECTOR_BYTES) continue;
                byte[] data = new byte[total];
                System.arraycopy(head, 0, data, 0, 5);
                raf.readFully(data, 5, total - 5);
                chunks.put(i, data);
            }
        } finally {
            raf.close();
        }
    }

    /** 是否存在该 chunk（含磁盘上的）。 */
    public boolean hasChunk(int index) throws IOException {
        loadIfNeeded();
        return chunks.containsKey(index);
    }

    /** 设置/覆盖 chunk（payload = 4B 长度 + 1B 压缩 + NBT）。先确保已加载磁盘数据。 */
    public void setChunk(int index, byte[] chunkPayload) throws IOException {
        loadIfNeeded();
        chunks.put(index, chunkPayload);
    }

    /** 从 region 移除 chunk（location 置 0）。先确保已加载磁盘数据。 */
    public void removeChunk(int index) throws IOException {
        loadIfNeeded();
        chunks.remove(index);
    }

    /** 备份当前 region 文件到 <name>.mcabackup（时间戳后缀）。 */
    public File backup() throws IOException {
        if (!file.isFile()) return null;
        File bak = new File(file.getParentFile(), file.getName() + "." + System.currentTimeMillis() + ".mcabackup");
        copyFile(file, bak);
        return bak;
    }

    /** 写回磁盘（自动备份 + 原子写）。返回备份文件（无原文件时返回 null）。 */
    public File write() throws IOException {
        loadIfNeeded();
        File backupFile = backup();
        File tmp = new File(file.getParentFile(), file.getName() + ".tmp");
        byte[] out = buildFile();
        RandomAccessFile raf = new RandomAccessFile(tmp, "rw");
        try {
            raf.write(out);
        } finally {
            raf.close();
        }
        if (!tmp.renameTo(file)) {
            // rename 失败（如被占用）时尝试直接覆盖
            copyFile(tmp, file);
            tmp.delete();
        }
        return backupFile;
    }

    /** 构建完整的 region 文件字节（头部 + 数据区，顺序写入）。 */
    byte[] buildFile() throws IOException {
        int sectorCount = 2; // 头部 2 扇区
        Map<Integer, Integer> sectorOf = new HashMap<Integer, Integer>();
        java.util.List<Integer> indices = new java.util.ArrayList<Integer>(chunks.keySet());
        java.util.Collections.sort(indices);
        for (int idx : indices) {
            byte[] data = chunks.get(idx);
            int sectors = (data.length + RegionConstants.SECTOR_BYTES - 1) / RegionConstants.SECTOR_BYTES;
            sectorOf.put(idx, sectorCount);
            sectorCount += sectors;
        }
        byte[] out = new byte[sectorCount * RegionConstants.SECTOR_BYTES];
        int now = (int) (System.currentTimeMillis() / 1000);
        for (int idx : indices) {
            byte[] data = chunks.get(idx);
            int loc = (sectorOf.get(idx) << 8) | ((data.length + RegionConstants.SECTOR_BYTES - 1) / RegionConstants.SECTOR_BYTES);
            out[idx * 4] = (byte) (loc >>> 24);
            out[idx * 4 + 1] = (byte) (loc >>> 16);
            out[idx * 4 + 2] = (byte) (loc >>> 8);
            out[idx * 4 + 3] = (byte) loc;
            int ts = timestamps[idx] != 0 ? timestamps[idx] : now;
            out[RegionConstants.SECTOR_BYTES + idx * 4] = (byte) (ts >>> 24);
            out[RegionConstants.SECTOR_BYTES + idx * 4 + 1] = (byte) (ts >>> 16);
            out[RegionConstants.SECTOR_BYTES + idx * 4 + 2] = (byte) (ts >>> 8);
            out[RegionConstants.SECTOR_BYTES + idx * 4 + 3] = (byte) ts;
            System.arraycopy(data, 0, out, sectorOf.get(idx) * RegionConstants.SECTOR_BYTES, data.length);
        }
        return out;
    }

    /** 把 NBT 节点打包为 chunk payload（zlib，与 vanilla 1.12 一致）。 */
    public static byte[] packChunk(NbtNode root) throws IOException {
        byte[] nbt = NbtWriter.writeRoot(root);
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        DeflaterOutputStream dos = new DeflaterOutputStream(bos);
        dos.write(nbt);
        dos.close();
        byte[] compressed = bos.toByteArray();
        int dataLen = compressed.length + 1; // + 压缩类型字节
        byte[] out = new byte[4 + dataLen];
        out[0] = (byte) (dataLen >>> 24);
        out[1] = (byte) (dataLen >>> 16);
        out[2] = (byte) (dataLen >>> 8);
        out[3] = (byte) dataLen;
        out[4] = 2; // zlib
        System.arraycopy(compressed, 0, out, 5, compressed.length);
        return out;
    }

    /** 解包 chunk payload → NBT 根节点。 */
    public static NbtNode unpackChunk(byte[] payload) throws IOException {
        return NbtReader.readChunkPayload(payload);
    }

    static void copyFile(File src, File dst) throws IOException {
        RandomAccessFile in = new RandomAccessFile(src, "r");
        RandomAccessFile out = new RandomAccessFile(dst, "rw");
        try {
            byte[] buf = new byte[65536];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
        } finally {
            in.close();
            out.close();
        }
    }

    /** region 常量（与 verify.RegionReader 对齐）。 */
    public static class RegionConstants {
        public static final int SECTOR_BYTES = 4096;
        public static final int CHUNKS_PER_REGION = 1024;
    }
}
