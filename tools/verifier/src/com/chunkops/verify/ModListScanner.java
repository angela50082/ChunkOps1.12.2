package com.chunkops.verify;

import com.chunkops.core.Json;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * 整合包模组清单扫描器：遍历 mods 目录所有 jar，读取 mcmod.info，输出 modid:version 列表。
 * 用于与运行时导出的 registry-snapshot.json 的 mods 部分对照（离线预检）。
 *
 * 用法：ModListScanner <modsDir> [输出文件]
 */
public class ModListScanner {

    public static void main(String[] args) throws Exception {
        if (args.length < 1) {
            System.out.println("Usage: ModListScanner <modsDir> [outFile]");
            return;
        }
        File modsDir = new File(args[0]);
        File outFile = args.length > 1 ? new File(args[1]) : null;
        if (!modsDir.isDirectory()) {
            System.out.println("ERROR: not a directory: " + modsDir);
            return;
        }
        List<File> jars = new ArrayList<File>();
        collectJars(modsDir, jars);
        System.out.println("mods dir: " + modsDir + " (" + jars.size() + " jar files)");

        List<String> mods = new ArrayList<String>();
        List<String> failed = new ArrayList<String>();
        for (File jar : jars) {
            List<String> found = readMcmodInfo(jar);
            if (found.isEmpty()) {
                failed.add(jar.getName());
            } else {
                mods.addAll(found);
            }
        }
        java.util.Collections.sort(mods);
        System.out.println("parsed mods: " + mods.size() + " (from " + (jars.size() - failed.size()) + "/" + jars.size() + " jars)");
        System.out.println("failed jars: " + failed.size());
        for (String f : failed) System.out.println("  FAIL: " + f);

        // 统计 modid 前缀分布（按模组名首字母？按 modid 去重计数）
        java.util.Set<String> unique = new java.util.HashSet<String>();
        for (String m : mods) unique.add(m.substring(0, m.indexOf(':')));
        System.out.println("unique modids: " + unique.size());

        if (outFile != null) {
            StringBuilder sb = new StringBuilder();
            for (String m : mods) sb.append(m).append('\n');
            java.nio.file.Files.write(outFile.toPath(), sb.toString().getBytes(StandardCharsets.UTF_8));
            System.out.println("written: " + outFile.getAbsolutePath());
        }
    }

    static void collectJars(File dir, List<File> out) {
        File[] files = dir.listFiles();
        if (files == null) return;
        for (File f : files) {
            if (f.isDirectory()) collectJars(f, out);
            else if (f.getName().toLowerCase().endsWith(".jar")) out.add(f);
        }
    }

    /** 读取 jar 内 mcmod.info（根目录或 META-INF/），返回 ["modid:version", ...]。 */
    @SuppressWarnings("unchecked")
    static List<String> readMcmodInfo(File jar) {
        List<String> result = new ArrayList<String>();
        try {
            ZipFile zf = new ZipFile(jar);
            try {
                ZipEntry entry = zf.getEntry("mcmod.info");
                if (entry == null) entry = zf.getEntry("META-INF/mcmod.info");
                if (entry == null) return result;
                InputStream in = zf.getInputStream(entry);
                java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
                byte[] buf = new byte[4096];
                int n;
                while ((n = in.read(buf)) > 0) bos.write(buf, 0, n);
                in.close();
                String text = new String(bos.toByteArray(), StandardCharsets.UTF_8);
                Object root = Json.parse(text);
                if (root instanceof List) {
                    for (Object o : (List<Object>) root) {
                        if (!(o instanceof Map)) continue;
                        Map<String, Object> m = (Map<String, Object>) o;
                        String modid = Json.str(m, "modid");
                        if (modid == null) continue;
                        String version = Json.str(m, "version");
                        result.add(modid + ":" + (version != null ? version : "?"));
                    }
                } else if (root instanceof Map) {
                    // 根对象：可能为 {"modList": [...]}（新版格式）或单模组 {modid:..}
                    Map<String, Object> m = (Map<String, Object>) root;
                    Object modList = m.get("modList");
                    if (modList instanceof List) {
                        for (Object o : (List<Object>) modList) {
                            if (!(o instanceof Map)) continue;
                            Map<String, Object> mm = (Map<String, Object>) o;
                            String modid = Json.str(mm, "modid");
                            if (modid == null) continue;
                            String version = Json.str(mm, "version");
                            result.add(modid + ":" + (version != null ? version : "?"));
                        }
                    } else {
                        String modid = Json.str(m, "modid");
                        if (modid != null) {
                            String version = Json.str(m, "version");
                            result.add(modid + ":" + (version != null ? version : "?"));
                        }
                    }
                }
            } finally {
                zf.close();
            }
        } catch (Exception e) {
            // 解析失败
        }
        return result;
    }
}
