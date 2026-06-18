package com.petronova.kiosk.system;

import android.os.CountDownTimer;

/**
 * Timer de inactividad del kiosko.
 * Equivalente de INACTIVITY_TIMEOUT, resetInactivityTimer(), handleInactivityTimeout() en kiosk.js.
 * Si el usuario no interactúa en INACTIVITY_TIMEOUT_MS ms, llama al listener (→ vuelve a main).
 */
public class InactivityTimer {

    private final long           timeoutMs;
    private final Runnable       onTimeout;
    private       CountDownTimer timer;

    public InactivityTimer(long timeoutMs, Runnable onTimeout) {
        this.timeoutMs = timeoutMs;
        this.onTimeout = onTimeout;
    }

    public void start() {
        cancel();
        timer = new CountDownTimer(timeoutMs, timeoutMs) {
            @Override public void onTick(long ms) {}
            @Override public void onFinish() { onTimeout.run(); }
        }.start();
    }

    /** Resetea el countdown. Llamar en onUserInteraction() de la Activity. */
    public void reset() { start(); }

    public void stop() { cancel(); }

    private void cancel() {
        if (timer != null) { timer.cancel(); timer = null; }
    }
}
