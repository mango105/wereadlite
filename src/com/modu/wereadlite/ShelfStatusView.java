package com.modu.wereadlite;

import android.content.Context;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * 书架状态面板：加载中 / 空 / 断网 / 加载失败 四态。
 * 结构与配色对齐微信读书墨水屏官方 StatusPanel：
 * 标题 #595959、副文 #B3B3B3、按钮 #F2F2F2 底 + 1px #303030 描边、直角。
 */
final class ShelfStatusView extends LinearLayout {

    private final TextView title;
    private final TextView sub;
    private final Button retry;

    ShelfStatusView(Context c, OnClickListener onRetry) {
        super(c);
        setOrientation(VERTICAL);
        setGravity(Gravity.CENTER);
        setPadding(0, Ui.dp(c, 60), 0, Ui.dp(c, 20));

        title = Ui.text(c, "", 16, 0xFF595959);
        title.setGravity(Gravity.CENTER);
        addView(title, new LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));

        sub = Ui.text(c, "", 12, 0xFFB3B3B3);
        sub.setGravity(Gravity.CENTER);
        LayoutParams subLp = new LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        subLp.topMargin = Ui.dp(c, 10);
        addView(sub, subLp);

        retry = new Button(c);
        retry.setText("重试");
        retry.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        retry.setTextColor(0xFF303030);
        retry.setBackgroundDrawable(Ui.box(0xFFF2F2F2, 0xFF303030, 1, 0));
        LayoutParams btnLp = new LayoutParams(Ui.dp(c, 80), Ui.dp(c, 30));
        btnLp.topMargin = Ui.dp(c, 20);
        btnLp.gravity = Gravity.CENTER_HORIZONTAL;
        retry.setLayoutParams(btnLp);
        retry.setOnClickListener(onRetry);
        addView(retry);
    }

    void showLoading() {
        title.setText("正在同步书架…");
        sub.setText("");
        retry.setVisibility(GONE);
        setVisibility(VISIBLE);
    }

    void showEmpty() {
        title.setText("书架是空的");
        sub.setText("下拉刷新或从书城添加书籍");
        retry.setVisibility(GONE);
        setVisibility(VISIBLE);
    }

    void showOffline() {
        title.setText("网络不可用");
        sub.setText("显示本地缓存数据");
        retry.setVisibility(VISIBLE);
        setVisibility(VISIBLE);
    }

    void showError(String hint) {
        boolean offline = hint != null && (hint.contains("网络") || hint.contains("Wi-Fi"));
        title.setText(offline ? "网络不可用" : "加载失败");
        sub.setText(offline ? "显示本地缓存数据" : (hint == null ? "请检查网络后重试" : hint));
        retry.setVisibility(VISIBLE);
        setVisibility(VISIBLE);
    }
}
