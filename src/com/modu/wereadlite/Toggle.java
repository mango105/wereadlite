package com.modu.wereadlite;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.view.MotionEvent;
import android.view.View;

/** 墨水屏开关：开=黑底白钮，关=白底灰框。 */
final class Toggle extends View {

    interface OnChange {
        void onChange(boolean checked);
    }

    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF rect = new RectF();
    private boolean checked;
    private OnChange listener;

    Toggle(Context c) {
        super(c);
    }

    void setChecked(boolean value) {
        checked = value;
        invalidate();
    }

    boolean isChecked() {
        return checked;
    }

    void setOnChange(OnChange l) {
        listener = l;
    }

    private int dp(float v) {
        return Ui.dp(getContext(), v);
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        setMeasuredDimension(dp(46), dp(26));
    }

    @Override
    protected void onDraw(Canvas canvas) {
        int w = getWidth();
        int h = getHeight();
        float r = h / 2f;

        paint.setStyle(Paint.Style.FILL);
        paint.setColor(checked ? Ui.INK : Color.WHITE);
        rect.set(dp(1), dp(1), w - dp(1), h - dp(1));
        canvas.drawRoundRect(rect, r, r, paint);
        if (!checked) {
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(Math.max(1f, dp(0.8f)));
            paint.setColor(0xFFCFCFCF);
            canvas.drawRoundRect(rect, r, r, paint);
        }
        float cx = checked ? w - r : r;
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(checked ? Color.WHITE : 0xFFF0F0F0);
        canvas.drawCircle(cx, h / 2f, r - dp(3), paint);
        if (!checked) {
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(Math.max(1f, dp(0.6f)));
            paint.setColor(0xFFD5D5D5);
            canvas.drawCircle(cx, h / 2f, r - dp(3), paint);
        }
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        if (event.getAction() == MotionEvent.ACTION_UP) {
            checked = !checked;
            invalidate();
            if (listener != null) listener.onChange(checked);
            performClick();
        }
        return true;
    }

    @Override
    public boolean performClick() {
        return super.performClick();
    }
}
