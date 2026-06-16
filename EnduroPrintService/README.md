# 🖨 Enduro Print Service para Android

Servicio de impresión en red LAN/WiFi para la impresora térmica **Enduro 80mm**, como alternativa gratuita a RawBT.

---

## ¿Qué hace esta app?

- Corre como **servicio en background** (igual que RawBT)
- Se reinicia automáticamente al encender la tablet
- Recibe trabajos de impresión desde el **navegador o tu app web**
- Usa el **SDK oficial del fabricante** (el JAR que vino en la USB)
- Soporta texto, HTML, imágenes y comandos ESC/POS raw

---

## Estructura del proyecto

```
EnduroPrintService/
├── app/
│   ├── libs/
│   │   └── printersdkv5.7.0.jar   ← SDK oficial Enduro (ya incluido)
│   └── src/main/java/com/enduro/printservice/
│       ├── PrintApp.java           ← Application class
│       ├── MainActivity.java       ← Pantalla de configuración
│       ├── PrintActivity.java      ← Intercepta el URL scheme
│       ├── PrinterService.java     ← Servicio principal (lógica de impresión)
│       └── BootReceiver.java       ← Auto-inicio al encender dispositivo
├── enduro-print.js                 ← Helper JS para tu app web
└── README.md
```

---

## Cómo compilar

### Requisitos
- Android Studio Hedgehog o superior
- JDK 17
- Android SDK 34

### Pasos
1. Abre Android Studio → `File > Open` → selecciona la carpeta `EnduroPrintService`
2. Espera que Gradle sincronice las dependencias
3. Conecta la tablet Android vía USB
4. Presiona **Run** (▶) o `Build > Build APK`
5. Instala la APK en la tablet

---

## Configuración inicial

1. Abre la app **Enduro Print Service** en la tablet
2. Ingresa la **IP de la impresora** (la encuentras en la impresora: imprimir página de autotest)
3. Puerto: `9100` (no cambiar)
4. Ancho de papel: `80mm`
5. Activa **Corte automático** si tu modelo lo soporta
6. Presiona **GUARDAR CONFIGURACIÓN**
7. Activa el interruptor **Servicio activo**
8. Presiona **IMPRIMIR PRUEBA** para verificar

---

## Integración en tu app/POS web

### Opción 1: Con el helper JS (recomendado)

Copia `enduro-print.js` a tu proyecto y úsalo:

```html
<script src="enduro-print.js"></script>
<script>
// Ticket completo
EnduroPrint.ticket({
  storeName: 'MI TIENDA',
  receiptNumber: '00042',
  items: [
    { name: 'Producto A', qty: 2, price: 50.00 },
    { name: 'Producto B', qty: 1, price: 30.00 },
  ],
  total: 130.00,
  paid: 200.00
});
</script>
```

### Opción 2: URL scheme directo

```javascript
// Texto con formato
const ticket = `## MI TIENDA\n---\nProducto A   $50.00\n---\n## TOTAL: $50.00`;
window.location.href = 'enduroprint://print?type=text&data=' + encodeURIComponent(ticket);

// HTML como imagen
window.location.href = 'enduroprint://print?type=html&data=' + encodeURIComponent('<h2>Ticket</h2>');
```

### Formato de texto

| Prefijo | Efecto |
|---------|--------|
| `## Texto` | Título doble altura centrado |
| `# Texto` | Texto centrado |
| `---` | Línea separadora de guiones |
| (sin prefijo) | Texto normal alineado a la izquierda |

---

## Tipos de impresión

| `type=` | Descripción |
|---------|-------------|
| `text` | Texto plano con formato simple |
| `escpos` | Comandos ESC/POS en hexadecimal raw |
| `html` | HTML renderizado como imagen (WebView) |
| `image` | Imagen en Base64 |

---

## Cómo funciona el servicio

```
Tu app web (Chrome/WebView)
        ↓
  enduroprint://print?type=text&data=...
        ↓
  PrintActivity (intercepta, parsea, envía)
        ↓
  PrinterService (cola de trabajos, hilo dedicado)
        ↓
  TCP Socket → IP:9100
        ↓
  Impresora Enduro 80mm
```

El `PrinterService` usa `START_STICKY` — si Android lo mata por memoria, se reinicia automáticamente.

---

## Diferencias con RawBT

| Característica | Esta app | RawBT |
|----------------|----------|-------|
| Costo | Gratuita | Pago (opciones limitadas) |
| SDK | Oficial del fabricante | Genérico |
| Impresión HTML | ✅ | Con versión de pago |
| Auto-inicio | ✅ | ✅ |
| Código fuente | Tuyo | Cerrado |
| Impresoras soportadas | Enduro | Múltiples marcas |

---

## Solución de problemas

**La impresora no responde:**
- Verifica que la tablet y la impresora estén en la **misma red WiFi**
- Imprime la página de autotest (botón de la impresora al encender) para ver la IP asignada
- Prueba hacer ping desde otra PC: `ping 192.168.x.x`

**El URL scheme no abre la app:**
- Verifica que la app esté instalada y el servicio activo
- Algunos navegadores bloquean redirecciones a esquemas personalizados; usa Chrome

**Android mata el servicio:**
- Ve a `Ajustes > Batería > Optimización de batería` y excluye "Enduro Print Service"
- En MIUI/Samsung: busca "apps en segundo plano" y permite que corra libremente
