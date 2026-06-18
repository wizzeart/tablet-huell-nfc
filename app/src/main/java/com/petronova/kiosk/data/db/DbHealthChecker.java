package com.petronova.kiosk.data.db;

import com.petronova.kiosk.config.AppConfig;
import java.net.InetSocketAddress;
import java.net.Socket;

/**
 * Equivalente del endpoint GET /db/ping de api.py.
 * Verifica conectividad a PostgreSQL mediante un socket TCP (sin abrir sesión SQL).
 * Retorna latencia en ms o -1 si no es alcanzable.
 *
 * NOTA: llamar desde hilo de fondo.
 */
public final class DbHealthChecker {

    private DbHealthChecker() {}

    public static long ping() {
        long start = System.currentTimeMillis();
        try (Socket s = new Socket()) {
            s.connect(new InetSocketAddress(AppConfig.DB_HOST, AppConfig.DB_PORT), 5000);
            return System.currentTimeMillis() - start;
        } catch (Exception e) {
            return -1;
        }
    }

    public static boolean isReachable() {
        return ping() >= 0;
    }
}
