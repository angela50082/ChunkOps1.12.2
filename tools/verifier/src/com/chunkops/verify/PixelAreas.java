package com.chunkops.verify;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.File;

/**
 * 指定区域像素统计（P2 调试）：平均色 + top 颜色，用于量化"地图暗 vs 色板亮"。
 * 用法: java -cp out com.chunkops.verify.PixelAreas <png> <x1>,<y1>,<x2>,<y2> [<x1>,<y1>,<x2>,<y2> ...]
 */
public class PixelAreas {

    public static void main(String[] args) throws Exception {
        BufferedImage img = ImageIO.read(new File(args[0]));
        int W = img.getWidth(), H = img.getHeight();
        System.out.println("image " + W + "x" + H);
        for (int a = 1; a < args.length; a++) {
            String[] p = args[a].split(",");
            int x1 = Integer.parseInt(p[0]), y1 = Integer.parseInt(p[1]);
            int x2 = Integer.parseInt(p[2]), y2 = Integer.parseInt(p[3]);
            long r = 0, g = 0, b = 0;
            int n = 0;
            java.util.Map<Integer, Integer> histo = new java.util.TreeMap<Integer, Integer>();
            for (int y = y1; y < y2 && y < H; y++) {
                for (int x = x1; x < x2 && x < W; x++) {
                    int c = img.getRGB(x, y);
                    r += (c >> 16) & 0xFF;
                    g += (c >> 8) & 0xFF;
                    b += c & 0xFF;
                    n++;
                    Integer c2 = histo.get(c & 0xFFFFFF);
                    histo.put(c & 0xFFFFFF, c2 == null ? 1 : c2 + 1);
                }
            }
            System.out.println("区域[" + args[a] + "] avg=(" + (r / n) + "," + (g / n) + "," + (b / n) + ") 像素=" + n);
            java.util.List<java.util.Map.Entry<Integer, Integer>> list =
                    new java.util.ArrayList<java.util.Map.Entry<Integer, Integer>>(histo.entrySet());
            java.util.Collections.sort(list, new java.util.Comparator<java.util.Map.Entry<Integer, Integer>>() {
                public int compare(java.util.Map.Entry<Integer, Integer> x, java.util.Map.Entry<Integer, Integer> y) {
                    return Integer.compare(y.getValue(), x.getValue());
                }
            });
            for (int i = 0; i < Math.min(3, list.size()); i++) {
                int c = list.get(i).getKey();
                System.out.println("   top: #" + String.format("%06X", c) + " (" + ((c >> 16) & 0xFF) + ","
                        + ((c >> 8) & 0xFF) + "," + (c & 0xFF) + ") x" + list.get(i).getValue());
            }
        }
    }
}
