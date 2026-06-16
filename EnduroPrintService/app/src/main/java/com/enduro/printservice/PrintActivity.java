package com.enduro.printservice;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.util.Log;
import android.widget.Toast;

/**
 * ============================================================
 * PrintActivity — Actividad transparente interceptora
 *
 * Esta actividad NO tiene UI visible. Simplemente:
 *  1. Captura el URL scheme:  enduroprint://print?type=...&data=...
 *  2. Parsea los parámetros
 *  3. Envía el trabajo al PrinterService
 *  4. Cierra inmediatamente
 *
 * Parámetros del URL:
 *   type   → "text" | "escpos" | "html" | "image"
 *   data   → contenido a imprimir (URL-encoded)
 *
 * Ejemplos desde JavaScript:
 *
 *   // Texto simple
 *   window.location.href = 'enduroprint://print?type=text&data=Hola+Mundo%0ATicekt+%231';
 *
 *   // Texto con formato (## = titulo, # = centrado, --- = separador)
 *   const ticket = '## MI TIENDA\n---\nProducto A  $10.00\nProducto B  $5.00\n---\n## TOTAL: $15.00';
 *   window.location.href = 'enduroprint://print?type=text&data=' + encodeURIComponent(ticket);
 *
 *   // ESC/POS raw (hex)
 *   window.location.href = 'enduroprint://print?type=escpos&data=1B401B610048656C6C6F0A';
 *
 *   // HTML como imagen
 *   const html = '<b>TICKET</b><br><table>...';
 *   window.location.href = 'enduroprint://print?type=html&data=' + encodeURIComponent(html);
 *
 *   // Imagen Base64
 *   window.location.href = 'enduroprint://print?type=image&data=' + encodeURIComponent(base64str);
 *
 * ============================================================
 */
public class PrintActivity extends Activity {

    private static final String TAG = "PrintActivity";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        handlePrintIntent(getIntent());
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        handlePrintIntent(intent);
    }

    private void handlePrintIntent(Intent intent) {
        if (intent == null) {
            finish();
            return;
        }

        Uri uri = intent.getData();
        if (uri == null) {
            Log.w(TAG, "Intent sin URI");
            finish();
            return;
        }

        // Parsear: enduroprint://print?type=text&data=...
        String type = uri.getQueryParameter("type");
        String data = uri.getQueryParameter("data");

        if (data == null || data.isEmpty()) {
            Toast.makeText(this, "Error: datos vacíos", Toast.LENGTH_SHORT).show();
            finish();
            return;
        }

        Log.d(TAG, "Print request — type: " + type + ", data length: " + data.length());

        // Determinar la acción del servicio según el tipo
        String serviceAction;
        if (type == null) type = "text";

        switch (type.toLowerCase()) {
            case "escpos":
                serviceAction = PrinterService.ACTION_PRINT_ESCPOS;
                break;
            case "html":
                serviceAction = PrinterService.ACTION_PRINT_HTML;
                break;
            case "image":
                serviceAction = PrinterService.ACTION_PRINT_IMAGE;
                break;
            case "text":
            default:
                serviceAction = PrinterService.ACTION_PRINT;
                break;
        }

        // Enviar al servicio
        Intent serviceIntent = new Intent(this, PrinterService.class);
        serviceIntent.setAction(serviceAction);
        serviceIntent.putExtra(PrinterService.EXTRA_DATA, data);
        startForegroundService(serviceIntent);

        Toast.makeText(this, "Enviando a imprimir...", Toast.LENGTH_SHORT).show();

        // Cerrar esta actividad inmediatamente (no tiene UI)
        finish();
    }
}
