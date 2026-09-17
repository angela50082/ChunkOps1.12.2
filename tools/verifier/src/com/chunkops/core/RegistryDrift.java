package com.chunkops.core;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * 注册表漂移：比较两份快照（旧会话 vs 当前会话），产出 stateId/biomeId 的重映射表。
 *
 * 原理：存档里没有方块名，只有数字；但快照里有「name#meta ↔ stateId」。
 * 于是 旧stateId --(旧快照)--> name#meta --(当前快照)--> 新stateId，
 * 这就是「把旧编号翻译成当前编号」的全部依据。
 *
 * 三类结果：
 *   - 编号没变（same）：无需动；
 *   - 编号变了（changed）：进 stateMap / biomeMap，修复时改写；
 *   - 当前注册表里已经没有这个名字（missing）：**无法映射**，只能保留原值并报告
 *     （通常是模组被移除/换名，或该方块本就来自更早的会话）。
 */
public final class RegistryDrift {

    /** 旧 stateId → 当前 stateId（只含**发生变化**的项）。 */
    public final Map<Integer, Integer> stateMap = new HashMap<Integer, Integer>();
    /** 旧 biomeId → 当前 biomeId（只含发生变化的项）。 */
    public final Map<Integer, Integer> biomeMap = new HashMap<Integer, Integer>();
    /** 旧 stateId → 方块名（当前注册表里已不存在，修复时只能跳过）。 */
    public final Map<Integer, String> missingStates = new HashMap<Integer, String>();
    /** 旧 biomeId → 群系名（当前注册表里已不存在）。 */
    public final Map<Integer, String> missingBiomes = new HashMap<Integer, String>();

    public int sameStates;
    public int changedStates;
    public int sameBiomes;
    public int changedBiomes;

    public RegistryDrift() {
    }

    public boolean isEmpty() {
        return stateMap.isEmpty() && biomeMap.isEmpty();
    }

    public boolean hasMissing() {
        return !missingStates.isEmpty() || !missingBiomes.isEmpty();
    }

    /** 用「旧快照 + 当前快照」构建漂移表。 */
    public static RegistryDrift build(RegistrySnapshot oldSnap, RegistrySnapshot curSnap) {
        RegistryDrift d = new RegistryDrift();
        if (oldSnap == null || curSnap == null) return d;
        oldSnap.index();
        curSnap.index();
        for (RegistrySnapshot.BlockEntry e : oldSnap.blocks) {
            Integer now = curSnap.lookupBlockId(e.name);
            if (now == null) {
                d.missingStates.put(Integer.valueOf(e.id), e.name);
            } else if (now.intValue() != e.id) {
                d.stateMap.put(Integer.valueOf(e.id), now);
                d.changedStates++;
            } else {
                d.sameStates++;
            }
        }
        for (RegistrySnapshot.BiomeEntry e : oldSnap.biomes) {
            Integer now = curSnap.lookupBiomeId(e.name);
            if (now == null) {
                d.missingBiomes.put(Integer.valueOf(e.id), e.name);
            } else if (now.intValue() != e.id) {
                d.biomeMap.put(Integer.valueOf(e.id), now);
                d.changedBiomes++;
            } else {
                d.sameBiomes++;
            }
        }
        return d;
    }

    /** 旧编号里是否有变化（用于统计世界扫描结果）。 */
    public Integer remapState(int oldStateId) {
        return stateMap.get(Integer.valueOf(oldStateId));
    }

    public Integer remapBiome(int oldBiomeId) {
        return biomeMap.get(Integer.valueOf(oldBiomeId));
    }

    /** 人类可读摘要（供日志/报告；文案由调用方本地化时自行拼接）。 */
    public String summary() {
        StringBuilder sb = new StringBuilder();
        sb.append("方块状态: 变化 ").append(changedStates).append(" / 未变 ").append(sameStates);
        if (!missingStates.isEmpty()) sb.append(" / 当前注册表缺失 ").append(missingStates.size());
        sb.append("；生物群系: 变化 ").append(changedBiomes).append(" / 未变 ").append(sameBiomes);
        if (!missingBiomes.isEmpty()) sb.append(" / 缺失 ").append(missingBiomes.size());
        return sb.toString();
    }

    /** 缺失方块名样例（最多 n 条）。 */
    public List<String> missingSamples(int n) {
        Map<String, Integer> names = new TreeMap<String, Integer>();
        for (String s : missingStates.values()) {
            Integer c = names.get(s);
            names.put(s, c == null ? 1 : c + 1);
        }
        List<String> out = new ArrayList<String>();
        for (String s : names.keySet()) {
            if (out.size() >= n) break;
            out.add(s);
        }
        return out;
    }
}
