package com.chunkops.verify;

import java.io.RandomAccessFile;

/**
 * 诊断工具：打印 region 文件头部原始值。
 * 用法：DumpHeader <r.x.z.mca> [idx...]
 */
public class DumpHeader {

    public static void main(String[] args) throws Exception {
        RandomAccessFile raf = new RandomAccessFile(args[0], "r");
        System.out.println("file = " + args[0] + " length = " + raf.length());
        int nonzero = 0;
        for (int i = 0; i < 1024; i++) {
            raf.seek(i * 4L);
            int off = raf.readInt();
            if (off != 0) {
                if (nonzero < 5) System.out.println("nonzero offset: idx=" + i + " offset=" + off);
                nonzero++;
            }
        }
        System.out.println("total nonzero offsets: " + nonzero);
        for (int i = 1; i < args.length; i++) {
            int target = Integer.parseInt(args[i]);
            raf.seek(target * 4L);
            int off = raf.readInt();
            raf.seek(target * 4L + 4096);
            int size = raf.readInt();
            int len24 = size & 0xFFFFFF;
            int sectors = size >>> 24;
            System.out.println("idx=" + target + " offset=" + off + " -> byte " + (off * 4096L)
                    + " | size=0x" + Integer.toHexString(size)
                    + " len24=" + len24 + " sectors=" + sectors
                    + " | data range=[" + (off * 4096L) + "," + (off * 4096L + len24 + 3L) + "]"
                    + " | inside file=" + (off * 4096L + len24 + 3L <= raf.length()));
            if (off != 0) {
                raf.seek(off * 4096L);
                byte[] head = new byte[5];
                raf.readFully(head);
                int dataLen = ((head[0] & 0xFF) << 24) | ((head[1] & 0xFF) << 16) | ((head[2] & 0xFF) << 8) | (head[3] & 0xFF);
                System.out.println("    chunk header: dataLen=" + dataLen + " compression=" + (head[4] & 0xFF));
            }
        }
        raf.close();
    }
}
