package com.chunkops.verify;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.Arrays;
import java.util.zip.DeflaterOutputStream;

/**
 * 生成一个「标准 palette 格式」的 1.12.2 测试 region 文件（离线自造样本）。
 *
 * 用途：在没有 palette 格式真实存档时验证 palette 解码/位打包逻辑，
 * 同时验证 region 文件的写入路径（阶段 1 数据层的基础）。
 *
 * 生成内容：r.0.0.mca，chunk(0,0) 为 palette 格式：
 *   palette = [1(stone), 2(grass), 7(bedrock)]，4 bits/block，
 *   BlockStates 值 = index % 3（可预测的模式，便于核对索引顺序）。
 */
public class MakePaletteSample {

    public static void main(String[] args) throws Exception {
        File outDir = args.length > 0 ? new File(args[0]) : new File("tools/verifier/samples");
        outDir.mkdirs();
        File regionFile = new File(outDir, "r.0.0.mca");

        byte[] nbt = buildChunkNbt();
        byte[] compressed = deflate(nbt);
        int dataLen = compressed.length + 1; // + 1 字节压缩类型
        byte[] chunk = new byte[4 + dataLen];
        chunk[0] = (byte) (dataLen >>> 24);
        chunk[1] = (byte) (dataLen >>> 16);
        chunk[2] = (byte) (dataLen >>> 8);
        chunk[3] = (byte) dataLen;
        chunk[4] = 2; // zlib
        System.arraycopy(compressed, 0, chunk, 5, compressed.length);
        System.out.println("chunk data size = " + chunk.length + " (fits 1 sector: " + (chunk.length <= 4096) + ")");

        byte[] region = new byte[3 * RegionReader.SECTOR_BYTES];
        // location[0] = (2 << 8) | 1  （扇区 2 起、1 个扇区），4 字节大端 = {0,0,2,1}
        int loc = (2 << 8) | 1;
        region[0] = (byte) (loc >>> 24);
        region[1] = (byte) (loc >>> 16);
        region[2] = (byte) (loc >>> 8);
        region[3] = (byte) loc;
        // timestamp[0]
        int ts = (int) (System.currentTimeMillis() / 1000);
        region[4096] = (byte) (ts >>> 24);
        region[4097] = (byte) (ts >>> 16);
        region[4098] = (byte) (ts >>> 8);
        region[4099] = (byte) ts;
        System.arraycopy(chunk, 0, region, 2 * RegionReader.SECTOR_BYTES, chunk.length);

        FileOutputStream fos = new FileOutputStream(regionFile);
        fos.write(region);
        fos.close();
        System.out.println("written: " + regionFile.getAbsolutePath() + " (" + region.length + " bytes)");
    }

    static byte[] deflate(byte[] data) throws IOException {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        DeflaterOutputStream dos = new DeflaterOutputStream(bos);
        dos.write(data);
        dos.close();
        return bos.toByteArray();
    }

    static byte[] buildChunkNbt() throws IOException {
        NbtNode root = NbtNode.compound();
        root.asMap().put("DataVersion", NbtNode.intNode(1343));

        NbtNode level = NbtNode.compound();
        root.asMap().put("Level", level);
        level.asMap().put("xPos", NbtNode.intNode(0));
        level.asMap().put("zPos", NbtNode.intNode(0));

        byte[] biomes = new byte[256];
        Arrays.fill(biomes, (byte) 1); // plains
        level.asMap().put("Biomes", NbtNode.byteArrayNode(biomes));

        int[] heightMap = new int[256];
        Arrays.fill(heightMap, 3);
        level.asMap().put("HeightMap", NbtNode.intArrayNode(heightMap));

        // section Y=0：palette [stone(1:0)=16, grass(2:0)=32, bedrock(7:0)=112]
        // stateId = blockId << 4 | meta（1.12 语义）
        NbtNode sec = NbtNode.compound();
        sec.asMap().put("Y", NbtNode.byteNode((byte) 0));
        NbtNode palette = NbtNode.list();
        palette.listElemType = NbtNode.TAG_INT;
        palette.asList().add(NbtNode.intNode(1 << 4 | 0)); // stone
        palette.asList().add(NbtNode.intNode(2 << 4 | 0)); // grass
        palette.asList().add(NbtNode.intNode(7 << 4 | 0)); // bedrock
        sec.asMap().put("Palette", palette);

        int bits = 4; // max(4, ceil(log2(3))) = 4
        long[] states = new long[(4096 * bits + 63) / 64]; // 32 longs
        for (int i = 0; i < 4096; i++) {
            setPacked(states, i, bits, i % 3);
        }
        sec.asMap().put("BlockStates", NbtNode.longArrayNode(states));

        byte[] blockLight = new byte[2048];
        byte[] skyLight = new byte[2048];
        Arrays.fill(skyLight, (byte) 0xFF);
        sec.asMap().put("BlockLight", NbtNode.byteArrayNode(blockLight));
        sec.asMap().put("SkyLight", NbtNode.byteArrayNode(skyLight));

        NbtNode sections = NbtNode.list();
        sections.listElemType = NbtNode.TAG_COMPOUND;
        sections.asList().add(sec);
        level.asMap().put("Sections", sections);

        NbtNode tes = NbtNode.list();
        tes.listElemType = NbtNode.TAG_COMPOUND;
        level.asMap().put("TileEntities", tes);
        NbtNode ents = NbtNode.list();
        ents.listElemType = NbtNode.TAG_COMPOUND;
        level.asMap().put("Entities", ents);

        return NbtWriter.writeRoot(root);
    }

    /** 与 ChunkInspector.getPacked 对称的写入（低位在前、跨 long 连续）。 */
    static void setPacked(long[] arr, int index, int bits, long value) {
        int bitIndex = index * bits;
        int longIndex = bitIndex >>> 6;
        int offset = bitIndex & 63;
        long mask = bits == 64 ? -1L : (1L << bits) - 1;
        value &= mask;
        if (offset + bits <= 64) {
            arr[longIndex] = (arr[longIndex] & ~(mask << offset)) | (value << offset);
        } else {
            int first = 64 - offset;
            arr[longIndex] = (arr[longIndex] & ~(mask << offset)) | (value << offset);
            arr[longIndex + 1] = (arr[longIndex + 1] & ~(mask >>> first)) | (value >>> first);
        }
    }
}
