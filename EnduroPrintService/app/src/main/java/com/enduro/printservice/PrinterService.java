package com.enduro.printservice;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.Message;
import android.preference.PreferenceManager;
import android.util.Base64;
import android.util.Log;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import androidx.core.app.NotificationCompat;

import com.printer.sdk.PrinterConstants;
import com.printer.sdk.PrinterConstants.Command;
import com.printer.sdk.PrinterInstance;

import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;

/**
 * ============================================================
 * EnduroPrintService — Servicio en foreground para impresión
 * en red (LAN/WiFi) con impresoras térmicas Enduro 80mm.
 *
 * Flujo:
 *  1. La app web/navegador lanza: enduroprint://print?type=text&data=...
 *  2. PrintActivity captura el Intent y llama a PrinterService
 *  3. El servicio encola el trabajo
 *  4. Un hilo dedicado saca trabajos de la cola y los imprime
 *
 * Tipos de impresión soportados:
 *  - "text"   → Texto plano con saltos de línea
 *  - "escpos" → Comandos ESC/POS en hexadecimal (ej: 1B400A...)
 *  - "html"   → HTML renderizado como bitmap e impreso como imagen
 *  - "image"  → Imagen en Base64
 * ============================================================
 */
public class PrinterService extends Service {

    private static final String TAG = "EnduroPrintService";
    private static final String CHANNEL_ID = "enduro_print_channel";
    private static final int NOTIF_ID = 1001;

    // Actions que recibe el servicio
    public static final String ACTION_START       = "com.enduro.printservice.START";
    public static final String ACTION_STOP        = "com.enduro.printservice.STOP";
    public static final String ACTION_PRINT       = "com.enduro.printservice.PRINT";
    public static final String ACTION_PRINT_ESCPOS = "com.enduro.printservice.PRINT_ESCPOS";
    public static final String ACTION_PRINT_HTML  = "com.enduro.printservice.PRINT_HTML";
    public static final String ACTION_PRINT_IMAGE = "com.enduro.printservice.PRINT_IMAGE";

    // Extras del Intent de impresión
    public static final String EXTRA_DATA     = "data";
    public static final String EXTRA_ENCODING = "encoding"; // utf-8 | gbk | latin1

    // Estado de la conexión con la impresora
    public static boolean isConnected  = false;
    public static String  statusMessage = "Servicio detenido";

    // Cola de trabajos de impresión
    private final BlockingQueue<PrintJob> printQueue = new LinkedBlockingQueue<>();
    private Thread printerThread;
    private PrinterInstance mPrinter;
    private Handler mHandler;
    private volatile boolean running = false;

    // Preferencias
    private String  printerIp;
    private int     printerPort;
    private int     paperWidth;
    private boolean autoCut;

    // ──────────────────────────────────────────────
    // Ciclo de vida del servicio
    // ──────────────────────────────────────────────

    @Override
    public void onCreate() {
        super.onCreate();
        createNotificationChannel();

        // Handler para recibir eventos de conexión del SDK
        mHandler = new Handler(Looper.getMainLooper()) {
            @Override
            public void handleMessage(Message msg) {
                switch (msg.what) {
                    case PrinterConstants.Connect.SUCCESS:
                        isConnected = true;
                        statusMessage = "Conectado";
                        updateNotification("✓ Conectado a " + printerIp);
                        Log.i(TAG, "Impresora conectada: " + printerIp);
                        break;
                    case PrinterConstants.Connect.FAILED:
                        isConnected = false;
                        statusMessage = "Error de conexión";
                        updateNotification("✗ Sin conexión — revisa la IP");
                        Log.e(TAG, "Error al conectar con la impresora");
                        break;
                    case PrinterConstants.Connect.CLOSED:
                        isConnected = false;
                        statusMessage = "Desconectado";
                        Log.i(TAG, "Conexión cerrada");
                        break;
                }
            }
        };
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null) return START_STICKY;

        loadPreferences();
        String action = intent.getAction();
        if (action == null) action = ACTION_START;

        switch (action) {
            case ACTION_STOP:
                stopSelf();
                return START_NOT_STICKY;

            case ACTION_PRINT:
                String textData = intent.getStringExtra(EXTRA_DATA);
                if (textData != null) enqueue(new PrintJob(PrintJob.TYPE_TEXT, textData));
                break;

            case ACTION_PRINT_ESCPOS:
                String hexData = intent.getStringExtra(EXTRA_DATA);
                if (hexData != null) enqueue(new PrintJob(PrintJob.TYPE_ESCPOS, hexData));
                break;

            case ACTION_PRINT_HTML:
                String htmlData = intent.getStringExtra(EXTRA_DATA);
                if (htmlData != null) enqueue(new PrintJob(PrintJob.TYPE_HTML, htmlData));
                break;

            case ACTION_PRINT_IMAGE:
                String b64Data = intent.getStringExtra(EXTRA_DATA);
                if (b64Data != null) enqueue(new PrintJob(PrintJob.TYPE_IMAGE, b64Data));
                break;

            case ACTION_START:
            default:
                break;
        }

        // Iniciar como foreground service
        startForeground(NOTIF_ID, buildNotification("Listo — esperando trabajos"));

        // Guardar preferencia de que el servicio está activo
        SharedPreferences.Editor editor = PreferenceManager
                .getDefaultSharedPreferences(this).edit();
        editor.putBoolean(PrintApp.PREF_SERVICE_ON, true).apply();

        // Iniciar hilo de impresión si no está corriendo
        if (!running) {
            running = true;
            printerThread = new Thread(this::printerLoop);
            printerThread.setDaemon(true);
            printerThread.start();
        }

        return START_STICKY; // Se reinicia automáticamente si Android lo mata
    }

    @Override
    public void onDestroy() {
        running = false;
        if (printerThread != null) printerThread.interrupt();
        disconnectPrinter();

        SharedPreferences.Editor editor = PreferenceManager
                .getDefaultSharedPreferences(this).edit();
        editor.putBoolean(PrintApp.PREF_SERVICE_ON, false).apply();

        isConnected = false;
        statusMessage = "Servicio detenido";
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) { return null; }

    // ──────────────────────────────────────────────
    // Hilo principal de impresión
    // ──────────────────────────────────────────────

    /**
     * Bucle que espera trabajos en la cola y los ejecuta en orden.
     * Corre en un hilo dedicado para no bloquear el UI.
     */
    private void printerLoop() {
        Log.i(TAG, "Hilo de impresión iniciado");
        while (running) {
            try {
                PrintJob job = printQueue.take(); // espera bloqueante
                executePrintJob(job);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        Log.i(TAG, "Hilo de impresión terminado");
    }

    private void enqueue(PrintJob job) {
        printQueue.offer(job);
        Log.d(TAG, "Trabajo encolado: " + job.type + " (" + printQueue.size() + " en cola)");
    }

    // ──────────────────────────────────────────────
    // Ejecución de trabajos de impresión
    // ──────────────────────────────────────────────

    private void executePrintJob(PrintJob job) {
        Log.i(TAG, "Ejecutando trabajo: " + job.type);
        updateNotification("Imprimiendo...");

        if (!connectPrinter()) {
            Log.e(TAG, "No se pudo conectar. Trabajo descartado.");
            updateNotification("✗ Error — no se pudo conectar");
            return;
        }

        try {
            switch (job.type) {
                case PrintJob.TYPE_TEXT:
                    printText(job.data);
                    break;
                case PrintJob.TYPE_ESCPOS:
                    printEscPos(job.data);
                    break;
                case PrintJob.TYPE_HTML:
                    printHtml(job.data);
                    break;
                case PrintJob.TYPE_IMAGE:
                    printImage(job.data);
                    break;
            }

            if (autoCut) {
                // Corte parcial: 66 = modo corte, 50 = alimentar 50 puntos antes
                mPrinter.cutPaper(66, 50);
            }

        } catch (Exception e) {
            Log.e(TAG, "Error durante impresión: " + e.getMessage(), e);
        } finally {
            disconnectPrinter();
            updateNotification(isConnected ? "✓ Conectado a " + printerIp : "Listo — esperando trabajos");
        }
    }

    // ──────────────────────────────────────────────
    // Métodos de impresión por tipo
    // ──────────────────────────────────────────────

    /**
     * Imprime texto plano con codificación correcta para español.
     * Soporta \n para nuevas líneas.
     */
    private void printText(String text) {
        mPrinter.initPrinter();
        mPrinter.setFont(0, 0, 0, 0, 0); // fuente normal
        mPrinter.setPrinter(Command.ALIGN, Command.ALIGN_LEFT);

        // Dividir en líneas y enviar. Permite control básico de formato:
        // Si la línea empieza con "##" → centrado y doble altura
        // Si la línea empieza con "#"  → centrado
        // Si la línea empieza con "---" → línea separadora
        String[] lines = text.split("\n");
        StringBuilder normal = new StringBuilder();

        for (String line : lines) {
            if (line.startsWith("##")) {
                // Volcar buffer acumulado primero
                if (normal.length() > 0) {
                    mPrinter.printText(normal.toString());
                    normal.setLength(0);
                }
                // Título doble altura centrado
                mPrinter.setPrinter(Command.ALIGN, Command.ALIGN_CENTER);
                mPrinter.setFont(0, 1, 1, 0, 0);
                mPrinter.printText(line.substring(2).trim() + "\n");
                mPrinter.setFont(0, 0, 0, 0, 0);
                mPrinter.setPrinter(Command.ALIGN, Command.ALIGN_LEFT);

            } else if (line.startsWith("#")) {
                if (normal.length() > 0) {
                    mPrinter.printText(normal.toString());
                    normal.setLength(0);
                }
                // Centrado normal
                mPrinter.setPrinter(Command.ALIGN, Command.ALIGN_CENTER);
                mPrinter.printText(line.substring(1).trim() + "\n");
                mPrinter.setPrinter(Command.ALIGN, Command.ALIGN_LEFT);

            } else if (line.startsWith("---")) {
                // Línea de guiones como separador
                int chars = (paperWidth == 58) ? 32 : 48;
                normal.append("-".repeat(chars)).append("\n");

            } else {
                normal.append(line).append("\n");
            }
        }

        // Imprimir resto acumulado
        if (normal.length() > 0) {
            mPrinter.printText(normal.toString());
        }

        // Alimentar papel al final
        mPrinter.setPrinter(Command.PRINT_AND_WAKE_PAPER_BY_LINE, 3);
    }

    /**
     * Imprime comandos ESC/POS en hexadecimal raw.
     * Útil para sistemas POS que ya generan el stream ESC/POS.
     * Formato esperado: "1B400A1B6100..." (hex sin espacios)
     */
    private void printEscPos(String hexString) {
        // Limpiar posibles espacios o 0x prefixes
        hexString = hexString.replaceAll("\\s+", "").replace("0x", "").replace("0X", "");

        if (hexString.length() % 2 != 0) {
            Log.e(TAG, "Datos ESC/POS inválidos (longitud impar)");
            return;
        }

        byte[] data = new byte[hexString.length() / 2];
        for (int i = 0; i < data.length; i++) {
            int val = Integer.parseInt(hexString.substring(i * 2, i * 2 + 2), 16);
            data[i] = (byte) val;
        }

        mPrinter.sendByteData(data);
    }

    /**
     * Renderiza HTML a un Bitmap y lo imprime como imagen.
     * Útil para tickets con formato rico: logos, tablas, QR.
     *
     * NOTA: Requiere ejecutarse en el main thread (WebView).
     * Se usa un Looper temporal para esto.
     */
    private void printHtml(final String html) {
        // El ancho de papel en píxeles: 80mm ≈ 576px a 203dpi, 58mm ≈ 384px
        final int widthPx = (paperWidth == 80) ? 576 : 384;

        // Para renderizar HTML necesitamos estar en el hilo principal
        final Object lock = new Object();
        final Bitmap[] result = {null};

        new Handler(Looper.getMainLooper()).post(() -> {
            WebView webView = new WebView(PrinterService.this);
            webView.getSettings().setJavaScriptEnabled(true);
            webView.measure(
                android.view.View.MeasureSpec.makeMeasureSpec(widthPx, android.view.View.MeasureSpec.EXACTLY),
                android.view.View.MeasureSpec.makeMeasureSpec(0, android.view.View.MeasureSpec.UNSPECIFIED)
            );
            webView.layout(0, 0, widthPx, webView.getMeasuredHeight());

            webView.setWebViewClient(new WebViewClient() {
                @Override
                public void onPageFinished(WebView view, String url) {
                    // Re-medir tras cargar contenido
                    view.measure(
                        android.view.View.MeasureSpec.makeMeasureSpec(widthPx, android.view.View.MeasureSpec.EXACTLY),
                        android.view.View.MeasureSpec.makeMeasureSpec(0, android.view.View.MeasureSpec.UNSPECIFIED)
                    );
                    view.layout(0, 0, widthPx, view.getMeasuredHeight());

                    int height = view.getMeasuredHeight();
                    if (height <= 0) height = 800;

                    Bitmap bmp = Bitmap.createBitmap(widthPx, height, Bitmap.Config.ARGB_8888);
                    Canvas canvas = new Canvas(bmp);
                    canvas.drawColor(Color.WHITE);
                    view.draw(canvas);

                    synchronized (lock) {
                        result[0] = bmp;
                        lock.notifyAll();
                    }
                }
            });

            // CSS por defecto para ticket: fuente monoespaciada, sin márgenes
            String fullHtml = "<html><head><meta charset='utf-8'>" +
                "<style>body{margin:0;padding:4px;font-family:monospace;font-size:12px;}" +
                "table{width:100%;border-collapse:collapse;}" +
                "td,th{padding:2px 4px;}" +
                "</style></head><body>" + html + "</body></html>";
            webView.loadData(fullHtml, "text/html", "UTF-8");
        });

        // Esperar el resultado del WebView (max 10 segundos)
        synchronized (lock) {
            try {
                lock.wait(10000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }

        if (result[0] != null) {
            // Convertir a blanco/negro (dithering) para impresión térmica
            Bitmap bwBitmap = toBW(result[0]);
            mPrinter.printBitmap(bwBitmap, PrinterConstants.BitmapPrintMode.MODE_SINGLE);
        } else {
            Log.e(TAG, "WebView no generó bitmap");
        }
    }

    /**
     * Imprime imagen desde Base64.
     */
    private void printImage(String base64) {
        // Quitar posible header: "data:image/png;base64,..."
        if (base64.contains(",")) {
            base64 = base64.split(",")[1];
        }
        byte[] bytes = Base64.decode(base64, Base64.DEFAULT);
        Bitmap bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.length);
        if (bmp != null) {
            // Escalar al ancho de papel
            int targetWidth = (paperWidth == 80) ? 576 : 384;
            float ratio = (float) targetWidth / bmp.getWidth();
            int targetHeight = (int) (bmp.getHeight() * ratio);
            Bitmap scaled = Bitmap.createScaledBitmap(bmp, targetWidth, targetHeight, true);
            Bitmap bwBitmap = toBW(scaled);
            mPrinter.printBitmap(bwBitmap, PrinterConstants.BitmapPrintMode.MODE_SINGLE);
        }
    }

    /**
     * Convierte un Bitmap color a blanco/negro puro
     * usando dithering Floyd-Steinberg para mejor calidad.
     */
    private Bitmap toBW(Bitmap src) {
        int w = src.getWidth();
        int h = src.getHeight();
        int[] pixels = new int[w * h];
        src.getPixels(pixels, 0, w, 0, 0, w, h);

        // Convertir a escala de grises y aplicar dithering simple
        int[] gray = new int[w * h];
        for (int i = 0; i < pixels.length; i++) {
            int r = (pixels[i] >> 16) & 0xFF;
            int g = (pixels[i] >> 8) & 0xFF;
            int b = pixels[i] & 0xFF;
            gray[i] = (int)(0.299 * r + 0.587 * g + 0.114 * b);
        }

        // Floyd-Steinberg dithering
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int idx = y * w + x;
                int oldVal = gray[idx];
                int newVal = (oldVal < 128) ? 0 : 255;
                gray[idx] = newVal;
                int err = oldVal - newVal;

                if (x + 1 < w)               gray[idx + 1]     += err * 7 / 16;
                if (y + 1 < h && x > 0)      gray[idx + w - 1] += err * 3 / 16;
                if (y + 1 < h)               gray[idx + w]     += err * 5 / 16;
                if (y + 1 < h && x + 1 < w) gray[idx + w + 1] += err * 1 / 16;
            }
        }

        Bitmap result = Bitmap.createBitmap(w, h, Bitmap.Config.RGB_565);
        for (int i = 0; i < pixels.length; i++) {
            int v = gray[i];
            pixels[i] = Color.rgb(v, v, v);
        }
        result.setPixels(pixels, 0, w, 0, 0, w, h);
        return result;
    }

    // ──────────────────────────────────────────────
    // Gestión de conexión con la impresora
    // ──────────────────────────────────────────────

    private boolean connectPrinter() {
        try {
            mPrinter = PrinterInstance.getPrinterInstance(printerIp, printerPort, mHandler);
            // openConnection es sincrónica — bloquea hasta conectar o fallar
            mPrinter.openConnection();

            // Dar tiempo al handler para procesar el evento de conexión
            Thread.sleep(800);
            return isConnected;
        } catch (Exception e) {
            Log.e(TAG, "Excepción al conectar: " + e.getMessage());
            return false;
        }
    }

    private void disconnectPrinter() {
        if (mPrinter != null) {
            try {
                mPrinter.closeConnection();
            } catch (Exception e) {
                Log.w(TAG, "Error al cerrar conexión: " + e.getMessage());
            }
        }
        isConnected = false;
    }

    // ──────────────────────────────────────────────
    // Preferencias y notificaciones
    // ──────────────────────────────────────────────

    private void loadPreferences() {
        SharedPreferences p = PreferenceManager.getDefaultSharedPreferences(this);
        printerIp   = p.getString(PrintApp.PREF_PRINTER_IP, PrintApp.DEFAULT_IP);
        printerPort = p.getInt(PrintApp.PREF_PRINTER_PORT, PrintApp.DEFAULT_PORT);
        paperWidth  = p.getInt(PrintApp.PREF_PAPER_WIDTH, PrintApp.DEFAULT_PAPER_WIDTH);
        autoCut     = p.getBoolean(PrintApp.PREF_AUTO_CUT, true);
    }

    private void createNotificationChannel() {
        NotificationChannel channel = new NotificationChannel(
            CHANNEL_ID,
            "Servicio de Impresión Enduro",
            NotificationManager.IMPORTANCE_LOW
        );
        channel.setDescription("Mantiene activo el servicio de impresión en red");
        channel.setShowBadge(false);
        NotificationManager nm = getSystemService(NotificationManager.class);
        if (nm != null) nm.createNotificationChannel(channel);
    }

    private Notification buildNotification(String text) {
        Intent openApp = new Intent(this, MainActivity.class);
        PendingIntent pi = PendingIntent.getActivity(this, 0, openApp,
            PendingIntent.FLAG_IMMUTABLE);

        return new NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Enduro Print Service")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_menu_send)
            .setContentIntent(pi)
            .setOngoing(true)
            .setSilent(true)
            .build();
    }

    private void updateNotification(String text) {
        Notification n = buildNotification(text);
        NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm != null) nm.notify(NOTIF_ID, n);
        statusMessage = text;
    }

    // ──────────────────────────────────────────────
    // Clase interna: trabajo de impresión
    // ──────────────────────────────────────────────

    static class PrintJob {
        static final int TYPE_TEXT   = 0;
        static final int TYPE_ESCPOS = 1;
        static final int TYPE_HTML   = 2;
        static final int TYPE_IMAGE  = 3;

        final int    type;
        final String data;

        PrintJob(int type, String data) {
            this.type = type;
            this.data = data;
        }
    }
}
