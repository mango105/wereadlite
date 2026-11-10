package com.modu.wereadlite;

/**
 * 分页计算：把「一批行 + 一页能放多高」换成每页的起始字符下标。
 * 单独拆出来是因为它最容易算错（多一页空白、行高超过整页时死循环），
 * 而 StaticLayout 只能在设备上拿到，这里做成纯函数好自测。
 */
final class Pager {

    private Pager() {
    }

    /**
     * @param lineStart 每行起始字符下标，须与 top/bottom 等长、按从上到下排列
     * @param lineTop   每行顶部
     * @param lineBottom 每行底部
     * @param pageH     正文可用页高（像素）
     * @return 每页起始字符下标，首项恒为 lineStart[0]；最后一页能整页放下的行不会被拆成两页
     */
    static int[] pageStarts(int[] lineStart, int[] lineTop, int[] lineBottom, int lines, int pageH) {
        if (lines <= 0 || pageH <= 0 || lineStart == null) return new int[]{0};
        int[] page = new int[lines + 1];
        int count = 0;
        page[count++] = lineStart[0];
        int i = 0;
        while (i < lines - 1) {
            int top = lineTop[i];
            int last = i;
            while (last + 1 < lines && lineBottom[last + 1] - top <= pageH) last++;
            if (last + 1 >= lines) break; // 当前页已经含最后一行，不再切出空白页
            // 单行比整页还高时只推进一行，否则原地打转
            i = last > i ? last + 1 : i + 1;
            page[count++] = lineStart[i];
        }
        int[] out = new int[count];
        System.arraycopy(page, 0, out, 0, count);
        return out;
    }
}
