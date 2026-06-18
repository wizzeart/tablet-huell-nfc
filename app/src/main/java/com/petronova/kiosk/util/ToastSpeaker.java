package com.petronova.kiosk.util;

import android.content.Context;
import android.widget.Toast;
import com.petronova.kiosk.audio.TtsManager;

/** Muestra un Toast y lo narra por TTS con el mismo texto. */
public final class ToastSpeaker {

    private ToastSpeaker() {}

    public static void show(Context context, String message) {
        show(context, message, Toast.LENGTH_LONG);
    }

    public static void show(Context context, String message, int duration) {
        if (context == null || message == null || message.trim().isEmpty()) return;
        Toast.makeText(context.getApplicationContext(), message, duration).show();
        TtsManager.getInstance().speak(message);
    }
}
