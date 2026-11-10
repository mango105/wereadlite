package com.modu.wereadlite;

/**
 * 滑杆档位换算：把任意值吸附到「最小值 + n × 步长」的刻度上。
 * 单独拆出来是因为这套取整在边界上最容易出错（拖不到顶、轻点越界），而它能不用真机就跑回归。
 */
final class Detent {

    private Detent() {}

    /** 吸附到最近的档位；顶档不足一整步时直接给最大值，保证能拖到头。 */
    static int snap(int min, int max, int step, int v) {
        if (max <= min) return clamp(min, max, v);
        if (step <= 1) return clamp(min, max, v);
        if (v < min) v = min;
        if (v > max) v = max;
        int s = min + Math.round((v - min) / (float) step) * step;
        return clamp(min, max, s);
    }

    /** 手指位置（0~1）换算成档位值。 */
    static int fromRatio(int min, int max, int step, float ratio) {
        if (ratio < 0f) ratio = 0f;
        if (ratio > 1f) ratio = 1f;
        return snap(min, max, step, min + Math.round(ratio * (max - min)));
    }

    /** 轻点微调：往 dir（>0 变大）走一整档，已经在端点就停住。 */
    static int nudge(int min, int max, int step, int v, int dir) {
        if (max <= min) return min;
        int s = snap(min, max, step, v);
        int n = step <= 1 ? (dir > 0 ? s + 1 : s - 1) : (dir > 0 ? s + step : s - step);
        return clamp(min, max, n);
    }

    private static int clamp(int min, int max, int v) {
        if (v < min) return min;
        return v > max ? max : v;
    }
}
