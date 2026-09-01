package com.chunkops.core;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

/**
 * 精确数据层（P2，设计文档 §6.6）自研格式 COPM v1：
 * 一个 region（256×256 列）一份文件，游戏内采集结果（颜色+高度+方块名+生物群系名）。
 *
 * 文件布局（大端）：
 *   [魔数 4B "COPM"][版本 1B = 1][GZIP 流]
 * GZIP 解压后：
 *   [u16 width][u16 height]                     列数（region = 256×256），列索引 = dz*width+dx
 *   [u16 blockDictN]  { [u16 len][UTF8 字节] }* 方块名字典（"air" 固定索引 0）
 *   [u16 biomeDictN]  { [u16 len][UTF8 字节] }* 生物群系名字典（"?" 固定索引 0）
 *   RLE 记录流：{ [varint count][u32 color][u8 height][u16 blockIdx][u8 biomeIdx] }*
 *     count 之和必等于 width*height；相同 (color,height,blockIdx,biomeIdx) 的相邻列合并。
 *
 * 无数据（虚空/整列空气）约定：blockIdx=0 && height=0 && color=0。
 * 纯 Java 8、无 MC 依赖：模组内与独立工具链（verifier）均可读写。
 */
public class ExactMapFile {

    public static final int MAGIC = 0x434F504D; // "COPM"
    public static final int VERSION = 1;
    public static final int DEFAULT_SIZE = 256;

    /** 一个 region 的列数据（256×256，含字典）。 */
    public static class ExactRegion {
        public final int width;
        public final int height;
        public final int[] color;     // ARGB
        public final byte[] heightY;  // 地表 y（0..255）
        public final short[] blockIdx; // 方块名字典索引（0=air）
        public final byte[] biomeIdx;  // 生物群系名字典索引（0=未知）
        public final List<String> blockNames = new ArrayList<String>();
        public final List<String> biomeNames = new ArrayList<String>();

        public ExactRegion(int width, int height) {
            this.width = width;
            this.height = height;
            int n = width * height;
            this.color = new int[n];
            this.heightY = new byte[n];
            this.blockIdx = new short[n];
            this.biomeIdx = new byte[n];
            this.blockNames.add("air");
            this.biomeNames.add("?");
        }

        public int idx(int dx, int dz) {
            return dz * width + dx;
        }

        /** 该列是否有采集数据（非空白约定值）。 */
        public boolean hasData(int i) {
            return blockIdx[i] != 0 || heightY[i] != 0 || color[i] != 0;
        }
    }

    // ------------------------------------------------------------ write

    /** 写入 .dat（原子：临时文件 + rename；目录自动创建）。失败抛 IOException。 */
    public static void write(File file, ExactRegion r) throws IOException {
        File dir = file.getParentFile();
        if (dir != null && !dir.isDirectory() && !dir.mkdirs()) {
            throw new IOException("无法创建目录: " + dir);
        }
        File tmp = new File(file.getParentFile(), file.getName() + ".tmp");
        DataOutputStream out = new DataOutputStream(new BufferedOutputStream(new FileOutputStream(tmp)));
        try {
            out.writeInt(MAGIC);
            out.writeByte(VERSION);
            GZIPOutputStream gz = new GZIPOutputStream(out);
            // 注意：GZIPOutputStream close 会关掉底层 out，因此用包装后统一处理
            DataOutputStream body = new DataOutputStream(gz);
            body.writeShort(r.width);
            body.writeShort(r.height);
            writeDict(body, r.blockNames);
            writeDict(body, r.biomeNames);
            // RLE 列数据
            int n = r.width * r.height;
            int i = 0;
            while (i < n) {
                int j = i;
                while (j < n && same(r, i, j)) j++;
                writeVarInt(body, j - i);
                body.writeInt(r.color[i]);
                body.writeByte(r.heightY[i]);
                body.writeShort(r.blockIdx[i]);
                body.writeByte(r.biomeIdx[i]);
                i = j;
            }
            body.flush();
            gz.finish();
        } finally {
            out.close();
        }
        // 原子替换
        if (file.exists() && !file.delete()) {
            throw new IOException("无法替换旧文件: " + file);
        }
        if (!tmp.renameTo(file)) {
            throw new IOException("无法写入: " + file);
        }
    }

    private static boolean same(ExactRegion r, int a, int b) {
        return r.color[a] == r.color[b] && r.heightY[a] == r.heightY[b]
                && r.blockIdx[a] == r.blockIdx[b] && r.biomeIdx[a] == r.biomeIdx[b];
    }

    private static void writeDict(DataOutputStream out, List<String> dict) throws IOException {
        out.writeShort(dict.size());
        for (String s : dict) {
            byte[] b = s.getBytes("UTF-8");
            out.writeShort(b.length);
            out.write(b);
        }
    }

    // ------------------------------------------------------------ read

    /** 读取 .dat。文件不存在返回 null；格式/损坏抛 IOException。 */
    public static ExactRegion read(File file) throws IOException {
        if (file == null || !file.isFile()) return null;
        DataInputStream in = new DataInputStream(new BufferedInputStream(new FileInputStream(file)));
        try {
            int magic = in.readInt();
            if (magic != MAGIC) throw new IOException("魔数错误: " + file);
            int ver = in.readUnsignedByte();
            if (ver != VERSION) throw new IOException("版本不支持: " + ver);
            DataInputStream body = new DataInputStream(new GZIPInputStream(in));
            int w = body.readUnsignedShort();
            int h = body.readUnsignedShort();
            if (w <= 0 || h <= 0 || w > 4096 || h > 4096) {
                throw new IOException("尺寸非法: " + w + "x" + h);
            }
            ExactRegion r = new ExactRegion(w, h);
            readDict(body, r.blockNames);
            readDict(body, r.biomeNames);
            int n = w * h;
            int i = 0;
            while (i < n) {
                int count = readVarInt(body);
                if (count <= 0 || i + count > n) throw new IOException("RLE 越界(len=" + count + ")");
                int color = body.readInt();
                int y = body.readUnsignedByte();
                int bIdx = body.readUnsignedShort();
                int bioIdx = body.readUnsignedByte();
                if (bIdx >= r.blockNames.size() || bioIdx >= r.biomeNames.size()) {
                    throw new IOException("字典索引越界: block=" + bIdx + "/" + r.blockNames.size()
                            + " biome=" + bioIdx + "/" + r.biomeNames.size());
                }
                for (int k = 0; k < count; k++) {
                    r.color[i] = color;
                    r.heightY[i] = (byte) y;
                    r.blockIdx[i] = (short) bIdx;
                    r.biomeIdx[i] = (byte) bioIdx;
                    i++;
                }
            }
            return r;
        } catch (EOFException e) {
            throw new IOException("文件截断: " + file, e);
        } finally {
            in.close();
        }
    }

    private static void readDict(DataInputStream in, List<String> dict) throws IOException {
        int n = in.readUnsignedShort();
        dict.clear();
        for (int i = 0; i < n; i++) {
            int len = in.readUnsignedShort();
            byte[] b = new byte[len];
            in.readFully(b);
            dict.add(new String(b, "UTF-8"));
        }
    }

    // ------------------------------------------------------------ varint (unsigned LEB128)

    static void writeVarInt(DataOutputStream out, int v) throws IOException {
        while ((v & ~0x7F) != 0) {
            out.writeByte((v & 0x7F) | 0x80);
            v >>>= 7;
        }
        out.writeByte(v);
    }

    static int readVarInt(DataInputStream in) throws IOException {
        int v = 0;
        for (int i = 0; i < 5; i++) {
            int b = in.readUnsignedByte();
            v |= (b & 0x7F) << (i * 7);
            if ((b & 0x80) == 0) return v;
        }
        throw new IOException("varint 过长");
    }

    // ------------------------------------------------------------ 工具

    /** 生成 region 文件名（与 MCA 一致）。 */
    public static String fileName(int rx, int rz) {
        return "r." + rx + "." + rz + ".dat";
    }
}
