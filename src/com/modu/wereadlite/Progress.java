package com.modu.wereadlite;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 整本书的页码与百分比。
 *
 * 目录接口给了每章 wordCount，用它当权重。真正排版过的章节只用来标定
 * 「一页多少字」这个比例（前 3 章取平均，之后锁定），不会逐章改写累计页数，
 * 否则一边读一边重算会出现"翻到下一章页码反而变小"的怪现象。
 *
 * 这样整本书不需要下载完，全书页数在打开一次目录后就能算出来，而且随翻页单调递增。
 */
public class Progress {

    /** 每章权重（字数）。 */
    private final int[] words;
    /** 估算时给没有 wordCount 的章节兜底的字数。 */
    private final int fallbackWords;
    /** 目录里至少有一章带 wordCount，说明这本书的字数信息可用。 */
    private final boolean hasWords;
    private long totalWeight;

    /** 页/字。0 表示还没标定过，先用默认值。 */
    private double perChar;
    /** 已参与标定的章节数，够 3 章就锁死比例。 */
    private int samples;
    /** 当前排版签名，字号/边距/行距一变就重新标定。 */
    private String layout = "";

    private int[] pages;
    private int[] prefix;
    private boolean dirty = true;

    /** 记录过的原始数据，只用于调试与异常值判断。 */
    private final HashMap<Integer, int[]> actual = new HashMap<Integer, int[]>();

    public Progress(List<Map<String, Object>> chapters) {
        int n = chapters == null ? 0 : chapters.size();
        words = new int[n];
        long counted = 0;
        int countedN = 0;
        for (int i = 0; i < n; i++) {
            Object w = chapters.get(i).get("wordCount");
            int v = w instanceof Number ? ((Number) w).intValue() : 0;
            if (v < 0) v = 0;
            words[i] = v;
            if (v > 50) {
                counted += v;
                countedN++;
            }
        }
        hasWords = countedN > 0;
        int fb = countedN == 0 ? 1000 : (int) (counted / countedN);
        if (fb < 200) fb = 200;
        fallbackWords = fb;
        for (int i = 0; i < n; i++) totalWeight += weight(i);
    }

    public int size() {
        return words.length;
    }

    /** 字号/边距/行距变了，之前标定的比例作废。 */
    public void setLayout(String signature) {
        if (signature == null) signature = "";
        if (signature.equals(layout)) return;
        layout = signature;
        perChar = 0;
        samples = 0;
        dirty = true;
    }

    /**
     * 报告某章的真实排版结果，用来标定「一页多少字」。
     * 只在前 3 次、且和已有比例不矛盾时才采纳，避免个别短章把整本带偏。
     */
    public void setActual(int index, int pageCount, int charCount) {
        if (index < 0 || index >= words.length || pageCount <= 0 || charCount <= 0) return;
        actual.put(Integer.valueOf(index), new int[]{pageCount, charCount});
        if (samples >= 3) return;
        // 目录页、版权页这种几十个字的章节排版出来一页，用它标定会把整本算成几万页
        if (charCount < 300) return;
        double r = (double) pageCount / charCount;
        if (r > 1.0 / 150.0 || r < 1.0 / 2200.0) return;   // 一页 150~2200 字之外都不正常
        if (samples == 0) {
            perChar = r;
            samples = 1;
            dirty = true;
            return;
        }
        if (Math.abs(r - perChar) / perChar > 0.35) return;   // 明显异常，忽略
        perChar = (perChar * samples + r) / (samples + 1);
        samples++;
        dirty = true;
    }

    private int weight(int i) {
        if (words[i] > 0) return words[i];
        // 有字数信息时，缺 wordCount 的多半是封面/版权页，按很短的章节算，别把全书撑大；
        // 整本都没有 wordCount 时只能按平均厚度估。
        return hasWords ? 120 : fallbackWords;
    }

    private double ratio() {
        return perChar > 0 ? perChar : 1.0 / 520.0;
    }

    private void ensure() {
        if (!dirty) return;
        int n = words.length;
        pages = new int[n];
        prefix = new int[n + 1];
        double r = ratio();
        for (int i = 0; i < n; i++) {
            int p = (int) Math.round(weight(i) * r);
            pages[i] = p < 1 ? 1 : p;
            prefix[i + 1] = prefix[i] + pages[i];
        }
        dirty = false;
    }

    /** 全书总页数（含未读章节的估算）。 */
    public int totalPages() {
        ensure();
        return prefix[prefix.length - 1];
    }

    /** 全书总页数，末章用真实页数收尾。 */
    public int totalPages(int index, int localTotal) {
        return totalFor(index, localTotal);
    }

    /** 第 index 章之前的累计页数。 */
    public int pagesBefore(int index) {
        ensure();
        if (index <= 0) return 0;
        if (index >= prefix.length - 1) return prefix[prefix.length - 1];
        return prefix[index];
    }

    /** 第 index 章的第 localPage 页（从 0 起）在全书里是第几页（从 1 起）。 */
    public int pageNumber(int index, int localPage) {
        return pageNumber(index, localPage, -1);
    }

    /**
     * @param localTotal 本章实际有多少页；最后一章用它收尾，保证读完正好到头。
     */
    public int pageNumber(int index, int localPage, int localTotal) {
        ensure();
        int total = totalFor(index, localTotal);
        int p = pagesBefore(index) + localPage + 1;
        if (p > total) p = total;
        if (p < 1) p = 1;
        return p;
    }

    /** 按全书页数算的百分比，读到最后一页正好 100%。 */
    public int percent(int index, int localPage) {
        return percent(index, localPage, -1);
    }

    public int percent(int index, int localPage, int localTotal) {
        ensure();
        int total = totalFor(index, localTotal);
        if (total <= 0) return 0;
        int p = (int) Math.round((pagesBefore(index) + localPage + 1) * 100.0 / total);
        if (p < 1) p = 1;
        if (p > 100) p = 100;
        return p;
    }

    /**
     * 全书页数。前面的章节一律用估算，保证翻页时页码只增不减；
     * 只有最后一章换成真实页数，免得读到最后显示 93%。
     */
    private int totalFor(int index, int localTotal) {
        ensure();
        int total = prefix[prefix.length - 1];
        if (localTotal > 0 && index >= 0 && index == words.length - 1) {
            return prefix[index] + localTotal;
        }
        return total;
    }

    /** 按字数算的百分比，用于向服务端上报进度（跨设备同步更准）。 */
    public int charPercent(int index, int offset, int textLength) {
        if (totalWeight <= 0) return 0;
        long before = 0;
        for (int i = 0; i < index && i < words.length; i++) before += weight(i);
        double frac = textLength <= 0 ? 0 : (double) offset / textLength;
        double p = (before + frac * weight(Math.min(index, words.length - 1)))
                / totalWeight * 100.0;
        int v = (int) Math.round(p);
        if (v < 0) v = 0;
        if (v > 100) v = 100;
        return v;
    }
}
