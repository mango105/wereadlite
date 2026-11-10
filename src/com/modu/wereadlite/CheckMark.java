package com.modu.wereadlite;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.view.View;

/** 选中角标：未选为空心圆，选中为实心黑圆 + 白勾。 */
final class CheckMark extends View {

    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path path = new Path();
    private boolean checked;

    CheckMark(Context c) {
        super(c);
    }

    void setChecked(boolean value) {
        if (checked != value) {
            checked = value;
            invalidate();
        }
    }

    @Override
    protected void onDraw(Canvas canvas) {
        int w = getWidth();
        int h = getHeight();
        float cx = w / 2f;
        float cy = h / 2f;
        float r = Math.min(w, h) / 2f - 1f;
        if (r <= 1f) return;

        if (checked) {
            paint.setStyle(Paint.Style.FILL);
            paint.setColor(Color.WHITE);
            canvas.drawCircle(cx, cy, r, paint);
            paint.setColor(0xFF1A1A1A);
            canvas.drawCircle(cx, cy, r - 1f, paint);

            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(r * 0.26f);
            paint.setStrokeCap(Paint.Cap.ROUND);
            paint.setStrokeJoin(Paint.Join.ROUND);
            paint.setColor(Color.WHITE);
            path.reset();
            path.moveTo(cx - r * 0.42f, cy + r * 0.02f);
            path.lineTo(cx - r * 0.10f, cy + r * 0.34f);
            path.lineTo(cx + r * 0.46f, cy - r * 0.34f);
            canvas.drawPath(path, paint);
        } else {
            paint.setStyle(Paint.Style.FILL);
            paint.setColor(0xF0FFFFFF);
            canvas.drawCircle(cx, cy, r, paint);
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(Math.max(1f, r * 0.12f));
            paint.setColor(0xFFAAAAAA);
            canvas.drawCircle(cx, cy, r - 1f, paint);
        }
    }
}
