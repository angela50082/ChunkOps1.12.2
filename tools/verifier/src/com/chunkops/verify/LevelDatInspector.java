package com.chunkops.verify;

import java.io.File;
import java.io.IOException;

/**
 * level.dat 检查器：确认存档版本（DataVersion）、世界名、生成器类型。
 * 用法：LevelDatInspector <世界目录>
 */
public class LevelDatInspector {

    public static void main(String[] args) throws IOException {
        if (args.length < 1) {
            System.out.println("Usage: LevelDatInspector <worldDir>");
            return;
        }
        File world = new File(args[0]);
        File levelDat = new File(world, "level.dat");
        if (!levelDat.isFile()) {
            System.out.println("ERROR: " + levelDat + " not found");
            return;
        }
        byte[] raw = RegionReader.readAllBytes(levelDat);
        NbtNode root = NbtReader.read(new java.io.ByteArrayInputStream(raw), true);
        System.out.println("level.dat root: " + root.name);
        NbtNode data = root.get("Data");
        if (data == null) {
            System.out.println("ERROR: no Data compound");
            return;
        }
        NbtNode dv = data.get("DataVersion");
        System.out.println("DataVersion: " + (dv != null ? dv.intValue() : "ABSENT"));
        NbtNode name = data.get("LevelName");
        System.out.println("LevelName: " + (name != null ? name.stringValue() : "?"));
        NbtNode gen = data.get("generatorName");
        System.out.println("generatorName: " + (gen != null ? gen.stringValue() : "?"));
        NbtNode gt = data.get("GameType");
        System.out.println("GameType: " + (gt != null ? gt.intValue() : "?"));
        NbtNode ver = data.get("Version");
        if (ver != null) {
            NbtNode vid = ver.get("Id");
            NbtNode vname = ver.get("Name");
            System.out.println("Version: Id=" + (vid != null ? vid.intValue() : "?")
                    + " Name=" + (vname != null ? vname.stringValue() : "?"));
        } else {
            System.out.println("Version tag: ABSENT (pre-1.9 level.dat or modded)");
        }
        NbtNode regs = data.get("Registries");
        System.out.println("Registries tag: " + (regs != null ? "PRESENT (1.13+) ***" : "ABSENT (expected for 1.12)"));
    }
}
