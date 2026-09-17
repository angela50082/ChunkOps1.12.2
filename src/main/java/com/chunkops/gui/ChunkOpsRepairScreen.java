package com.chunkops.gui;

import com.chunkops.core.RegistryDrift;
import com.chunkops.core.RegistryRepair;
import com.chunkops.core.RegistrySnapshot;
import com.chunkops.core.SnapshotStore;
import net.minecraft.client.gui.GuiButton;
import net.minecraft.client.gui.GuiScreen;

import java.io.File;

/**
 * 注册表编号修复界面（阶段 B）。
 *
 * 用存档里记录的「旧会话快照」和当前会话快照按 name#meta 对齐，把存档 palette 里的编号
 * 改写为当前编号；默认先干跑（只统计），确认后再写入（自动 .mcabackup 备份 + 回读校验）。
 */
public class ChunkOpsRepairScreen extends GuiScreen {

    private static final int BTN_SCAN = 1;
    private static final int BTN_APPLY = 2;
    private static final int BTN_BACK = 3;

    private final GuiScreen parent;
    private final File worldDir;
    private final File dimDir;

    private volatile String status = "";
    private volatile RegistryRepair.Report report = null;
    private volatile String error = null;
    private volatile boolean busy = false;
    private boolean scanned = false;

    private String curHash = "-";
    private String markHash = null;
    private String snapshotName = "-";
    private String markTime = "-";
    private boolean canRepair = false;

    /** 刚修好的存档路径（编辑器回到前台时提示一次，见 consumeRepaired）。 */
    private static volatile String repairedWorldPath = null;

    /** 若这个存档刚被修复过，消费掉标记并返回 true（只提示一次）。 */
    public static boolean consumeRepaired(File worldDir) {
        String p = repairedWorldPath;
        if (p == null || worldDir == null) return false;
        if (p.equals(worldDir.getAbsolutePath())) {
            repairedWorldPath = null;
            return true;
        }
        return false;
    }

    public ChunkOpsRepairScreen(GuiScreen parent, File worldDir, File dimDir) {
        this.parent = parent;
        this.worldDir = worldDir;
        this.dimDir = dimDir;
        refreshInfo();
    }

    private void refreshInfo() {
        curHash = SnapshotStore.shortHash(GuiOps.currentMappingHash());
        markHash = null;
        snapshotName = "-";
        markTime = "-";
        SnapshotStore.WorldMark mark = GuiOps.worldMark(worldDir);
        if (mark != null) {
            markHash = SnapshotStore.shortHash(mark.mappingHash);
            snapshotName = mark.snapshot == null || mark.snapshot.isEmpty()
                    ? ChunkOpsLang.t("chunkops.repair.missing") : mark.snapshot;
            if (mark.writtenAt > 0) {
                markTime = new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm")
                        .format(new java.util.Date(mark.writtenAt));
            }
        }
        File snap = GuiOps.worldMarkSnapshot(worldDir);
        canRepair = mark != null && snap != null && markHash != null && !markHash.equals(curHash);
    }

    @Override
    public void initGui() {
        this.buttonList.clear();
        int cx = this.width / 2;
        int y = this.height - 52;
        GuiButton scan = new GuiButton(BTN_SCAN, cx - 205, y, 130, 20,
                ChunkOpsLang.t("chunkops.repair.scan"));
        GuiButton apply = new GuiButton(BTN_APPLY, cx - 65, y, 130, 20,
                ChunkOpsLang.t("chunkops.repair.apply"));
        apply.enabled = canRepair && !busy;
        this.buttonList.add(scan);
        this.buttonList.add(apply);
        this.buttonList.add(new GuiButton(BTN_BACK, cx + 75, y, 130, 20,
                ChunkOpsLang.t("chunkops.repair.back")));
    }

    @Override
    protected void actionPerformed(GuiButton button) {
        if (button.id == BTN_BACK) {
            net.minecraft.client.Minecraft.getMinecraft().displayGuiScreen(parent);
            return;
        }
        if (button.id == BTN_SCAN) {
            start(false);
            return;
        }
        if (button.id == BTN_APPLY) {
            if (!scanned) {
                // 第一次点：先干跑一次，让用户看到会影响多少区块
                start(false);
                scanned = true;
                status = ChunkOpsLang.t("chunkops.repair.confirm");
            } else {
                start(true);
                scanned = false;
            }
        }
    }

    private void start(final boolean apply) {
        if (busy) return;
        busy = true;
        error = null;
        status = ChunkOpsLang.t("chunkops.repair.busy");
        Thread t = new Thread(new Runnable() {
            public void run() {
                try {
                    File snapFile = GuiOps.worldMarkSnapshot(worldDir);
                    if (snapFile == null) {
                        error = ChunkOpsLang.t("chunkops.repair.noSnapshot");
                        return;
                    }
                    RegistrySnapshot oldSnap = RegistrySnapshot.load(snapFile);
                    RegistrySnapshot curSnap = GuiOps.liveSnapshot();
                    RegistryDrift drift = RegistryDrift.build(oldSnap, curSnap);
                    // 编号漂移是全存档性质的：只修主世界、放着下界/末地/模组维度不管，会留下不一致的存档，
                    // 所以这里对**所有带 region 的维度目录**都跑一遍（同一个漂移表）。
                    RegistryRepair.Report total = new RegistryRepair.Report();
                    total.applied = apply;
                    int dims = 0;
                    for (File dim : GuiOps.dimensionDirs(worldDir)) {
                        dims++;
                        merge(total, RegistryRepair.repair(dim, drift, apply));
                    }
                    total.notes.add("维度 " + dims + " 个");
                    report = total;
                    if (apply && report.chunksWritten > 0) {
                        GuiOps.writeWorldMark(worldDir); // 修完就是当前编号了
                        repairedWorldPath = worldDir.getAbsolutePath();
                        // 关键：剪贴板里是修复前的原始数字，用它粘贴会出错，必须作废
                        if (parent instanceof ChunkOpsEditorScreen) {
                            ((ChunkOpsEditorScreen) parent).onWorldRegistryRepaired();
                        }
                    }
                } catch (Throwable t2) {
                    error = String.valueOf(t2);
                } finally {
                    busy = false;
                }
            }
        }, "chunkops-repair");
        t.setDaemon(true);
        t.start();
    }

    /** 汇总各维度的报告。 */
    private static void merge(RegistryRepair.Report total, RegistryRepair.Report r) {
        total.regionFiles += r.regionFiles;
        total.chunksSeen += r.chunksSeen;
        total.chunksChanged += r.chunksChanged;
        total.chunksWritten += r.chunksWritten;
        total.paletteEntries += r.paletteEntries;
        total.paletteRemapped += r.paletteRemapped;
        total.biomeEntries += r.biomeEntries;
        total.biomeRemapped += r.biomeRemapped;
        total.legacySections += r.legacySections;
        total.unmappablePalette += r.unmappablePalette;
        total.unmappableBiomes += r.unmappableBiomes;
        total.verifyFailed += r.verifyFailed;
        total.elapsedMs += r.elapsedMs;
        for (java.util.Map.Entry<String, Long> e : r.unmappableNames.entrySet()) {
            Long c = total.unmappableNames.get(e.getKey());
            total.unmappableNames.put(e.getKey(), c == null ? e.getValue() : c + e.getValue());
        }
        total.notes.addAll(r.notes);
    }

    @Override
    public void drawScreen(int mouseX, int mouseY, float partialTicks) {
        this.drawDefaultBackground();
        int cx = this.width / 2;
        int y = 24;
        this.drawCenteredString(this.fontRenderer, ChunkOpsLang.t("chunkops.repair.title"), cx, y, 0xFFFFFF);
        y += 24;

        String[] lines = new String[]{
                ChunkOpsLang.t("chunkops.repair.current", curHash),
                markHash == null ? ChunkOpsLang.t("chunkops.repair.noMark")
                        : ChunkOpsLang.t("chunkops.repair.worldMark", markHash, markTime),
                ChunkOpsLang.t("chunkops.repair.snapshot", snapshotName),
                "",
                error != null ? ChunkOpsLang.t("chunkops.repair.failed", error)
                        : (status.isEmpty() ? "" : status),
        };
        for (String line : lines) {
            if (!line.isEmpty()) {
                this.drawCenteredString(this.fontRenderer, line, cx, y,
                        error != null ? 0xFF6666 : 0xCCCCCC);
            }
            y += 14;
        }

        RegistryRepair.Report r = report;
        if (r != null) {
            y += 4;
            this.drawCenteredString(this.fontRenderer,
                    ChunkOpsLang.t("chunkops.repair.scanResult", Integer.valueOf(r.chunksSeen),
                            Integer.valueOf(r.chunksChanged), Long.valueOf(r.paletteEntries),
                            Long.valueOf(r.paletteRemapped), Long.valueOf(r.biomeRemapped)),
                    cx, y, 0xAAAAAA);
            y += 14;
            if (r.applied) {
                this.drawCenteredString(this.fontRenderer,
                        ChunkOpsLang.t("chunkops.repair.applyResult",
                                Integer.valueOf(r.chunksWritten), Integer.valueOf(r.verifyFailed)),
                        cx, y, r.verifyFailed > 0 ? 0xFF6666 : 0x88FF88);
                y += 14;
            }
            if (r.legacySections > 0) {
                this.drawCenteredString(this.fontRenderer,
                        ChunkOpsLang.t("chunkops.repair.legacy", Integer.valueOf(r.legacySections)),
                        cx, y, 0xAAAAAA);
                y += 14;
            }
            if (r.unmappablePalette > 0 || r.unmappableBiomes > 0) {
                this.drawCenteredString(this.fontRenderer,
                        ChunkOpsLang.t("chunkops.repair.unmappable",
                                Long.valueOf(r.unmappablePalette), Integer.valueOf(r.unmappableNames.size())),
                        cx, y, 0xFFAA55);
                y += 14;
                int n = 0;
                for (java.util.Map.Entry<String, Long> e : r.unmappableNames.entrySet()) {
                    if (n++ >= 8) break;
                    this.drawCenteredString(this.fontRenderer,
                            "  " + e.getKey() + " x" + e.getValue(), cx, y, 0x888888);
                    y += 12;
                }
            }
            this.drawCenteredString(this.fontRenderer,
                    ChunkOpsLang.t("chunkops.repair.elapsed", Long.valueOf(r.elapsedMs)), cx, y, 0x888888);
            y += 16;
        }

        // 底部说明（多行短句）
        int hy = this.height - 96;
        for (String s : new String[]{
                ChunkOpsLang.t("chunkops.repair.hint1"),
                ChunkOpsLang.t("chunkops.repair.hint2")}) {
            this.drawCenteredString(this.fontRenderer, s, cx, hy, 0x777777);
            hy += 12;
        }

        super.drawScreen(mouseX, mouseY, partialTicks);
    }

    @Override
    public boolean doesGuiPauseGame() {
        return false;
    }
}
