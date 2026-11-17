package com.modu.wereadlite;

import android.content.Context;
import android.content.SharedPreferences;

/** 本地持久化。老 ROM 上 apply() 会丢，统一用 commit()。 */
public final class Store {

    private static final String NAME = "wereadlite";
    private final SharedPreferences sp;

    public Store(Context ctx) {
        sp = ctx.getSharedPreferences(NAME, Context.MODE_PRIVATE);
        Trace.init(ctx);
    }

    private String get(String k) {
        return sp.getString(k, "");
    }

    private void put(String k, String v) {
        sp.edit().putString(k, v == null ? "" : v).commit();
    }

    public String cookies() {
        return get("cookies");
    }

    public void setCookies(String v) {
        put("cookies", v);
    }

    public String vid() {
        return get("vid");
    }

    public String skey() {
        return get("skey");
    }

    public boolean loggedIn() {
        return cookies().length() > 0 && vid().length() > 0;
    }

    public void saveSession(String cookies, String vid, String skey) {
        sp.edit().putString("cookies", cookies).putString("vid", vid)
                .putString("skey", skey).commit();
    }

    public void clearSession() {
        sp.edit().putString("cookies", "").putString("vid", "").putString("skey", "").commit();
    }

    public boolean compat() {
        return sp.getBoolean("compat", false);
    }

    public void setCompat(boolean v) {
        sp.edit().putBoolean("compat", v).commit();
    }

    public int fontSize() {
        return sp.getInt("fontSize", 22);
    }

    public void setFontSize(int v) {
        sp.edit().putInt("fontSize", v).commit();
    }

    /** 段落首行缩进，半角格：4 格 = 2 个汉字。老版本没有这一项，升级后默认两字缩进。 */
    public int indentCells() {
        return sp.getInt("indentCells", 4);
    }

    public void setIndentCells(int v) {
        sp.edit().putInt("indentCells", v).commit();
    }

    /** 行距百分比，116 = 1.16 倍。旧版本存的是 0/1/2 档位，这里做一次迁移。 */
    public int linePct() {
        if (sp.contains("linePct")) return sp.getInt("linePct", 116);
        int old = sp.getInt("lineLevel", 0);
        return old == 0 ? 116 : (old == 1 ? 136 : 158);
    }

    public void setLinePct(int v) {
        sp.edit().putInt("linePct", v).commit();
    }

    /** 左右边距（dp）。旧版本同样是 0/1/2 档位。 */
    public int marginDp() {
        if (sp.contains("marginDp")) return sp.getInt("marginDp", 14);
        int old = sp.getInt("marginLevel", 0);
        return old == 0 ? 14 : (old == 1 ? 20 : 28);
    }

    public void setMarginDp(int v) {
        sp.edit().putInt("marginDp", v).commit();
    }

    public boolean volumePaging() {
        return sp.getBoolean("volumePaging", true);
    }

    public void setVolumePaging(boolean v) {
        sp.edit().putBoolean("volumePaging", v).commit();
    }

    /** 页数与百分比：true 整本书（默认），false 只看本章。 */
    public boolean progressWholeBook() {
        return sp.getBoolean("progressWholeBook", true);
    }

    public void setProgressWholeBook(boolean v) {
        sp.edit().putBoolean("progressWholeBook", v).commit();
    }

    public boolean keepAwake() {
        return sp.getBoolean("keepAwake", false);
    }

    public void setKeepAwake(boolean v) {
        sp.edit().putBoolean("keepAwake", v).commit();
    }

    /** 每本书上次读到的位置，存成 "章节下标|章内偏移|写入毫秒"。 */
    public void setLastPos(String bookId, int index, int offset) {
        sp.edit().putString("lp_" + bookId, index + "|" + offset + "|" + System.currentTimeMillis())
                .commit();
    }

    public int lastIndex(String bookId) {
        return Store.parseInt(at(posParts(bookId), 0));
    }

    public int lastOffset(String bookId) {
        return Store.parseInt(at(posParts(bookId), 1));
    }

    /** 本地这条位置是什么时候写的；0 表示这本书本地没记过。 */
    public long lastPosAt(String bookId) {
        return Store.parseLong(at(posParts(bookId), 2));
    }

    private static String at(String[] parts, int i) {
        return i < parts.length ? parts[i] : "";
    }

    private String[] posParts(String bookId) {
        String s = sp.getString("lp_" + bookId, "");
        if (s.length() == 0) {
            // 旧版本只有一个全局槽位，升级后第一次打开也要接上原来的位置
            String[] legacy = get("lastPos").split("\\|", -1);
            if (legacy.length >= 3 && legacy[0].equals(bookId)) s = legacy[1] + "|" + legacy[2];
        }
        return s.split("\\|", -1);
    }

    public static int parseInt(String v) {
        try {
            return Integer.parseInt(v.trim());
        } catch (Exception e) {
            return 0;
        }
    }

    public static long parseLong(String v) {
        try {
            return Long.parseLong(v.trim());
        } catch (Exception e) {
            return 0L;
        }
    }

    /** 云端记的阅读位置，原样存 Resume.encode 的结果。 */
    public String mark(String bookId) {
        return get("mk_" + bookId);
    }

    /** 一次同步几十本书的位置，合并成一个编辑器落盘。 */
    public void setMarks(java.util.Map<String, String> marks) {
        if (marks == null || marks.isEmpty()) return;
        SharedPreferences.Editor ed = sp.edit();
        for (java.util.Map.Entry<String, String> e : marks.entrySet()) {
            String v = e.getValue();
            if (v != null && v.length() > 0) ed.putString("mk_" + e.getKey(), v);
        }
        ed.commit();
    }

    /** E-Ink 客户端授权凭证（JSON：vid/accessToken/refreshToken/deviceId）。 */
    public String einkCred() {
        return get("einkCred");
    }

    public void setEinkCred(String v) {
        put("einkCred", v);
    }

    public long syncedAt() {
        return sp.getLong("syncedAt", 0L);
    }

    public void setSyncedAt(long v) {
        sp.edit().putLong("syncedAt", v).commit();
    }

    // ---------------- 书签 ----------------

    /** 每本书的书签，Bookmarks.encode 出来的 JSON 数组。 */
    public String bookmarks(String bookId) {
        return get("bm_" + bookId);
    }

    public void setBookmarks(String bookId, String json) {
        put("bm_" + bookId, json);
    }

    /** 全量枚举：bookId -> 该书书签 JSON，导出时用。 */
    public java.util.Map<String, String> allBookmarks() {
        java.util.Map<String, String> out = new java.util.LinkedHashMap<String, String>();
        for (java.util.Map.Entry<String, ?> e : sp.getAll().entrySet()) {
            String k = e.getKey();
            if (!k.startsWith("bm_") || !(e.getValue() instanceof String)) continue;
            String v = (String) e.getValue();
            if (v.length() > 0) out.put(k.substring(3), v);
        }
        return out;
    }

    /** 导入时一次写多本，合并成一个编辑器。 */
    public void setAllBookmarks(java.util.Map<String, String> byBook) {
        if (byBook == null || byBook.isEmpty()) return;
        SharedPreferences.Editor ed = sp.edit();
        for (java.util.Map.Entry<String, String> e : byBook.entrySet()) {
            String v = e.getValue();
            if (v != null) ed.putString("bm_" + e.getKey(), v);
        }
        ed.commit();
    }

    /** 「我」页显示的总条数。 */
    public int bookmarkCount() {
        int n = 0;
        for (java.util.Map.Entry<String, String> e : allBookmarks().entrySet()) {
            n += Bookmarks.decode(e.getValue()).size();
        }
        return n;
    }

    /** 每本书的累计阅读秒数，来自书架同步里的 bookProgress；没同步到过就是 -1。 */
    public int readTime(String bookId) {
        return sp.getInt("rt_" + bookId, -1);
    }

    /** 上报成功的那一段直接累上，阅读页的「本书已读」不用等下一次同步才动。 */
    public void addReadTime(String bookId, int seconds) {
        if (seconds <= 0) return;
        int base = sp.getInt("rt_" + bookId, 0);
        sp.edit().putInt("rt_" + bookId, Math.max(0, base) + seconds).commit();
    }

    /** 一次同步要写几十本书的时长，合并成一个编辑器，免得落盘几十次。 */
    public void setReadTimes(java.util.Map<String, Integer> times) {
        if (times == null || times.isEmpty()) return;
        SharedPreferences.Editor ed = sp.edit();
        for (java.util.Map.Entry<String, Integer> e : times.entrySet()) {
            Integer v = e.getValue();
            if (v != null && v.intValue() >= 0) ed.putInt("rt_" + e.getKey(), v.intValue());
        }
        ed.commit();
    }

    // ---------------- 整包备份 / 恢复 ----------------

    /** 导出需要备份的偏好：进度、书签、设置。会话/授权凭证不导出。 */
    public java.util.Map<String, String> exportWantedPrefs() {
        java.util.Map<String, String> out = new java.util.LinkedHashMap<String, String>();
        for (java.util.Map.Entry<String, ?> e : sp.getAll().entrySet()) {
            String k = e.getKey();
            if (!Backup.wantedPref(k) || e.getValue() == null) continue;
            out.put(k, String.valueOf(e.getValue()));
        }
        return out;
    }

    /**
     * 合并导入的偏好，返回实际应用了多少项。
     * - 阅读位置 lp_ / mk_：谁的时间戳新用谁；
     * - 阅读时长 rt_：取较大值（累计时长不该倒退）；
     * - 书签 bm_：用 Bookmarks.merge 按位置合并去重，不覆盖本地已有的；
     * - 其余设置：直接覆盖（用户主动导入就是想恢复这套设置）。
     */
    public int mergeImportedPrefs(java.util.Map<String, String> incoming) {
        if (incoming == null || incoming.isEmpty()) return 0;
        SharedPreferences.Editor ed = sp.edit();
        int n = 0;
        for (java.util.Map.Entry<String, String> e : incoming.entrySet()) {
            String k = e.getKey();
            String v = e.getValue();
            if (!Backup.wantedPref(k) || v == null) continue;
            if (k.startsWith("lp_")) {
                if (newerPos(sp.getString(k, ""), v)) { ed.putString(k, v); n++; }
            } else if (k.startsWith("mk_")) {
                // mk_ 是 Resume 编码串，没有独立时间戳，按字符串不同就取导入的（同步结果）
                if (!v.equals(sp.getString(k, ""))) { ed.putString(k, v); n++; }
            } else if (k.startsWith("rt_")) {
                int cur = sp.getInt(k, -1);
                int val = Store.parseInt(v);
                if (val > cur) { ed.putInt(k, val); n++; }
            } else if (k.startsWith("bm_")) {
                String bookId = k.substring(3);
                java.util.List<Bookmarks.Item> merged = Bookmarks.merge(
                        Bookmarks.decode(sp.getString(k, "")), Bookmarks.decode(v));
                String enc = Bookmarks.encode(merged);
                if (!enc.equals(sp.getString(k, ""))) { ed.putString(k, enc); n++; }
            } else {
                // 设置项：值可能是"true"/数字，按原类型写回
                Object cur = sp.getAll().get(k);
                if (cur instanceof Boolean) ed.putBoolean(k, Boolean.parseBoolean(v));
                else if (cur instanceof Integer) ed.putInt(k, Store.parseInt(v));
                else ed.putString(k, v);
                n++;
            }
        }
        ed.commit();
        return n;
    }

    /** 比较两个 "idx|off|毫秒" 位置串，b 的时间戳更新则返回 true。 */
    private static boolean newerPos(String a, String b) {
        long ta = Store.parseLong(posTime(a));
        long tb = Store.parseLong(posTime(b));
        return tb > ta;
    }

    private static String posTime(String s) {
        String[] parts = s == null ? new String[0] : s.split("\\|", -1);
        return parts.length >= 3 ? parts[2] : "";
    }
}
