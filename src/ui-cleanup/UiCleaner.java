package local.simcity.offline;

import android.app.Activity;
import android.app.Application;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.TextView;

public final class UiCleaner {
    private static native int install(long base);
    private static native int pollSettingsVisible();
    private static native int pollShopVisible();
    static native boolean addCurrency(int type);
    static native int currencyAppliedCount(int type);

    public static void start(Application app) {
        try {
            System.loadLibrary("simcityui");
        } catch (Throwable e) {
            android.util.Log.e("SimCityUI", "Native filter unavailable", e);
            return;
        }

        installSettingsClose(app);
        Thread t = new Thread(new Runnable() {
            public void run() {
                for (int attempt = 0; attempt < 3000; attempt++) {
                    try {
                        java.io.BufferedReader r = new java.io.BufferedReader(
                                new java.io.FileReader("/proc/self/maps"));
                        String line;
                        while ((line = r.readLine()) != null) {
                            if (!line.contains("/dev/zero") || !line.contains("rwx")) continue;
                            String[] range = line.substring(0, line.indexOf(' ')).split("-");
                            long lo = Long.parseLong(range[0], 16);
                            long hi = Long.parseLong(range[1], 16);
                            if (hi - lo < 35000000 || hi - lo > 36000000) continue;
                            int result = install(lo);
                            if (result == 1) {
                                r.close();
                                android.util.Log.i("SimCityUI", "UI filter installed");
                                return;
                            }
                        }
                        r.close();
                        Thread.sleep(10);
                    } catch (Throwable e) {
                        android.util.Log.e("SimCityUI", "UI filter initialization failed", e);
                        return;
                    }
                }
                android.util.Log.e("SimCityUI", "Game module was not ready");
            }
        }, "SimCityUI");
        t.setDaemon(true);
        t.start();
    }

    private static void installSettingsClose(Application app) {
        app.registerActivityLifecycleCallbacks(new Application.ActivityLifecycleCallbacks() {
            public void onActivityCreated(Activity activity, android.os.Bundle state) {}
            public void onActivityStarted(Activity activity) {}
            public void onActivityPaused(Activity activity) {}
            public void onActivityStopped(Activity activity) {}
            public void onActivitySaveInstanceState(Activity activity, android.os.Bundle state) {}
            public void onActivityDestroyed(Activity activity) {}

            public void onActivityResumed(final Activity activity) {
                activity.getWindow().getDecorView().post(new Runnable() {
                    public void run() {
                        View old = activity.getWindow().getDecorView().findViewWithTag("simcity-settings-close");
                        if (old != null) return;
                        final FrameLayout decor = (FrameLayout) activity.getWindow().getDecorView();
                        final TextView close = new TextView(activity);
                        close.setTag("simcity-settings-close");
                        close.setText("×");
                        close.setTextColor(Color.WHITE);
                        close.setTextSize(30);
                        close.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
                        close.setGravity(Gravity.CENTER);
                        close.setContentDescription("关闭设置");
                        GradientDrawable bg = new GradientDrawable();
                        bg.setShape(GradientDrawable.OVAL);
                        bg.setColor(Color.rgb(58, 139, 184));
                        bg.setStroke(dp(activity, 2), Color.WHITE);
                        close.setBackground(bg);
                        close.setElevation(dp(activity, 8));
                        close.setVisibility(View.GONE);

                        android.util.DisplayMetrics dm = activity.getResources().getDisplayMetrics();
                        int size = dp(activity, 52);
                        int x = Math.max(0, (int) (dm.widthPixels * 0.66f) - size / 2);
                        int y = Math.max(0, (int) (dm.heightPixels * 0.245f) - size / 2);
                        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(size, size,
                                Gravity.TOP | Gravity.LEFT);
                        lp.leftMargin = x;
                        lp.topMargin = y;
                        decor.addView(close, lp);
                        final FrameLayout currencyCards = CurrencyCards.create(activity);
                        FrameLayout.LayoutParams cardsLp = new FrameLayout.LayoutParams(
                                (int)(dm.widthPixels * 0.81f), (int)(dm.heightPixels * 0.53f), Gravity.TOP | Gravity.LEFT);
                        cardsLp.leftMargin=(int)(dm.widthPixels * 0.18f);
                        cardsLp.topMargin=(int)(dm.heightPixels * 0.265f);
                        decor.addView(currencyCards, cardsLp);

                        close.setOnClickListener(new View.OnClickListener() {
                            public void onClick(View v) {
                                close.setVisibility(View.GONE);
                                activity.dispatchKeyEvent(new KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_BACK));
                                activity.dispatchKeyEvent(new KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_BACK));
                            }
                        });

                        final Handler handler = new Handler(Looper.getMainLooper());
                        final Runnable poll = new Runnable() {
                            public void run() {
                                if (activity.isFinishing() || activity.isDestroyed()) {
                                    decor.removeView(close);
                                    return;
                                }
                                close.setVisibility(pollSettingsVisible() != 0 ? View.VISIBLE : View.GONE);
                                currencyCards.setVisibility(pollShopVisible() != 0 ? View.VISIBLE : View.GONE);
                                handler.postDelayed(this, 200);
                            }
                        };
                        handler.post(poll);
                    }
                });
            }
        });
    }

    private static int dp(Activity activity, int value) {
        return (int) (value * activity.getResources().getDisplayMetrics().density + 0.5f);
    }
}
