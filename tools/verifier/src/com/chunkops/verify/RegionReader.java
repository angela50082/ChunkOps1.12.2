package com.chunkops.verify;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.util.ArrayList;
import java.util.List;

/**
 * .mca 区域文件读取器（1.12.2 Anvil 格式）。
 * 头部 8KB：1024 个 4 字节偏移（扇区号×4096）+ 1024 个 4 字节大小（高8位=扇区数）。
 * chunk 数据：4 字节长度 + 1 字节压缩类型 + NBT。
 */
public class RegionReader {

    public static final int SECTOR_BYTES = 4096;
    public static final int CHUNKS_PER_REGION = 1024;

    private final File file;

    public RegionReader(File file) {
        this.file = file;
    }

    public File getFile() {
        return file;
    }

    /** 解析 region 文件名 r.x.z.mca → [x, z]。失败返回 null。 */
    public static int[] regionCoords(String fileName) {
        // r.0.0.mca
        String base = fileName;
        if (base.toLowerCase().endsWith(".mca")) base = base.substring(0, base.length() - 4);
        String[] parts = base.split("\\.");
        if (parts.length != 3 || !parts[0].equalsIgnoreCase("r")) return null;
        try {
            return new int[]{Integer.parseInt(parts[1]), Integer.parseInt(parts[2])};
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /**
     * 读取指定块索引（0-1023）的原始 chunk 数据（含 4 字节长度前缀）。
     *
     * Anvil region 头部布局（重要！）：
     *   - 前 4096 字节：1024 个 4 字节 location 条目 = (起始扇区号 << 8) | 占用扇区数
     *   - 后 4096 字节：1024 个 4 字节时间戳（不是 size 表！）
     *   - chunk 数据自描述：4 字节长度 + 1 字节压缩类型 + NBT
     */
    public byte[] readChunkData(int index) throws IOException {
        RandomAccessFile raf = new RandomAccessFile(file, "r");
        try {
            raf.seek(index * 4L);
            int loc = raf.readInt();
            if (loc == 0) return null;
            int sectorOffset = (loc >>> 8) * SECTOR_BYTES;
            int sectorCount = loc & 0xFF;
            if (sectorCount <= 0 || sectorOffset + (long) sectorCount * SECTOR_BYTES > raf.length()) {
                return null; // 索引越界：损坏/截断的 region 槽位
            }
            raf.seek(sectorOffset);
            byte[] head = new byte[5];
            raf.readFully(head);
            int dataLen = ((head[0] & 0xFF) << 24) | ((head[1] & 0xFF) << 16)
                    | ((head[2] & 0xFF) << 8) | (head[3] & 0xFF); // 含压缩类型字节
            int total = 4 + dataLen; // 4 字节长度前缀 + 数据
            if (total < 5 || total > sectorCount * SECTOR_BYTES) {
                return null; // 长度前缀异常
            }
            byte[] data = new byte[total];
            System.arraycopy(head, 0, data, 0, 5);
            raf.readFully(data, 5, total - 5);
            return data;
        } finally {
            raf.close();
        }
    }

    /** 遍历本 region 中所有存在的 chunk 索引。 */
    public List<Integer> listChunkIndices() throws IOException {
        List<Integer> result = new ArrayList<Integer>();
        RandomAccessFile raf = new RandomAccessFile(file, "r");
        try {
            byte[] header = new byte[SECTOR_BYTES];
            raf.readFully(header);
            for (int i = 0; i < CHUNKS_PER_REGION; i++) {
                int off = (header[i * 4] & 0xFF) << 24 | (header[i * 4 + 1] & 0xFF) << 16
                        | (header[i * 4 + 2] & 0xFF) << 8 | (header[i * 4 + 3] & 0xFF);
                if (off != 0) result.add(i);
            }
        } finally {
            raf.close();
        }
        return result;
    }

    /** 收集整个 region 目录（含 DIM 子目录）下所有 .mca 文件。 */
    public static List<File> collectRegionFiles(File worldDir) {
        List<File> result = new ArrayList<File>();
        collectRegionFilesRecursive(worldDir, result);
        return result;
    }

    private static void collectRegionFilesRecursive(File dir, List<File> out) {
        if (dir == null || !dir.isDirectory()) return;
        File[] files = dir.listFiles();
        if (files == null) return;
        for (File f : files) {
            if (f.isDirectory()) {
                collectRegionFilesRecursive(f, out);
            } else if (f.getName().toLowerCase().endsWith(".mca")) {
                out.add(f);
            }
        }
    }

    /** 读取文件内容（用于 level.dat 等）。 */
    public static byte[] readAllBytes(File f) throws IOException {
        RandomAccessFile raf = new RandomAccessFile(f, "r");
        try {
            ByteArrayOutputStream bos = new ByteArrayOutputStream((int) Math.min(raf.length(), Integer.MAX_VALUE));
            byte[] buf = new byte[8192];
            int n;
            while ((n = raf.read(buf)) > 0) bos.write(buf, 0, n);
            return bos.toByteArray();
        } finally {
            raf.close();
        }
    }
}
