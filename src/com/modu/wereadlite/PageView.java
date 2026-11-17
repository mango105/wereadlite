package com.modu.wereadlite;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.text.Layout;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.StaticLayout;
import android.text.TextPaint;
import android.text.TextUtils;
import android.text.style.LeadingMarginSpan;
import android.text.style.LineHeightSpan;
import android.util.TypedValue;
import android.view.MotionEvent;
import android.view.View;

/** 手动分页的正文视图：黑白直绘，无动画。 */
public class PageView extends View {

    public interface Callback {
        void onNextChapter();

        void onPrevChapter();

        void onMenu();

        void onPageChanged(int page, int total, int offset);
    }

    /** 页脚文字来源：整本书进度或按章进度由外部决定。 */
    public interface Footer {
        /** 页脚居中：书名 / 作者。 */
        String caption();

        /** 页脚左侧：页码。 */
        String pageLabel(int localPage, int localTotal, int offset);

        /** 页脚右侧：百分比。 */
        String percentLabel(int localPage, int localTotal, int offset);
    }

    private final TextPaint paint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final Paint rulePaint = new Paint();
    private final Paint footerPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final TextPaint captionPaint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private String text = "";
    private int[] starts = new int[]{0};
    private int index;
    private int fontSizeSp = 22;
    /** 行距，百分比：116 表示 1.16 倍行高。 */
    private int linePct = 116;
    /** 左右边距，dp。 */
    private int marginDp = 14;
    private int margin;
    /** 页脚区高度：一行文字 + 分隔线，正文排版不占用这块。 */
    private int footerHeight;
    /** 首行缩进，按半角格算：2 格 = 1 个汉字，4 格即标准的两字缩进。 */
    private int indentCells = 4;
    private boolean paraGap;
    private StaticLayout layout;
    private int layoutStart = -1;
    /** 页脚是否绘制：工具条弹起时隐藏，但不改变正文区域。 */
    private boolean footerShown = true;
    private Callback callback;
    private Footer footer;
    private boolean pendingStartAtEnd;

    public PageView(Context ctx) {
        super(ctx);
        paint.setColor(Color.BLACK);
        paint.setSubpixelText(false);
        rulePaint.setColor(Color.BLACK);
        rulePaint.setStrokeWidth(1f);
        footerPaint.setColor(Color.BLACK);
        captionPaint.setColor(0xFF8A8A8A);
        setBackgroundColor(Color.WHITE);
        applyMetrics();
    }

    public void setCallback(Callback cb) {
        callback = cb;
    }

    public void setFooter(Footer f) {
        footer = f;
        invalidate();
    }

    public void setFontSize(int sp) {
        if (sp < 14) sp = 14;
        if (sp > 40) sp = 40;
        if (sp == fontSizeSp) return;
        fontSizeSp = sp;
        reflow();
    }

    public void setLinePct(int pct) {
        if (pct < 100) pct = 100;
        if (pct > 220) pct = 220;
        if (pct == linePct) return;
        linePct = pct;
        reflow();
    }

    public void setMarginDp(int dp) {
        if (dp < 4) dp = 4;
        if (dp > 56) dp = 56;
        if (dp == marginDp) return;
        marginDp = dp;
        reflow();
    }

    public int linePct() {
        return linePct;
    }

    public int marginDp() {
        return marginDp;
    }

    /** 段落首行缩进格数；0 表示不缩进。 */
    public void setIndentCells(int cells) {
        if (cells < 0) cells = 0;
        if (cells > 12) cells = 12;
        if (cells == indentCells) return;
        indentCells = cells;
        reflow();
    }

    public int indentCells() {
        return indentCells;
    }

    /** 段间距：段落之间留一条窄空白，不改字符、不影响进度偏移。 */
    public void setParaGap(boolean on) {
        if (paraGap == on) return;
        paraGap = on;
        reflow();
    }

    public boolean paraGap() {
        return paraGap;
    }

    /** 字号/行距/边距/缩进变化后重新分页，并保持当前阅读位置。 */
    private void reflow() {
        applyMetrics();
        if (getWidth() <= 0 || text.length() == 0) {
            invalidate();
            return;
        }
        int offset = currentOffset();
        paginate();
        index = pageOf(offset);
        layout = null;
        layoutStart = -1;
        notifyPage();
        invalidate();
    }

    private void applyMetrics() {
        margin = dp(marginDp);
        footerHeight = dp(30);
        applyFontSize();
    }

    private float lineSpacing() {
        return linePct / 100f;
    }

    public int fontSize() {
        return fontSizeSp;
    }

    /**
     * 工具条/面板弹起时隐藏页码。只重绘，不重新分页——
     * 正文区域一旦跟着变，页面会像被弹一下，墨水屏上尤其难看。
     */
    public void setFooterShown(boolean shown) {
        if (shown == footerShown) return;
        footerShown = shown;
        invalidate();
    }

    /** 跳到正文里的某个字符偏移（进度滑杆用）。 */
    public void setToOffset(int offset) {
        if (starts.length == 0) return;
        int n = pageOf(Math.max(0, Math.min(offset, text.length())));
        if (n == index) return;
        index = n;
        layout = null;
        layoutStart = -1;
        notifyPage();
        invalidate();
    }

    /** 本章当前页 / 本章总页，进度面板和页脚共用。 */
    public int currentPage() {
        return index;
    }

    public int totalPages() {
        return starts.length;
    }

    private void applyFontSize() {
        paint.setTextSize(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, fontSizeSp,
                getResources().getDisplayMetrics()));
    }

    public void setText(String content, boolean startAtEnd, int offset) {
        text = content == null ? "" : content;
        if (getWidth() > 0) {
            paginate();
            if (offset > 0) index = pageOf(offset);
            else if (startAtEnd) index = starts.length - 1;
            else index = 0;
            layout = null;
            if (index < 0) index = 0;
            if (index >= starts.length) index = starts.length - 1;
            notifyPage();
            invalidate();
        } else {
            index = 0;
            pendingStartAtEnd = startAtEnd;
        }
    }

    public int currentOffset() {
        if (starts.length == 0) return 0;
        return starts[Math.max(0, Math.min(index, starts.length - 1))];
    }

    public int pageCount() {
        return starts.length;
    }

    public int pageIndex() {
        return index;
    }

    public boolean next() {
        if (index < starts.length - 1) {
            index++;
            layout = null;
            notifyPage();
            invalidate();
            return true;
        }
        if (callback != null) callback.onNextChapter();
        return false;
    }

    public boolean prev() {
        if (index > 0) {
            index--;
            layout = null;
            notifyPage();
            invalidate();
            return true;
        }
        if (callback != null) callback.onPrevChapter();
        return false;
    }

    private void notifyPage() {
        if (callback != null) callback.onPageChanged(index, starts.length, currentOffset());
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        int before = currentOffset();
        paginate();
        index = before > 0 ? pageOf(before) : (pendingStartAtEnd ? starts.length - 1 : 0);
        pendingStartAtEnd = false;
        layout = null;
        notifyPage();
    }

    /**
     * 整章只排版一次：先把全章按当前字号/行距/边距排成行，再按页高切页。
     * 早先每切一页都要对「剩下的全部正文」建一个 StaticLayout，长章一次重排重复测量十几遍，
     * 拖动字号滑块时就是连续几十次全章测量，慢机器上手感直接散架。
     */
    private void paginate() {
        int w = getWidth() - margin * 2;
        int h = getHeight() - margin - footerHeight;
        if (w <= 0 || h <= 0 || text.length() == 0) {
            starts = new int[]{0};
            index = 0;
            return;
        }
        StaticLayout full = layoutOf(0, text.length(), w);
        int lines = full.getLineCount();
        int[] lineStart = new int[lines];
        int[] lineTop = new int[lines];
        int[] lineBottom = new int[lines];
        for (int i = 0; i < lines; i++) {
            lineStart[i] = full.getLineStart(i);
            lineTop[i] = full.getLineTop(i);
            lineBottom[i] = full.getLineBottom(i);
        }
        starts = Pager.pageStarts(lineStart, lineTop, lineBottom, lines, h);
    }

    private int pageOf(int offset) {
        int p = 0;
        for (int i = 0; i < starts.length; i++) {
            if (starts[i] <= offset) p = i;
            else break;
        }
        return p;
    }

    private StaticLayout layoutOf(int start, int end, int width) {
        return new StaticLayout(styledParagraphs(start, end), paint, width,
                Layout.Alignment.ALIGN_NORMAL, lineSpacing(), 0f, false);
    }

    /**
     * 段落首行缩进只加「空白」，不往正文里插字符：字符偏移必须和云端进度、本地书签
     * 里的原文偏移完全一致，插空格会让整章位置全部错位。
     *
     * 两点容易踩：
     * - 一页从段落中间开始时，第一段是上一页的续行，不能再缩一次；
     * - 原文本身带前导空格的 TXT，只补到设定宽度，避免叠成双倍缩进。
     */
    private CharSequence styledParagraphs(int start, int end) {
        CharSequence raw = text.subSequence(start, end);
        if (start >= end) return raw;
        boolean doIndent = indentCells > 0;
        if (!doIndent && !paraGap) return raw;
        SpannableStringBuilder ssb = new SpannableStringBuilder(raw);
        int n = ssb.length();
        int want = (int) (paint.getTextSize() * 0.5f * indentCells + 0.5f);
        int gap = (int) (paint.getTextSize() * 0.28f + 0.5f);
        int para = 0;
        boolean fresh = start == 0 || text.charAt(start - 1) == '\n';
        for (int i = 0; i <= n; i++) {
            if (i < n && ssb.charAt(i) != '\n') continue;
            if (fresh && i > para) {
                if (doIndent) {
                    int extra = want - leadingBlankWidth(ssb, para, i);
                    if (extra > 0) {
                        ssb.setSpan(new LeadingMarginSpan.Standard(extra, 0), para, i,
                                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                    }
                }
                // 段间距加在段首第一个字符上（不是页首第一段），抬高行顶留白。
                // LineHeightSpan 只改绘制高度，不改变字符数，进度偏移完全不受影响。
                if (paraGap && para < n) {
                    ssb.setSpan(new ParaGapSpan(gap), para, para + 1,
                            Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                }
            }
            para = i + 1;
            fresh = true;
        }
        return ssb;
    }

    /** 段首行顶部留白；只调字体度量，不动文本长度。 */
    private static final class ParaGapSpan implements LineHeightSpan {
        private final int gap;

        ParaGapSpan(int gap) {
            this.gap = gap;
        }

        @Override
        public void chooseHeight(CharSequence text, int start, int end,
                                 int spanStart, int spanEnd, Paint.FontMetricsInt fm) {
            fm.ascent -= gap;
            fm.top -= gap;
        }
    }

    /** 段落开头已有空白占多宽（半角空格 / 全角空格 / Tab / 不换行空格）。 */
    private int leadingBlankWidth(CharSequence s, int from, int to) {
        float w = 0f;
        for (int i = from; i < to; i++) {
            char c = s.charAt(i);
            if (c != ' ' && c != '\t' && c != '\r' && c != '　' && c != '\u00a0') break;
            w += paint.measureText(String.valueOf(c));
        }
        return (int) w;
    }

    @Override
    protected void onDraw(Canvas canvas) {
        int w = getWidth() - margin * 2;
        int end = index + 1 < starts.length ? starts[index + 1] : text.length();
        if (layout == null || layoutStart != starts[index]) {
            layout = layoutOf(starts[index], end, w);
            layoutStart = starts[index];
        }
        canvas.save();
        canvas.translate(margin, margin);
        if (layout != null) layout.draw(canvas);
        canvas.restore();

        if (!footerShown) return;
        // 页脚一行：左页码 / 中书名·作者 / 右百分比，省下一行给正文。
        int baseY = getHeight() - dp(9);
        int ruleY = baseY - dp(18);
        canvas.drawLine(margin, ruleY, getWidth() - margin, ruleY, rulePaint);

        footerPaint.setTextSize(dp(11));
        String left = footer == null ? null : footer.pageLabel(index, starts.length, currentOffset());
        String right = footer == null ? null : footer.percentLabel(index, starts.length, currentOffset());
        if (left == null) left = (index + 1) + "/" + starts.length;
        if (right == null) right = percent();
        float leftW = footerPaint.measureText(left);
        float rightW = right.length() == 0 ? 0f : footerPaint.measureText(right);
        canvas.drawText(left, margin, baseY, footerPaint);
        if (rightW > 0f) {
            canvas.drawText(right, getWidth() - margin - rightW, baseY, footerPaint);
        }

        captionPaint.setTextSize(dp(10));
        String cap = footer == null ? "" : footer.caption();
        if (cap == null) cap = "";
        if (cap.length() > 0) {
            // 书名夹在左右信息之间，宽度不够就省略，避免三者重叠
            float gap = dp(10);
            float avail = getWidth() - margin * 2f - leftW - rightW - gap * 2f;
            if (avail > dp(36)) {
                CharSequence e = TextUtils.ellipsize(cap, captionPaint, avail,
                        TextUtils.TruncateAt.END);
                String s = e.toString();
                float capW = captionPaint.measureText(s);
                float capX = margin + leftW + gap + (avail - capW) / 2f;
                canvas.drawText(s, capX, baseY, captionPaint);
            }
        }
    }

    private String percent() {
        if (text.length() == 0) return "";
        int p = currentOffset() * 100 / Math.max(1, text.length());
        return p + "%";
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        if (event.getAction() == MotionEvent.ACTION_DOWN) return true;
        if (event.getAction() != MotionEvent.ACTION_UP) return true;
        int w = getWidth();
        float x = event.getX();
        if (x < w * 0.28f) prev();
        else if (x > w * 0.72f) next();
        else if (callback != null) callback.onMenu();
        return true;
    }

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density + 0.5f);
    }
}
