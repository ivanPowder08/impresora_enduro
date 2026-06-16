package com.enduro.printservice;

import android.app.Application;
import android.content.Intent;
import android.content.SharedPreferences;
import android.preference.PreferenceManager;

/**
 * Clase Application del servicio de impresión Enduro.
 * Se inicializa automáticamente al arrancar la app.
 */
public class PrintApp extends Application {

    public static final String PREF_PRINTER_IP   = "printer_ip";
    public static final String PREF_PRINTER_PORT = "printer_port";
    public static final String PREF_PAPER_WIDTH  = "paper_width";  // 58 | 80
    public static final String PREF_AUTO_CUT     = "auto_cut";
    public static final String PREF_SERVICE_ON   = "service_on";

    // Valores predeterminados
    public static final String  DEFAULT_IP         = "192.168.1.100";
    public static final int     DEFAULT_PORT        = 9100;
    public static final int     DEFAULT_PAPER_WIDTH = 80;

    @Override
    public void onCreate() {
        super.onCreate();

        // Si el servicio estaba activo antes, volver a iniciarlo
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(this);
        if (prefs.getBoolean(PREF_SERVICE_ON, false)) {
            Intent serviceIntent = new Intent(this, PrinterService.class);
            serviceIntent.setAction(PrinterService.ACTION_START);
            startForegroundService(serviceIntent);
        }
    }
}
