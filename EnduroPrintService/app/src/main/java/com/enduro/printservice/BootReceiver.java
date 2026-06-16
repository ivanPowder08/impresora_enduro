package com.enduro.printservice;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.preference.PreferenceManager;

/**
 * Se ejecuta cuando el dispositivo arranca.
 * Si el servicio estaba activo, lo reinicia automáticamente.
 * Esto hace que la tablet no necesite abrir la app manualmente.
 */
public class BootReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        if (Intent.ACTION_BOOT_COMPLETED.equals(intent.getAction())) {
            SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(context);
            if (prefs.getBoolean(PrintApp.PREF_SERVICE_ON, false)) {
                Intent serviceIntent = new Intent(context, PrinterService.class);
                serviceIntent.setAction(PrinterService.ACTION_START);
                context.startForegroundService(serviceIntent);
            }
        }
    }
}
