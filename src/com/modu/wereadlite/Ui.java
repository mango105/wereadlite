package com.modu.wereadlite;

import android.content.Context;
import android.graphics.drawable.GradientDrawable;
import android.util.TypedValue;
import android.widget.TextView;

/** 极简配色与控件工厂：墨水屏只有黑白灰，不用任何主题色。 */
final class Ui {

    static final int INK = 0xFF111111;
    static final int SUB = 0xFF555555;
    static final int LIGHT = 0xFF9A9A9A;
    static final int LINE = 0xFFDCDCDC;
    static final int TILE = 0xFFF5F5F5;
    static final String APP_NAME = "微读lite";

    private Ui() {
    }

    static int dp(Context c, float v) {
        return (int) (v * c.getResources().getDisplayMetrics().density + 0.5f);
    }

    static GradientDrawable box(int fill, int strokeColor, int strokeWidth, float radius) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(fill);
        if (strokeWidth > 0) d.setStroke(strokeWidth, strokeColor);
        d.setCornerRadius(radius);
        return d;
    }

    static GradientDrawable oval(int fill) {
        GradientDrawable d = new GradientDrawable();
        d.setShape(GradientDrawable.OVAL);
        d.setColor(fill);
        return d;
    }

    static TextView text(Context c, CharSequence s, float sp, int color) {
        TextView t = new TextView(c);
        t.setText(s);
        t.setTextColor(color);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setIncludeFontPadding(false);
        return t;
    }

    /** 底栏和关于页的版本号取自包信息，免得和 manifest 里的 versionName 各写一份。 */
    static String version(Context c) {
        String v = "";
        try {
            v = c.getPackageManager().getPackageInfo(c.getPackageName(), 0).versionName;
        } catch (Exception e) {
            v = "";
        }
        if (v == null) v = "";
        return v.length() == 0 ? APP_NAME : APP_NAME + " " + v;
    }

    /** 行距百分比转成倍数显示（108 -> 1.08）。手拼，不用 String.format：部分地区设置下它会给出逗号小数点。 */
    static String lineSpacing(int pct) {
        return (pct / 100) + "." + String.valueOf(100 + pct % 100).substring(1);
    }

    /** 阅读时长：云端给的是秒。不足一分钟不显示 0，免得看着像没读。 */
    static String duration(int seconds) {
        if (seconds < 0) return "";
        if (seconds < 60) return "不足 1 分钟";
        int m = seconds / 60;
        if (m < 60) return m + " 分钟";
        int h = m / 60;
        int rest = m % 60;
        return rest == 0 ? h + " 小时" : h + " 小时 " + rest + " 分";
    }
}
