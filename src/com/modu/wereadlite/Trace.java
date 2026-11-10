package com.modu.wereadlite;

import android.content.Context;
import android.util.Log;

import java.io.File;
import java.io.FileOutputStream;

/** 运行期诊断日志：同时写 logcat 和外部私有目录，便于用 adb 拉取分析。 */
public final class Trace {

    private static File dir;

    static void init(Context ctx) {
        try {
            dir = ctx.getExternalFilesDir(null);
        } catch (Throwable t) {
            dir = null;
        }
    }

    public static void i(String msg) {
        Log.i("WereadLite", msg);
        append("trace.log", msg + "\n");
    }

    public static void dump(String name, String content) {
        String safe = name.replaceAll("[^A-Za-z0-9._-]", "_");
        if (dir == null) return;
        try {
            if (!dir.exists()) dir.mkdirs();
            File f = new File(dir, safe);
            if (f.exists()) f.delete();
            FileOutputStream out = new FileOutputStream(f, true);
            out.write(content.getBytes("UTF-8"));
            out.close();
        } catch (Throwable ignored) {
        }
    }

    private static void append(String name, String text) {
        if (dir == null) return;
        try {
            if (!dir.exists()) dir.mkdirs();
            File f = new File(dir, name);
            if (f.length() > 512 * 1024) f.delete();
            FileOutputStream out = new FileOutputStream(f, true);
            out.write(text.getBytes("UTF-8"));
            out.close();
        } catch (Throwable ignored) {
        }
    }
}
