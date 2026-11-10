package com.modu.wereadlite;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * 书架管理授权：走 E-Ink 客户端登录（第二次扫码，同一微信读书账号）。
 * 授权后才能调用 /shelf/delete 真正从账号书架移除书籍。
 */
public class EinkLoginActivity extends Activity {

    private Store store;
    private final Handler ui = new Handler();

    private ImageView qrView;
    private TextView status;
    private volatile boolean running;
    private Thread worker;
    private int qrSize;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().setBackgroundDrawable(new ColorDrawable(Color.WHITE));
        store = new Store(this);
        setContentView(buildUi(), new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        start();
    }

    private View buildUi() {
        int pad = dp(20);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Color.WHITE);
        root.setGravity(Gravity.CENTER);
        root.setPadding(pad, pad, pad, pad);

        root.addView(Ui.text(this, "授权云端功能", 20, Ui.INK),
                new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT));

        TextView tip = Ui.text(this,
                "用微信扫码确认（与当前登录相同的账号）\n"
                        + "授权后才能查看书友想法、从账号书架移除书籍",
                11.5f, Ui.LIGHT);
        tip.setGravity(Gravity.CENTER);
        tip.setLineSpacing(dp(4), 1f);
        tip.setPadding(0, dp(10), 0, 0);
        root.addView(tip);

        qrSize = Math.min(getResources().getDisplayMetrics().widthPixels,
                getResources().getDisplayMetrics().heightPixels) * 3 / 5;
        qrView = new ImageView(this);
        LinearLayout.LayoutParams qp = new LinearLayout.LayoutParams(qrSize, qrSize);
        qp.topMargin = dp(14);
        root.addView(qrView, qp);

        status = Ui.text(this, "正在获取二维码…", 13, Ui.SUB);
        status.setGravity(Gravity.CENTER);
        status.setPadding(0, dp(12), 0, 0);
        root.addView(status);

        Button refresh = new Button(this);
        refresh.setText("刷新二维码");
        refresh.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        refresh.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                start();
            }
        });
        LinearLayout.LayoutParams bp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        bp.topMargin = dp(8);
        root.addView(refresh, bp);
        return root;
    }

    private void start() {
        running = false;
        running = true;
        Thread t = new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    final boolean compat = store.compat();
                    Eink.Ticket ticket = Eink.ticket(compat);
                    final String uuid = Eink.qrUuid(ticket, compat);
                    final Bitmap bmp = Qr.encode(Eink.qrUrl(uuid), qrSize);
                    ui.post(new Runnable() {
                        @Override
                        public void run() {
                            qrView.setImageBitmap(bmp);
                            status.setText("请用微信扫码并确认");
                        }
                    });
                    String last = "";
                    int idle = 0;
                    while (running) {
                        Eink.Poll p = Eink.poll(uuid, last, compat);
                        if (p.state == Eink.Poll.DONE) {
                            final Eink.Cred cred = Eink.login(p.wxCode, Eink.load(store).deviceId, compat);
                            if (store.vid().length() > 0 && !store.vid().equals(cred.vid)) {
                                throw new Exception("扫码账号与当前登录账号不同，请用同一个微信读书账号");
                            }
                            Eink.verify(store, cred);
                            Eink.save(store, cred);
                            ui.post(new Runnable() {
                                @Override
                                public void run() {
                                    status.setText("授权成功");
                                    setResult(RESULT_OK, new Intent());
                                    finish();
                                }
                            });
                            return;
                        }
                        if (p.state == Eink.Poll.TIMEOUT) {
                            throw new Exception("二维码已过期，请点「刷新二维码」");
                        }
                        if (p.state == Eink.Poll.DECLINED) {
                            throw new Exception("已取消授权，可刷新后重试");
                        }
                        last = p.last;
                        if (++idle > 130) throw new Exception("二维码已过期，请刷新");
                        try {
                            Thread.sleep(1200);
                        } catch (InterruptedException e) {
                            return;
                        }
                    }
                } catch (final Exception e) {
                    if (!running) return;
                    ui.post(new Runnable() {
                        @Override
                        public void run() {
                            status.setText(e.getMessage() == null ? "授权失败" : e.getMessage());
                        }
                    });
                }
            }
        });
        t.setDaemon(true);
        worker = t;
        t.start();
    }

    @Override
    protected void onDestroy() {
        running = false;
        super.onDestroy();
    }

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density + 0.5f);
    }
}
