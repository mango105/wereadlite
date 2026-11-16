package com.modu.wereadlite;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

/**
 * 运行时存储权限。
 *
 * 本项目用 android-17 桩编译，打包后还要用 api_level.py 扫 dex 里每一个框架方法引用，
 * 直接调 API 23 的 checkSelfPermission / requestPermissions 既编译不过，也会被扫出来。
 * 所以高版本 API 一律反射调用，只在 SDK_INT >= 23 时执行；低版本仍走清单权限模型。
 */
final class Perms {

    static final int REQ_STORAGE = 0x71;
    private static final String READ_STORAGE = "android.permission.READ_EXTERNAL_STORAGE";

    private Perms() {
    }

    /** 反射读 SDK_INT 而不是写常量引用：避免编译期桩把静态常量内联成 0。 */
    private static int sdk() {
        try {
            Field f = Class.forName("android.os.Build$VERSION").getField("SDK_INT");
            return f.getInt(null);
        } catch (Throwable t) {
            return 0;
        }
    }

    static boolean runtimeModel() {
        return sdk() >= 23;
    }

    static boolean granted(Context c) {
        if (!runtimeModel()) return true;
        try {
            Method m = Context.class.getMethod("checkSelfPermission", String.class);
            Object v = m.invoke(c, READ_STORAGE);
            return v instanceof Integer && ((Integer) v).intValue() == 0;
        } catch (Throwable t) {
            // 反射拿不到就按已授权处理，后面 File.list() 会给出真实结果和提示
            return true;
        }
    }

    static void request(Activity a) {
        if (!runtimeModel() || granted(a)) return;
        try {
            Method m = Activity.class.getMethod("requestPermissions",
                    String[].class, int.class);
            // 必须显式包成 Object[]，否则 String[] 会被 invoke 的 varargs 拆开
            m.invoke(a, new Object[]{new String[]{READ_STORAGE}, Integer.valueOf(REQ_STORAGE)});
        } catch (Throwable ignored) {
        }
    }

    /** 用户勾了「不再询问」后只能去设置页手动打开。 */
    static void openSettings(Context c) {
        try {
            Intent i = new Intent("android.settings.APPLICATION_DETAILS_SETTINGS");
            i.setData(Uri.parse("package:" + c.getPackageName()));
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            c.startActivity(i);
        } catch (Exception ignored) {
        }
    }
}
