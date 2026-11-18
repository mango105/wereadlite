package com.modu.wereadlite;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/**
 * 本地划线数据层：纯 Java，不碰 Android，解析回归里可以直接跑。
 *
 * 微信读书没有公开「写划线」接口（koreader 插件闭源版才有），所以划线只存本机：
 * 「章节 uid + 起止字符偏移」，和进度、书签共用同一套原文坐标，改字号重排后位置不变。
 */
final class Highlights {

    static final int MAX_PER_BOOK = 500;
    static final int EXCERPT_LEN = 60;

    static final class Item {
        String uid = "";
        int idx;
        int start;
        int end;
        String excerpt = "";
        long at;
        /** 云端 bookmarkId；空 = 还没同步成功或映射失败（range 算不出来）。 */
        String rid = "";
        /** 划线在章节 HTML 里的 range（上传后缓存，重试不用重算）。 */
        String range = "";

        Item() {
        }

        Item(String uid, int idx, int start, int end, String excerpt, long at) {
            this.uid = uid == null ? "" : uid;
            this.idx = idx;
            this.start = Math.max(0, start);
            this.end = Math.max(this.start, end);
            this.excerpt = excerpt == null ? "" : excerpt;
            this.at = at;
        }

        boolean hit(int off) {
            return off >= start && off < end;
        }
    }

    static String encode(List<Item> items) {
        StringBuilder sb = new StringBuilder("[");
        if (items != null) {
            for (int i = 0; i < items.size(); i++) {
                if (i > 0) sb.append(',');
                Item it = items.get(i);
                sb.append("{\"u\":").append(Json.quote(it.uid))
                        .append(",\"i\":").append(it.idx)
                        .append(",\"s\":").append(it.start)
                        .append(",\"e\":").append(it.end)
                        .append(",\"x\":").append(Json.quote(it.excerpt))
                        .append(",\"r\":").append(Json.quote(it.rid))
                        .append(",\"g\":").append(Json.quote(it.range))
                        .append(",\"t\":").append(it.at).append('}');
            }
        }
        return sb.append(']').toString();
    }

    static List<Item> decode(String raw) {
        List<Item> out = new ArrayList<Item>();
        if (raw == null || raw.length() == 0) return out;
        for (Object o : Json.asList(Json.parse(raw))) {
            Map<String, Object> m = Json.asMap(o);
            if (m.isEmpty()) continue;
            Item it = new Item(
                    Json.str(m, "u"),
                    Json.num(m, "i"),
                    Json.num(m, "s"),
                    Json.num(m, "e"),
                    Json.str(m, "x"),
                    longOf(m.get("t")));
            it.rid = Json.str(m, "r");
            it.range = Json.str(m, "g");
            out.add(it);
        }
        return out;
    }

    /** 新增一条：完全相同的区间只更新时间（保留已同步的云端 id）；按章节/偏移排序。 */
    static List<Item> add(List<Item> items, Item one) {
        if (items == null) items = new ArrayList<Item>();
        for (int i = 0; i < items.size(); i++) {
            Item cur = items.get(i);
            if (cur.uid.equals(one.uid) && cur.start == one.start && cur.end == one.end) {
                if (one.rid.length() == 0) {
                    one.rid = cur.rid;
                    one.range = cur.range.length() > 0 ? cur.range : one.range;
                }
                items.set(i, one);
                return items;
            }
        }
        items.add(one);
        return sortAndTrim(items);
    }

    static boolean remove(List<Item> items, String uid, int start, int end) {
        for (int i = 0; i < items.size(); i++) {
            Item cur = items.get(i);
            if (cur.uid.equals(uid) && cur.start == start && cur.end == end) {
                items.remove(i);
                return true;
            }
        }
        return false;
    }

    /** 偏移落在哪条划线里（选区菜单/点击判断用）。 */
    static Item at(List<Item> items, int idx, int off) {
        for (Item it : items) {
            if (it.idx == idx && it.hit(off)) return it;
        }
        return null;
    }

    static List<Item> ofChapter(List<Item> items, int idx) {
        List<Item> out = new ArrayList<Item>();
        for (Item it : items) {
            if (it.idx == idx) out.add(it);
        }
        return out;
    }

    private static List<Item> sortAndTrim(List<Item> items) {
        Collections.sort(items, new Comparator<Item>() {
            @Override
            public int compare(Item a, Item b) {
                if (a.idx != b.idx) return a.idx < b.idx ? -1 : 1;
                if (a.start != b.start) return a.start < b.start ? -1 : 1;
                return 0;
            }
        });
        while (items.size() > MAX_PER_BOOK) items.remove(0);
        return items;
    }

    static String excerptOf(String text, int start, int end) {
        if (text == null || text.length() == 0) return "";
        int from = Math.max(0, Math.min(start, text.length()));
        int to = Math.min(text.length(), Math.max(end, from));
        StringBuilder sb = new StringBuilder();
        for (int i = from; i < to && sb.length() < EXCERPT_LEN; i++) {
            char c = text.charAt(i);
            if (c == '\n' || c == '\r' || c == '\t') {
                if (sb.length() > 0 && sb.charAt(sb.length() - 1) != ' ') sb.append(' ');
            } else {
                sb.append(c);
            }
        }
        return sb.toString().trim();
    }

    private static long longOf(Object v) {
        if (v instanceof Number) return ((Number) v).longValue();
        try {
            return Long.parseLong(String.valueOf(v));
        } catch (Exception e) {
            return 0L;
        }
    }

    private Highlights() {
    }
}
