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
}
