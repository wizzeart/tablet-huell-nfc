package com.petronova.kiosk.system;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import com.petronova.kiosk.ui.main.MainActivity;

/** Autoarranque: lanza la app al encender el dispositivo (kiosko). */
public class BootReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        if (Intent.ACTION_BOOT_COMPLETED.equals(intent.getAction())) {
            Intent launch = new Intent(context, MainActivity.class);
            launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            context.startActivity(launch);
        }
    }
}
