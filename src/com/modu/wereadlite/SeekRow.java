package com.modu.wereadlite;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewParent;

/**
 * 通用滑杆：字号 / 边距 / 行距 / 章内进度共用。
 * 拖动过程中通过 onValue 即时回调（用于实时预览），松手时 onCommit 落盘。
 * 视图本身只管画手柄和换算手指位置，重活交给回调，这样手柄能跟手、正文按节奏刷新。
 */
final class SeekRow extends View {

    interface Listener {
        /** 拖动中/点击时即时触发，用于实时预览。 */
        void onValue(int value);

        /** 松手后触发，用于持久化。 */
        void onCommit(int value);
    }

    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF track = new RectF();
    private int min = 14;
    private int max = 34;
    private int step = 1;
    private int value = 22;
    private String edgeLeft = "A";
    private String edgeRight = "A";
    private float edgeLeftSp = 11f;
    private float edgeRightSp = 16f;
    private Listener listener;
    private boolean tapNudge;
    private float downX;
    private boolean dragging;

    SeekRow(Context c) {
        super(c);
    }

    void setRange(int min, int max) {
        this.min = min;
        this.max = max;
    }

    /** 一档代表多少：0.04 倍行距、2dp 边距……档位越少越好停准。 */
    void setStep(int step) {
        this.step = step < 1 ? 1 : step;
    }

    /**
     * 轻点只走一档，而不是跳到手指点的那个位置。
     * 墨水屏上点不准，「跳到点位」会让字号一按就翻一倍，看起来像滑杆失控。
     */
    void setTapNudge(boolean on) {
        tapNudge = on;
    }

    void setValue(int v) {
        int n = Math.max(min, Math.min(max, v));
        if (n == value) return;
        value = n;
        invalidate();
    }

    int value() {
        return value;
    }

    /** 两端刻度文字，例如字号两端都是 A、边距是「窄/宽」、行距是「紧/松」。 */
    void setEdges(String left, float leftSp, String right, float rightSp) {
        edgeLeft = left;
        edgeLeftSp = leftSp;
        edgeRight = right;
        edgeRightSp = rightSp;
    }

    void setListener(Listener l) {
        listener = l;
    }

    private int dp(float v) {
        return (int) (v * getContext().getResources().getDisplayMetrics().density + 0.5f);
    }

    /**
     * 轨道左右端点。绘制和触摸换算必须走同一套坐标，
     * 以前窄屏时两边各退让一次，手柄画在 A 处、手指算出 B 处，滑起来就是拧着走。
     * 两端还要给刻度字留出位置，否则拖到头时手柄正好压在 A/宽 上。
     */
    private float left() {
        return dp(wide() ? 38 : 16);
    }

    private float right() {
        return getWidth() - dp(wide() ? 38 : 16);
    }

    private boolean wide() {
        return getWidth() - dp(60) >= dp(80);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        int w = getWidth();
        int h = getHeight();
        if (w <= 0 || h <= 0) return;
        float cy = h / 2f;
        float left = left();
        float right = right();

        // 两端刻度
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(0xFFAAAAAA);
        paint.setTextSize(dp(edgeLeftSp));
        paint.setTextAlign(Paint.Align.LEFT);
        canvas.drawText(edgeLeft, dp(2), cy + dp(edgeLeftSp * 0.36f), paint);
        paint.setTextSize(dp(edgeRightSp));
        paint.setTextAlign(Paint.Align.RIGHT);
        canvas.drawText(edgeRight, w - dp(2), cy + dp(edgeRightSp * 0.36f), paint);
        paint.setTextAlign(Paint.Align.LEFT);

        // 轨道
        float thick = dp(2);
        track.set(left, cy - thick / 2f, right, cy + thick / 2f);
        paint.setColor(0xFFDDDDDD);
        canvas.drawRoundRect(track, thick, thick, paint);

        float ratio = (value - min) / (float) Math.max(1, max - min);
        float x = left + (right - left) * ratio;
        paint.setColor(0xFF3A3A3A);
        canvas.drawRoundRect(new RectF(left, cy - thick / 2f, x, cy + thick / 2f),
                thick, thick, paint);

        // 手柄
        paint.setColor(Color.WHITE);
        canvas.drawCircle(x, cy, dp(10), paint);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(Math.max(1f, dp(1.8f)));
        paint.setColor(0xFF3A3A3A);
        canvas.drawCircle(x, cy, dp(10), paint);
        paint.setStyle(Paint.Style.FILL);
        canvas.drawCircle(x, cy, dp(5), paint);
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        int action = event.getAction();
        if (action == MotionEvent.ACTION_DOWN) {
            ViewParent p = getParent();
            if (p != null) p.requestDisallowInterceptTouchEvent(true);
            downX = event.getX();
            dragging = false;
            if (!tapNudge) moveTo(event.getX());
            return true;
        }
        if (action == MotionEvent.ACTION_MOVE) {
            if (Math.abs(event.getX() - downX) > dp(6)) dragging = true;
            if (dragging || !tapNudge) moveTo(event.getX());
            return true;
        }
        if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
            if (action == MotionEvent.ACTION_UP) {
                if (dragging || !tapNudge) moveTo(event.getX());
                else tap(event.getX());
                if (listener != null) listener.onCommit(value);
                performClick();
            }
            dragging = false;
            return true;
        }
        return super.onTouchEvent(event);
    }

    /** 手指位置换算成刻度值；只在数值真的跨档时才回调，避免同一个值反复触发。 */
    private void moveTo(float px) {
        float ratio = (px - left()) / Math.max(1f, right() - left());
        set(Detent.fromRatio(min, max, step, ratio));
    }

    /** 轻点：往点的这一侧走一档，落在手柄上就当没点到。 */
    private void tap(float px) {
        float knob = left() + (right() - left()) * ((value - min) / (float) Math.max(1, max - min));
        if (Math.abs(px - knob) < dp(12)) return;
        set(Detent.nudge(min, max, step, value, px > knob ? 1 : -1));
    }

    private void set(int v) {
        if (v == value) return;
        value = v;
        invalidate();
        if (listener != null) listener.onValue(v);
    }

    @Override
    public boolean performClick() {
        return super.performClick();
    }
}
