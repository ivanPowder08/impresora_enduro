package com.enduro.printservice;

import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.preference.PreferenceManager;
import android.view.View;
import android.widget.Button;
import android.widget.CompoundButton;
import android.widget.EditText;
import android.widget.RadioGroup;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

/**
 * Pantalla principal de configuración.
 * Permite:
 *  - Configurar IP y puerto de la impresora
 *  - Elegir ancho de papel (58mm / 80mm)
 *  - Activar/desactivar corte automático
 *  - Iniciar/detener el servicio de impresión
 *  - Ver el estado de conexión en tiempo real
 *  - Hacer una impresión de prueba
 */
public class MainActivity extends AppCompatActivity {

    private EditText etIp, etPort;
    private RadioGroup rgPaperWidth;
    private Switch swAutoCut, swService;
    private TextView tvStatus;
    private Button btnTestPrint, btnSave;

    private SharedPreferences prefs;
    private final Handler statusHandler = new Handler(Looper.getMainLooper());

    // Actualiza el estado cada segundo
    private final Runnable statusUpdater = new Runnable() {
        @Override
        public void run() {
            updateStatusDisplay();
            statusHandler.postDelayed(this, 1000);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        prefs = PreferenceManager.getDefaultSharedPreferences(this);
        initViews();
        loadSettings();
    }

    @Override
    protected void onResume() {
        super.onResume();
        statusHandler.post(statusUpdater);
    }

    @Override
    protected void onPause() {
        super.onPause();
        statusHandler.removeCallbacks(statusUpdater);
    }

    private void initViews() {
        etIp        = findViewById(R.id.et_ip);
        etPort      = findViewById(R.id.et_port);
        rgPaperWidth = findViewById(R.id.rg_paper_width);
        swAutoCut   = findViewById(R.id.sw_auto_cut);
        swService   = findViewById(R.id.sw_service);
        tvStatus    = findViewById(R.id.tv_status);
        btnTestPrint = findViewById(R.id.btn_test_print);
        btnSave     = findViewById(R.id.btn_save);

        btnSave.setOnClickListener(v -> saveSettings());

        btnTestPrint.setOnClickListener(v -> sendTestPrint());

        swService.setOnCheckedChangeListener((CompoundButton btn, boolean isChecked) -> {
            if (isChecked) {
                startPrinterService();
            } else {
                stopPrinterService();
            }
        });
    }

    private void loadSettings() {
        etIp.setText(prefs.getString(PrintApp.PREF_PRINTER_IP, PrintApp.DEFAULT_IP));
        etPort.setText(String.valueOf(prefs.getInt(PrintApp.PREF_PRINTER_PORT, PrintApp.DEFAULT_PORT)));
        swAutoCut.setChecked(prefs.getBoolean(PrintApp.PREF_AUTO_CUT, true));
        swService.setChecked(prefs.getBoolean(PrintApp.PREF_SERVICE_ON, false));

        int width = prefs.getInt(PrintApp.PREF_PAPER_WIDTH, 80);
        rgPaperWidth.check(width == 58 ? R.id.rb_58mm : R.id.rb_80mm);
    }

    private void saveSettings() {
        String ip = etIp.getText().toString().trim();
        String portStr = etPort.getText().toString().trim();

        if (ip.isEmpty()) {
            Toast.makeText(this, "Ingresa la IP de la impresora", Toast.LENGTH_SHORT).show();
            return;
        }

        int port;
        try {
            port = Integer.parseInt(portStr);
        } catch (NumberFormatException e) {
            port = PrintApp.DEFAULT_PORT;
        }

        int width = (rgPaperWidth.getCheckedRadioButtonId() == R.id.rb_58mm) ? 58 : 80;

        prefs.edit()
            .putString(PrintApp.PREF_PRINTER_IP, ip)
            .putInt(PrintApp.PREF_PRINTER_PORT, port)
            .putInt(PrintApp.PREF_PAPER_WIDTH, width)
            .putBoolean(PrintApp.PREF_AUTO_CUT, swAutoCut.isChecked())
            .apply();

        Toast.makeText(this, "Configuración guardada", Toast.LENGTH_SHORT).show();

        // Reiniciar servicio para aplicar nueva config
        if (prefs.getBoolean(PrintApp.PREF_SERVICE_ON, false)) {
            stopPrinterService();
            startPrinterService();
        }
    }

    private void startPrinterService() {
        Intent i = new Intent(this, PrinterService.class);
        i.setAction(PrinterService.ACTION_START);
        startForegroundService(i);
    }

    private void stopPrinterService() {
        Intent i = new Intent(this, PrinterService.class);
        i.setAction(PrinterService.ACTION_STOP);
        startService(i);
    }

    private void sendTestPrint() {
        // Ticket de prueba con todos los tipos de formato
        String ticket =
            "## ENDURO PRINT SERVICE\n" +
            "# Impresora Térmica 80mm\n" +
            "---\n" +
            "Prueba de texto normal\n" +
            "IP: " + prefs.getString(PrintApp.PREF_PRINTER_IP, "-") + "\n" +
            "Papel: " + prefs.getInt(PrintApp.PREF_PAPER_WIDTH, 80) + "mm\n" +
            "---\n" +
            "# ¡Funcionando correctamente!\n" +
            "\n\n";

        Intent i = new Intent(this, PrinterService.class);
        i.setAction(PrinterService.ACTION_PRINT);
        i.putExtra(PrinterService.EXTRA_DATA, ticket);
        startForegroundService(i);

        Toast.makeText(this, "Enviando prueba...", Toast.LENGTH_SHORT).show();
    }

    private void updateStatusDisplay() {
        boolean serviceOn = prefs.getBoolean(PrintApp.PREF_SERVICE_ON, false);

        swService.setOnCheckedChangeListener(null); // evitar bucle
        swService.setChecked(serviceOn);
        swService.setOnCheckedChangeListener((btn, isChecked) -> {
            if (isChecked) startPrinterService(); else stopPrinterService();
        });

        String status = PrinterService.statusMessage;
        tvStatus.setText(status);

        if (PrinterService.isConnected) {
            tvStatus.setTextColor(getColor(R.color.green));
        } else if (serviceOn) {
            tvStatus.setTextColor(getColor(R.color.orange));
        } else {
            tvStatus.setTextColor(getColor(R.color.gray));
        }
    }
}
