package com.petronova.kiosk.system;

import android.content.Context;
import android.os.SystemClock;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.text.SimpleDateFormat;
import java.util.Collections;
import java.util.Date;
import java.util.Locale;

/**
 * Información del sistema para la barra superior del kiosko.
 * Equivalente del endpoint GET /system_info de api.py y system_info.js.
 * Retorna: IP local, hora, uptime.
 */
public final class SystemInfoProvider {

    private SystemInfoProvider() {}

    public static String getStatusLine(Context ctx) {
        return getLocalIp() + " | " + getCurrentTime();
    }

    public static String getFullInfo(Context ctx) {
        long uptimeMs      = SystemClock.elapsedRealtime();
        long uptimeHours   = uptimeMs / 3_600_000;
        long uptimeMinutes = (uptimeMs % 3_600_000) / 60_000;
        return "IP: " + getLocalIp() +
               "\nHora: " + getCurrentTime() +
               "\nUptime: " + uptimeHours + "h " + uptimeMinutes + "m";
    }

    private static String getLocalIp() {
        try {
            for (NetworkInterface ni : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                for (InetAddress addr : Collections.list(ni.getInetAddresses())) {
                    if (!addr.isLoopbackAddress() && addr.getHostAddress().indexOf(':') < 0) {
                        return addr.getHostAddress();
                    }
                }
            }
        } catch (Exception ignore) {}
        return "sin red";
    }

    private static String getCurrentTime() {
        return new SimpleDateFormat("HH:mm", Locale.getDefault()).format(new Date());
    }
}
