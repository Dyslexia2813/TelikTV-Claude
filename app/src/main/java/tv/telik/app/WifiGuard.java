package tv.telik.app;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkInfo;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.SystemClock;

import java.io.BufferedReader;
import java.io.FileReader;

/**
 * The box firmware turns Wi-Fi on at every power-up even when a cable is plugged in.
 * After boot (and on app start) we check for a couple of minutes: if Ethernet is
 * connected and Wi-Fi is on, we switch Wi-Fi off. Without a cable we never touch Wi-Fi.
 */
public class WifiGuard extends BroadcastReceiver {

    private static final String ACTION_CHECK = "tv.telik.app.WIFI_CHECK";
    private static final String EXTRA_LEFT = "left";
    private static final long STEP_MS = 10_000;
    private static final int CHECKS = 13; // ~2 minutes: firmware sometimes enables Wi-Fi late

    /** Called from the app on start. */
    public static void start(Context ctx) {
        check(ctx.getApplicationContext(), CHECKS);
    }

    @Override
    public void onReceive(Context ctx, Intent intent) {
        int left = ACTION_CHECK.equals(intent.getAction())
                ? intent.getIntExtra(EXTRA_LEFT, 0) : CHECKS;
        check(ctx.getApplicationContext(), left);
    }

    private static void check(Context ctx, int left) {
        try {
            if (ethernetConnected(ctx)) {
                WifiManager wm = (WifiManager) ctx.getSystemService(Context.WIFI_SERVICE);
                if (wm != null && wm.isWifiEnabled()) wm.setWifiEnabled(false);
            }
        } catch (Throwable ignored) {
        }
        if (left > 1) schedule(ctx, left - 1);
    }

    private static void schedule(Context ctx, int left) {
        try {
            Intent i = new Intent(ctx, WifiGuard.class).setAction(ACTION_CHECK)
                    .putExtra(EXTRA_LEFT, left);
            int flags = PendingIntent.FLAG_UPDATE_CURRENT;
            if (Build.VERSION.SDK_INT >= 23) flags |= PendingIntent.FLAG_IMMUTABLE;
            PendingIntent pi = PendingIntent.getBroadcast(ctx, 7, i, flags);
            AlarmManager am = (AlarmManager) ctx.getSystemService(Context.ALARM_SERVICE);
            if (am != null) {
                am.set(AlarmManager.ELAPSED_REALTIME, SystemClock.elapsedRealtime() + STEP_MS, pi);
            }
        } catch (Throwable ignored) {
        }
    }

    @SuppressWarnings("deprecation")
    static boolean ethernetConnected(Context ctx) {
        try {
            ConnectivityManager cm =
                    (ConnectivityManager) ctx.getSystemService(Context.CONNECTIVITY_SERVICE);
            if (cm != null) {
                for (Network n : cm.getAllNetworks()) {
                    NetworkInfo ni = cm.getNetworkInfo(n);
                    if (ni != null && ni.getType() == ConnectivityManager.TYPE_ETHERNET
                            && ni.isConnected()) {
                        return true;
                    }
                }
            }
        } catch (Throwable ignored) {
        }
        // Cable plugged in but network not up yet: the link carrier is already 1.
        return "1".equals(readLine("/sys/class/net/eth0/carrier"));
    }

    private static String readLine(String path) {
        try (BufferedReader r = new BufferedReader(new FileReader(path))) {
            String s = r.readLine();
            return s == null ? null : s.trim();
        } catch (Throwable e) {
            return null;
        }
    }
}
