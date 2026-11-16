package com.modu.wereadlite;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.view.View;

/** 纯 Canvas 画的线性图标，避免引入任何位图资源（小包 + 墨水屏锐利）。 */
final class NavIcon extends View {

    static final int SHELF = 1;
    static final int ME = 2;
    static final int LOCK = 3;
    static final int DOWNLOAD = 4;
    static final int FOLDER = 5;
    static final int TRASH = 6;
    static final int SLIDERS = 7;
    static final int SEARCH = 8;
    static final int COMPASS = 9;
    static final int UP = 10;
    static final int DOWN = 11;
    static final int PLUS = 12;
    static final int THOUGHT = 13;
    static final int BACK = 14;
    static final int HEART = 15;
    static final int CHAT = 16;
    static final int DOT = 17;
    static final int LIST = 18;
    static final int PROGRESS = 19;
    static final int LETTER_A = 20;
    static final int INFO = 21;
    static final int BOOKMARK = 22;
    static final int OPENBOOK = 23;

    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path path = new Path();
    private final RectF oval = new RectF();
    private int kind;
    private int ink = Ui.INK;

    NavIcon(Context c, int kind) {
        super(c);
        this.kind = kind;
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeCap(Paint.Cap.ROUND);
        paint.setStrokeJoin(Paint.Join.ROUND);
    }

    void setInk(int color) {
        if (ink != color) {
            ink = color;
            invalidate();
        }
    }

    /** 同一个位置换图形：菜单收起时是圆点，展开时是返回箭头。 */
    void setKind(int k) {
        if (k == kind) return;
        kind = k;
        invalidate();
    }

    private float unit() {
        return Math.min(getWidth(), getHeight()) / 24f;
    }

    @Override
    protected void onDraw(Canvas canvas) {
        int w = getWidth();
        int h = getHeight();
        if (w <= 0 || h <= 0) return;
        float u = unit();
        float ox = (w - 24f * u) / 2f;
        float oy = (h - 24f * u) / 2f;
        canvas.save();
        canvas.translate(ox, oy);
        canvas.scale(u, u);

        paint.setColor(ink);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(1.7f);
        path.reset();

        switch (kind) {
            case SHELF:
                canvas.drawRoundRect(new RectF(4f, 4f, 8.2f, 20f), 0.8f, 0.8f, paint);
                canvas.drawRoundRect(new RectF(9.6f, 5.4f, 13.8f, 20f), 0.8f, 0.8f, paint);
                path.moveTo(16.2f, 6.6f);
                path.lineTo(19.4f, 7.4f);
                path.lineTo(17.2f, 19.4f);
                path.lineTo(14.4f, 18.7f);
                canvas.drawPath(path, paint);
                break;
            case ME:
                paint.setStyle(Paint.Style.STROKE);
                canvas.drawCircle(12f, 8.2f, 3.6f, paint);
                path.moveTo(5.2f, 19.6f);
                path.quadTo(12f, 12.6f, 18.8f, 19.6f);
                canvas.drawPath(path, paint);
                break;
            case LOCK:
                canvas.drawRoundRect(new RectF(5.6f, 10.4f, 18.4f, 19.6f), 1.6f, 1.6f, paint);
                path.moveTo(8.4f, 10.4f);
                path.lineTo(8.4f, 8f);
                path.quadTo(12f, 2.6f, 15.6f, 8f);
                path.lineTo(15.6f, 10.4f);
                canvas.drawPath(path, paint);
                paint.setStyle(Paint.Style.FILL);
                canvas.drawCircle(12f, 14.6f, 1.1f, paint);
                break;
            case DOWNLOAD:
                path.moveTo(12f, 3.8f);
                path.lineTo(12f, 14.6f);
                canvas.drawPath(path, paint);
                path.reset();
                path.moveTo(7.8f, 10.8f);
                path.lineTo(12f, 15.2f);
                path.lineTo(16.2f, 10.8f);
                canvas.drawPath(path, paint);
                path.reset();
                path.moveTo(4.6f, 19.4f);
                path.lineTo(19.4f, 19.4f);
                canvas.drawPath(path, paint);
                break;
            case FOLDER:
                path.moveTo(3.8f, 7.2f);
                path.lineTo(9.6f, 7.2f);
                path.lineTo(11.4f, 9.6f);
                path.lineTo(20.2f, 9.6f);
                path.lineTo(20.2f, 19.2f);
                path.lineTo(3.8f, 19.2f);
                path.close();
                canvas.drawPath(path, paint);
                path.reset();
                path.moveTo(8.4f, 14.4f);
                path.lineTo(15.6f, 14.4f);
                canvas.drawPath(path, paint);
                break;
            case TRASH:
                path.moveTo(4.4f, 6.8f);
                path.lineTo(19.6f, 6.8f);
                canvas.drawPath(path, paint);
                path.reset();
                path.moveTo(9.2f, 6.8f);
                path.lineTo(9.2f, 4.6f);
                path.lineTo(14.8f, 4.6f);
                path.lineTo(14.8f, 6.8f);
                canvas.drawPath(path, paint);
                path.reset();
                path.moveTo(6.4f, 6.8f);
                path.lineTo(7.6f, 20f);
                path.lineTo(16.4f, 20f);
                path.lineTo(17.6f, 6.8f);
                canvas.drawPath(path, paint);
                break;
            case SLIDERS:
                paint.setStyle(Paint.Style.STROKE);
                canvas.drawLine(4.4f, 7f, 19.6f, 7f, paint);
                canvas.drawLine(4.4f, 12f, 19.6f, 12f, paint);
                canvas.drawLine(4.4f, 17f, 19.6f, 17f, paint);
                drawKnob(canvas, 9f, 7f);
                drawKnob(canvas, 15f, 12f);
                drawKnob(canvas, 7.5f, 17f);
                break;
            case SEARCH:
                canvas.drawCircle(10.6f, 10.6f, 5.8f, paint);
                canvas.drawLine(14.9f, 14.9f, 19.4f, 19.4f, paint);
                break;
            case COMPASS:
                canvas.drawCircle(12f, 12f, 8.2f, paint);
                path.moveTo(15.4f, 8.6f);
                path.lineTo(13.3f, 13.3f);
                path.lineTo(8.6f, 15.4f);
                path.lineTo(10.7f, 10.7f);
                path.close();
                canvas.drawPath(path, paint);
                break;
            case UP:
            case DOWN:
                float dir = kind == UP ? -1f : 1f;
                path.moveTo(6.6f, 12f - dir * 3.4f);
                path.lineTo(12f, 12f + dir * 3.4f);
                path.lineTo(17.4f, 12f - dir * 3.4f);
                canvas.drawPath(path, paint);
                break;
            case PLUS:
                canvas.drawCircle(12f, 12f, 8.2f, paint);
                canvas.drawLine(12f, 7.8f, 12f, 16.2f, paint);
                canvas.drawLine(7.8f, 12f, 16.2f, 12f, paint);
                break;
            case THOUGHT:
                canvas.drawRoundRect(new RectF(3.8f, 5f, 20.2f, 15.4f), 2.4f, 2.4f, paint);
                path.moveTo(8.4f, 15.4f);
                path.lineTo(8.4f, 19.2f);
                path.lineTo(12.4f, 15.4f);
                canvas.drawPath(path, paint);
                path.reset();
                paint.setStrokeWidth(1.3f);
                canvas.drawLine(7.2f, 9f, 16.8f, 9f, paint);
                canvas.drawLine(7.2f, 12.2f, 13.4f, 12.2f, paint);
                break;
            case BACK:
                canvas.drawLine(19.2f, 12f, 5.4f, 12f, paint);
                path.moveTo(10.2f, 6.6f);
                path.lineTo(4.8f, 12f);
                path.lineTo(10.2f, 17.4f);
                canvas.drawPath(path, paint);
                break;
            case HEART:
                path.moveTo(12f, 18.4f);
                path.quadTo(4.8f, 13.2f, 6.1f, 9.1f);
                path.quadTo(7.3f, 5.7f, 10.2f, 6.7f);
                path.quadTo(11.6f, 7.2f, 12f, 8.7f);
                path.quadTo(12.4f, 7.2f, 13.8f, 6.7f);
                path.quadTo(16.7f, 5.7f, 17.9f, 9.1f);
                path.quadTo(19.2f, 13.2f, 12f, 18.4f);
                path.close();
                canvas.drawPath(path, paint);
                break;
            case CHAT:
                canvas.drawRoundRect(new RectF(4.4f, 5.6f, 19.6f, 15.4f), 2.2f, 2.2f, paint);
                path.moveTo(8.6f, 15.4f);
                path.lineTo(8.6f, 19f);
                path.lineTo(12.6f, 15.4f);
                canvas.drawPath(path, paint);
                break;
            case DOT:
                canvas.drawCircle(12f, 12f, 8.2f, paint);
                paint.setStyle(Paint.Style.FILL);
                canvas.drawCircle(12f, 12f, 2.6f, paint);
                break;
            case LIST:
                canvas.drawLine(4.4f, 7.4f, 19.6f, 7.4f, paint);
                canvas.drawLine(4.4f, 12f, 19.6f, 12f, paint);
                canvas.drawLine(4.4f, 16.6f, 15.4f, 16.6f, paint);
                break;
            case PROGRESS:
                canvas.drawLine(3.8f, 12f, 8.4f, 12f, paint);
                canvas.drawLine(15.6f, 12f, 20.2f, 12f, paint);
                canvas.drawCircle(12f, 12f, 3.6f, paint);
                paint.setStyle(Paint.Style.FILL);
                canvas.drawCircle(12f, 12f, 1.5f, paint);
                break;
            case LETTER_A:
                paint.setStyle(Paint.Style.FILL);
                paint.setTextSize(17f);
                paint.setTextAlign(Paint.Align.CENTER);
                canvas.drawText("A", 12f, 18.2f, paint);
                paint.setTextAlign(Paint.Align.LEFT);
                break;
            case INFO:
                canvas.drawRoundRect(new RectF(5.4f, 3.6f, 18.6f, 20.4f), 1.6f, 1.6f, paint);
                paint.setStrokeWidth(1.3f);
                canvas.drawLine(8.4f, 8.4f, 15.6f, 8.4f, paint);
                canvas.drawLine(8.4f, 12f, 15.6f, 12f, paint);
                canvas.drawLine(8.4f, 15.6f, 13f, 15.6f, paint);
                break;
            case BOOKMARK:
                // 上方丝带 + 底部 V 形缺口，一眼能和「目录」区分
                path.moveTo(6.2f, 3.8f);
                path.lineTo(17.8f, 3.8f);
                path.lineTo(17.8f, 20.2f);
                path.lineTo(12f, 15.4f);
                path.lineTo(6.2f, 20.2f);
                path.close();
                canvas.drawPath(path, paint);
                break;
            case OPENBOOK:
                // 展开的书：中缝低、两侧页角翘起
                path.moveTo(12f, 8.2f);
                path.lineTo(12f, 19.4f);
                canvas.drawPath(path, paint);
                path.reset();
                path.moveTo(12f, 8.2f);
                path.lineTo(8.2f, 5.8f);
                path.lineTo(3.6f, 6.4f);
                path.lineTo(3.6f, 17.6f);
                path.lineTo(8.2f, 17f);
                path.lineTo(12f, 19.4f);
                path.lineTo(15.8f, 17f);
                path.lineTo(20.4f, 17.6f);
                path.lineTo(20.4f, 6.4f);
                path.lineTo(15.8f, 5.8f);
                path.close();
                canvas.drawPath(path, paint);
                break;
            default:
                break;
        }
        canvas.restore();
    }

    private void drawKnob(Canvas canvas, float x, float y) {
        paint.setStyle(Paint.Style.FILL);
        int color = paint.getColor();
        paint.setColor(Color.WHITE);
        canvas.drawCircle(x, y, 2.4f, paint);
        paint.setStyle(Paint.Style.STROKE);
        paint.setColor(color);
        paint.setStrokeWidth(1.5f);
        canvas.drawCircle(x, y, 2.4f, paint);
    }
}
