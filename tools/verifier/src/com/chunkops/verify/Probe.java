package com.chunkops.verify;

import com.chunkops.core.RegionWriter;
import com.chunkops.core.SectionCodec;
import com.chunkops.verify.NbtNode;

import java.io.File;
import java.util.Map;
import java.util.TreeMap;

public class Probe {
    public static void main(String[] a) throws Exception {
        RegionReader rr = new RegionReader(new File(a[0]));
        byte[] p = rr.readChunkData(Integer.parseInt(a[1]));
        NbtNode root = RegionWriter.unpackChunk(p);
        NbtNode level = root.get("Level");
        for (NbtNode sec : SectionCodec.sectionsOf(level)) {
            int[] ids = SectionCodec.decode(sec);
            if (ids == null) continue;
            TreeMap<Integer, Integer> c = new TreeMap<Integer, Integer>();
            for (int s : ids) {
                if (s != 0) c.put(s, c.containsKey(s) ? c.get(s) + 1 : 1);
            }
            for (Map.Entry<Integer, Integer> e : c.entrySet()) {
                int sid = e.getKey();
                System.out.println("palette项=" + sid + " x" + e.getValue());
            }
        }
    }
}
