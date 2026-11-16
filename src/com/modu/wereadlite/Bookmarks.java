package com.modu.wereadlite;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 书签数据层：纯 Java，不碰 Android，解析回归里可以直接跑。
 *
 * 定位用「章节 uid + 章节下标 + 章内字符偏移」，和阅读进度上报、首行缩进共用
 * 同一套原文偏移；不往正文里插字符，跨设备导入后位置依旧对得上。
 * uid 对不上（本地书 / 目录调整）时退化用下标，偏移越界由阅读页夹回正文范围。
 */
final class Bookmarks {

    /** 同一章、偏移差在 24 字以内视为同一条，避免两台设备差几个字就重复。 */
    static final int TOLERANCE = 24;
    /** 每本书最多留 300 条，防止老机器上 SharedPreferences 越写越大。 */
    static final int MAX_PER_BOOK = 300;
    static final int EXCERPT_LEN = 42;

    static final class Item {
        String uid = "";
        String chapter = "";
        int idx;
        int off;
        String excerpt = "";
        long at;

        Item() {
        }

        Item(String uid, String chapter, int idx, int off, String excerpt, long at) {
            this.uid = uid == null ? "" : uid;
            this.chapter = chapter == null ? "" : chapter;
            this.idx = idx;
            this.off = Math.max(0, off);
            this.excerpt = excerpt == null ? "" : excerpt;
            this.at = at;
        }
    }

    /** 单本书编码成 JSON 数组，存进 Store。 */
    static String encode(List<Item> items) {
        StringBuilder sb = new StringBuilder("[");
        if (items != null) {
            for (int i = 0; i < items.size(); i++) {
                if (i > 0) sb.append(',');
                Item it = items.get(i);
                sb.append("{\"u\":").append(Json.quote(it.uid))
                        .append(",\"c\":").append(Json.quote(it.chapter))
                        .append(",\"i\":").append(it.idx)
                        .append(",\"o\":").append(it.off)
                        .append(",\"e\":").append(Json.quote(it.excerpt))
                        .append(",\"t\":").append(it.at).append('}');
            }
        }
        return sb.append(']').toString();
    }

    static List<Item> decode(String raw) {
        if (raw == null || raw.length() == 0) return new ArrayList<Item>();
        return fromRows(Json.asList(Json.parse(raw)));
    }

    private static List<Item> fromRows(List<Object> rows) {
        List<Item> out = new ArrayList<Item>();
        for (Object o : rows) {
            Map<String, Object> m = Json.asMap(o);
            if (m.isEmpty()) continue;
            out.add(new Item(
                    Json.str(m, "u"),
                    Json.str(m, "c"),
                    Json.num(m, "i"),
                    Json.num(m, "o"),
                    Json.str(m, "e"),
                    longOf(m.get("t"))));
        }
        return out;
    }

    /** 导出整个书架：{"v":1,"at":...,"books":{"<bookId>":[...]}} */
    static String exportAll(Map<String, String> byBook) {
        StringBuilder sb = new StringBuilder("{\"v\":1,\"at\":")
                .append(System.currentTimeMillis()).append(",\"books\":{");
        boolean first = true;
        if (byBook != null) {
            for (Map.Entry<String, String> e : byBook.entrySet()) {
                List<Item> items = decode(e.getValue());
                if (items.isEmpty()) continue;
                if (!first) sb.append(',');
                first = false;
                sb.append(Json.quote(e.getKey())).append(':').append(encode(items));
            }
        }
        return sb.append("}}").toString();
    }

    /** 解析导出文件，返回 bookId -> 该书书签 JSON 数组；格式不对返回空 map。 */
    static Map<String, String> parseAll(String text) {
        Map<String, String> out = new LinkedHashMap<String, String>();
        if (text == null || text.trim().length() == 0) return out;
        Object root = Json.parse(text);
        Map<String, Object> books = Json.map(root, "books");
        for (Map.Entry<String, Object> e : books.entrySet()) {
            List<Item> items = fromRows(Json.asList(e.getValue()));
            if (items.isEmpty()) continue;
            out.put(e.getKey(), encode(items));
        }
        return out;
    }

    /**
     * 合并本地与导入的书签：同章同位置去重，保留较新那条的摘录，按章节顺序排好，
     * 最后截断到 MAX_PER_BOOK。只增不覆盖，导入不会让本地书签丢失。
     */
    static List<Item> merge(List<Item> local, List<Item> incoming) {
        List<Item> out = new ArrayList<Item>();
        if (local != null) out.addAll(local);
        if (incoming != null) {
            for (Item add : incoming) {
                int hit = indexOf(out, add);
                if (hit < 0) {
                    out.add(add);
                    continue;
                }
                Item cur = out.get(hit);
                String excerpt = add.excerpt.length() > 0 ? add.excerpt : cur.excerpt;
                out.set(hit, new Item(cur.uid, cur.chapter, cur.idx, cur.off,
                        excerpt, Math.max(cur.at, add.at)));
            }
        }
        Collections.sort(out, new Comparator<Item>() {
            @Override
            public int compare(Item a, Item b) {
                if (a.idx != b.idx) return a.idx < b.idx ? -1 : 1;
                if (a.off != b.off) return a.off < b.off ? -1 : 1;
                return 0;
            }
        });
        while (out.size() > MAX_PER_BOOK) out.remove(0);
        return out;
    }

    static int indexOf(List<Item> items, String uid, int idx, int off) {
        return indexOf(items, new Item(uid, "", idx, off, "", 0L));
    }

    private static int indexOf(List<Item> items, Item probe) {
        if (items == null) return -1;
        for (int i = 0; i < items.size(); i++) {
            if (same(items.get(i), probe)) return i;
        }
        return -1;
    }

    /** 从列表里删掉与 it 同位置的书签，返回是否删掉。 */
    static boolean remove(List<Item> items, Item it) {
        int hit = indexOf(items, it);
        if (hit >= 0) {
            items.remove(hit);
            return true;
        }
        return false;
    }

    /** 当前位置已在书签里就删掉返回 false，否则加一条返回 true。 */
    static boolean toggle(List<Item> items, Item it) {
        int hit = indexOf(items, it);
        if (hit >= 0) {
            items.remove(hit);
            return false;
        }
        items.add(it);
        return true;
    }

    static boolean same(Item a, Item b) {
        if (a == null || b == null) return false;
        boolean chapter = a.uid.length() > 0 && b.uid.length() > 0
                ? a.uid.equals(b.uid) : a.idx == b.idx;
        return chapter && Math.abs(a.off - b.off) <= TOLERANCE;
    }

    /** 从当前偏移往后取一段当摘录，压平换行与空白，别把列表撑成多行。 */
    static String excerptOf(String text, int off) {
        if (text == null || text.length() == 0) return "";
        int from = Math.max(0, Math.min(off, text.length()));
        int to = Math.min(text.length(), from + EXCERPT_LEN * 2);
        StringBuilder sb = new StringBuilder();
        for (int i = from; i < to && sb.length() < EXCERPT_LEN; i++) {
            char c = text.charAt(i);
            if (c == '\n' || c == '\r' || c == '\t' || c == ' ') {
                if (sb.length() > 0 && sb.charAt(sb.length() - 1) != ' ') sb.append(' ');
            } else {
                sb.append(c);
            }
        }
        return sb.toString().trim();
    }

    /** 列表里的时间只显示月日时分，固定 Locale 避免地区差异。 */
    static String timeLabel(long at) {
        if (at <= 0) return "";
        try {
            return new java.text.SimpleDateFormat("MM-dd HH:mm", java.util.Locale.CHINA)
                    .format(new java.util.Date(at));
        } catch (Exception e) {
            return "";
        }
    }

    private static long longOf(Object v) {
        if (v instanceof Number) return ((Number) v).longValue();
        try {
            return Long.parseLong(String.valueOf(v));
        } catch (Exception e) {
            return 0L;
        }
    }

    private Bookmarks() {
    }
}
