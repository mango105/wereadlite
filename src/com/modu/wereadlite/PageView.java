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
import android.text.style.BackgroundColorSpan;
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

    /** 长按选择 / 点划线回调，阅读页弹菜单和想法列表用。 */
    public interface SelectListener {
        /** 长按松手后的选区（章内绝对偏移）。 */
        void onSelection(int start, int end);

        /** 点在一条高亮上（kind：1 本地划线 / 2 书友划线）。 */
        void onHighlightTap(int start, int end, int kind);
    }

    /** 高亮种类。 */
    public static final int HL_LOCAL = 1;
    public static final int HL_CLOUD = 2;

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
    /** 段间距档位 0–4：0 关闭，1 档约为 0.35 倍字高，其余按档递增。 */
    private int paraGapLevel;
    private StaticLayout layout;
    private int layoutStart = -1;
    /** 页脚是否绘制：工具条弹起时隐藏，但不改变正文区域。 */
    private boolean footerShown = true;
    private Callback callback;
    private Footer footer;
    private boolean pendingStartAtEnd;

    // ---------------- 划线高亮 + 长按选择 ----------------
    /** 高亮区间：[s0,e0,s1,e1,...] 章内绝对偏移，与 kinds 一一对应。 */
    private int[] hlRanges = new int[0];
    private int[] hlKinds = new int[0];
    private SelectListener selectListener;
    private int selStart = -1;
    private int selEnd = -1;
    private boolean selecting;
    private boolean longFired;
    private boolean movedFar;
    private float downX;
    private float downY;
    private int wordStart;
    private int wordEnd;
    private final Paint handlePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Runnable longPress = new Runnable() {
        @Override
        public void run() {
            if (movedFar || longFired) return;
            int off = offsetFor(downX, downY);
            if (off < 0) return;
            longFired = true;
            selecting = true;
            selectWord(off);
            invalidate();
        }
    };

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

    /** 段间距档位 0–4：段落之间留一条窄空白，不改字符、不影响进度偏移。 */
    public void setParaGap(int level) {
        level = Math.max(0, Math.min(4, level));
        if (paraGapLevel == level) return;
        paraGapLevel = level;
        reflow();
    }

    // ---------------- 划线高亮 ----------------

    /**
     * 设置高亮区间并重绘。区间是章内绝对偏移，与分页无关；改字号重排后位置依旧。
     * kinds[i]：1 本地划线（深灰底）、2 书友划线（浅灰底）。
     */
    public void setHighlightRanges(int[] ranges, int[] kinds) {
        hlRanges = ranges == null ? new int[0] : ranges;
        hlKinds = kinds == null ? new int[0] : kinds;
        layout = null;
        invalidate();
    }

    public void setSelectListener(SelectListener l) {
        selectListener = l;
    }

    /** 退出选择态（菜单消失、正文恢复）。 */
    public void clearSelection() {
        if (!selecting && selStart < 0) return;
        selecting = false;
        selStart = -1;
        selEnd = -1;
        layout = null;
        invalidate();
    }

    public boolean hasSelection() {
        return selecting && selStart >= 0 && selEnd > selStart;
    }

    public int selectionStart() {
        return selStart;
    }

    public int selectionEnd() {
        return selEnd;
    }

    /** 选区文字，复制 / 查询 / 新划线的摘录都从这取。 */
    public String selectionText() {
        if (!hasSelection()) return "";
        int to = Math.min(selEnd, text.length());
        return text.substring(Math.min(selStart, to), to);
    }

    /** 选区首行在视图里的 Y（含 margin），弹菜单贴着它上方。 */
    public int selectionTop() {
        if (!hasSelection() || ensureLayout() == null) return -1;
        int rel = Math.max(0, selStart - starts[index]);
        int line = layout.getLineForOffset(rel);
        if (line < 0) return -1;
        return margin + layout.getLineTop(line);
    }

    public int paraGap() {
        return paraGapLevel;
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
            clearSelection();
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
            clearSelection();
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
        SpannableStringBuilder ssb = new SpannableStringBuilder(raw);
        int n = ssb.length();
        int want = (int) (paint.getTextSize() * 0.5f * indentCells + 0.5f);
        int gap = (int) (paint.getTextSize() * 0.35f * paraGapLevel + 0.5f);
        int para = 0;
        boolean fresh = start == 0 || text.charAt(start - 1) == '\n';
        boolean anySpan = indentCells > 0 || paraGapLevel > 0
                || hlRanges.length > 0 || selStart >= 0;
        if (!anySpan) return raw;
        applyHighlightSpans(ssb, start);
        boolean doIndent = indentCells > 0;
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
                if (paraGapLevel > 0 && para < n) {
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
            // LineHeightSpan 是段落级 span：StaticLayout 会对段落内每一行都调到这里，
            // 只有当前行真的包含段首字符（spanStart 在行范围内）才抬高这一行，
            // 否则间距会泄漏成整段行距（实测整页只装得下几行的元凶）。
            if (spanStart < start || spanStart >= end) return;
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

    /**
     * 划线/选区背景色：只改底色不改字符。墨水屏上颜色会变灰阶，
     * 所以用不同深浅区分：本地划线深、书友划线浅、选区最深。
     */
    private void applyHighlightSpans(SpannableStringBuilder ssb, int pageStart) {
        int pageEnd = pageStart + ssb.length();
        for (int i = 0; i < hlRanges.length; i++) {
            int s = Math.max(hlRanges[i], pageStart);
            int e = Math.min(i * 2 + 1 < hlRanges.length ? hlRanges[i * 2 + 1] : hlRanges[i],
                    pageEnd);
            if (e <= s) continue;
            int kind = i < hlKinds.length ? hlKinds[i] : HL_CLOUD;
            ssb.setSpan(new BackgroundColorSpan(kind == HL_LOCAL ? 0x33000000 : 0x1E000000),
                    s - pageStart, e - pageStart, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        }
        if (selStart >= 0 && selEnd > selStart) {
            int s = Math.max(selStart, pageStart);
            int e = Math.min(selEnd, pageEnd);
            if (e > s) {
                ssb.setSpan(new BackgroundColorSpan(0x4A000000),
                        s - pageStart, e - pageStart, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            }
        }
    }

    /** 触点 → 章内字符偏移；越界返回 -1。 */
    private int offsetFor(float x, float y) {
        StaticLayout l = ensureLayout();
        if (l == null || l.getLineCount() == 0) return -1;
        int line = l.getLineForVertical((int) (y - margin));
        if (line < 0 || line >= l.getLineCount()) return -1;
        int off = l.getOffsetForHorizontal(line, x - margin);
        int pageStart = starts[index];
        int pageEnd = index + 1 < starts.length ? starts[index + 1] : text.length();
        off = Math.max(0, Math.min(off, l.getText().length()));
        int abs = pageStart + off;
        return Math.max(pageStart, Math.min(abs, pageEnd - 1));
    }

    private StaticLayout ensureLayout() {
        if (layout != null && layoutStart == starts[index]) return layout;
        int w = getWidth() - margin * 2;
        if (w <= 0 || starts.length == 0) return null;
        int end = index + 1 < starts.length ? starts[index + 1] : text.length();
        if (end <= starts[index]) return null;
        layout = layoutOf(starts[index], end, w);
        layoutStart = starts[index];
        return layout;
    }

    /** 长按取词：CJK 按单字，字母/数字按连续段，标点单取。 */
    private void selectWord(int off) {
        int s = off;
        int e = off + 1;
        if (isWordChar(text.charAt(Math.min(off, text.length() - 1)))) {
            while (s > 0 && isWordChar(text.charAt(s - 1))) s--;
            while (e < text.length() && isWordChar(text.charAt(e))) e++;
        } else if (isLetter(off)) {
            while (s > 0 && isLetter(s - 1)) s--;
            while (e < text.length() && isLetter(e)) e++;
        }
        wordStart = s;
        wordEnd = e;
        selStart = s;
        selEnd = e;
    }

    private static boolean isWordChar(char c) {
        return Character.isLetterOrDigit(c) || c > 127;
    }

    private boolean isLetter(int off) {
        return off >= 0 && off < text.length() && Character.isLetter(text.charAt(off));
    }

    /** 拖动扩展选区：以初始词为锚，触点在锚左则扩到左侧，反之扩到右侧。 */
    private void extendSelection(float x, float y) {
        int off = offsetFor(x, y);
        if (off < 0) return;
        int anchorS = wordStart;
        int anchorE = wordEnd;
        if (off < anchorS) {
            selStart = off;
            selEnd = anchorE;
        } else if (off >= anchorE) {
            selStart = anchorS;
            selEnd = Math.min(off + 1, text.length());
        } else {
            selStart = anchorS;
            selEnd = anchorE;
        }
        invalidate();
    }

    private void notifySelection() {
        if (selectListener != null && selEnd > selStart) {
            selectListener.onSelection(selStart, selEnd);
        }
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
        if (selStart >= 0 && selEnd > selStart && layout != null) drawHandles(canvas, layout);
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

    /** 选区两端的拖动手柄：一条竖线 + 底部小圆点，官方样式。 */
    private void drawHandles(Canvas canvas, StaticLayout l) {
        handlePaint.setColor(0xFF444444);
        int pageStart = starts[index];
        int[][] ends = {{selStart, 1}, {selEnd - 1, -1}};
        for (int[] spec : ends) {
            int rel = Math.max(0, Math.min(spec[0] - pageStart, l.getText().length()));
            int line = l.getLineForOffset(rel);
            if (line < 0 || line >= l.getLineCount()) continue;
            float x = l.getPrimaryHorizontal(rel);
            int top = l.getLineTop(line);
            int bottom = l.getLineBottom(line);
            canvas.drawLine(x, top, x, bottom + dp(6), handlePaint);
            canvas.drawCircle(x, bottom + dp(8), dp(4), handlePaint);
        }
    }

    private String percent() {
        if (text.length() == 0) return "";
        int p = currentOffset() * 100 / Math.max(1, text.length());
        return p + "%";
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                downX = event.getX();
                downY = event.getY();
                movedFar = false;
                longFired = false;
                postDelayed(longPress, 420);
                return true;
            case MotionEvent.ACTION_MOVE:
                if (Math.abs(event.getX() - downX) > dp(16)
                        || Math.abs(event.getY() - downY) > dp(16)) {
                    movedFar = true;
                    if (!longFired) removeCallbacks(longPress);
                }
                if (selecting && longFired) extendSelection(event.getX(), event.getY());
                return true;
            case MotionEvent.ACTION_UP:
                removeCallbacks(longPress);
                return onUp(event);
            case MotionEvent.ACTION_CANCEL:
                removeCallbacks(longPress);
                return true;
            default:
                return true;
        }
    }

    private boolean onUp(MotionEvent event) {
        float x = event.getX();
        // 长按选择结束：保留选区并通知外层弹菜单。
        if (longFired) {
            if (selecting) notifySelection();
            return true;
        }
        int w = getWidth();
        int off = offsetFor(x, event.getY());
        // 点在选区里：保持菜单。
        if (selStart >= 0 && off >= selStart && off < selEnd) return true;
        // 点在选区外：清除选区，且这一下不再触发翻页。
        if (selStart >= 0) {
            clearSelection();
            if (selectListener != null) selectListener.onHighlightTap(-1, -1, 0);
            return true;
        }
        // 点在一条已有高亮上：交给外层（书友划线弹想法，本地划线弹操作菜单）。
        if (off >= 0) {
            for (int i = 0; i < hlRanges.length; i++) {
                int e = i * 2 + 1 < hlRanges.length ? hlRanges[i * 2 + 1] : hlRanges[i];
                if (off >= hlRanges[i] && off < e) {
                    int kind = i < hlKinds.length ? hlKinds[i] : HL_CLOUD;
                    if (selectListener != null) {
                        selectListener.onHighlightTap(hlRanges[i], e, kind);
                    }
                    return true;
                }
            }
        }
        if (x < w * 0.28f) prev();
        else if (x > w * 0.72f) next();
        else if (callback != null) callback.onMenu();
        return true;
    }

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density + 0.5f);
    }
}
