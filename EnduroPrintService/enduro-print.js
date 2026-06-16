/**
 * ============================================================
 * enduro-print.js — Helper para integrar impresión desde tu POS web
 *
 * Copia este archivo a tu proyecto web y úsalo así:
 *   <script src="enduro-print.js"></script>
 *   EnduroPrint.text(miTicket);
 * ============================================================
 */

const EnduroPrint = (() => {

  const SCHEME = 'enduroprint://print';

  /**
   * Navega al URL scheme para disparar la impresión.
   * Funciona desde Chrome/WebView en Android con la app instalada.
   */
  function dispatch(type, data) {
    const url = `${SCHEME}?type=${type}&data=${encodeURIComponent(data)}`;
    window.location.href = url;
  }

  /**
   * Imprime texto plano con mini-formato:
   *
   * ##Texto  → Título doble altura centrado
   * #Texto   → Texto centrado
   * ---      → Línea separadora
   *
   * Ejemplo:
   *   EnduroPrint.text(`
   *     ## MI TIENDA
   *     ---
   *     Producto A        $10.00
   *     Producto B         $5.00
   *     ---
   *     ## TOTAL:         $15.00
   *   `);
   */
  function text(content) {
    dispatch('text', content);
  }

  /**
   * Imprime comandos ESC/POS en formato hexadecimal.
   * Útil si tu sistema ya genera el stream ESC/POS.
   *
   * Ejemplo:
   *   // 1B40 = init, 1B61 01 = centrar, texto, 0A = newline
   *   EnduroPrint.escpos('1B401B610148656C6C6F0A');
   */
  function escpos(hexString) {
    dispatch('escpos', hexString);
  }

  /**
   * Renderiza HTML como imagen y lo imprime.
   * Ideal para tickets con tablas, logos o formato rico.
   *
   * Ejemplo:
   *   EnduroPrint.html(`
   *     <h2 style="text-align:center">MI TIENDA</h2>
   *     <table>
   *       <tr><td>Producto A</td><td>$10.00</td></tr>
   *       <tr><td>Producto B</td><td> $5.00</td></tr>
   *     </table>
   *     <hr>
   *     <h3 style="text-align:right">Total: $15.00</h3>
   *   `);
   */
  function html(htmlContent) {
    dispatch('html', htmlContent);
  }

  /**
   * Imprime una imagen desde Base64 o URL de data.
   * Escala automáticamente al ancho del papel configurado.
   *
   * Ejemplo con canvas:
   *   const canvas = document.getElementById('myCanvas');
   *   EnduroPrint.image(canvas.toDataURL('image/png'));
   *
   * Ejemplo con base64 directo:
   *   EnduroPrint.image('data:image/png;base64,iVBORw0KGgo...');
   */
  function image(base64OrDataUrl) {
    dispatch('image', base64OrDataUrl);
  }

  /**
   * Genera un ticket típico de POS como texto formateado.
   *
   * @param {Object} options
   * @param {string} options.storeName     - Nombre del negocio
   * @param {string} options.receiptNumber - Número de ticket
   * @param {string} options.date          - Fecha/hora
   * @param {Array}  options.items         - [{name, qty, price}]
   * @param {number} options.total
   * @param {number} options.paid          - Monto recibido
   * @param {string} options.footer        - Texto de pie (opcional)
   */
  function ticket(options) {
    const {
      storeName = 'MI TIENDA',
      receiptNumber = '',
      date = new Date().toLocaleString('es-MX'),
      items = [],
      total = 0,
      paid = 0,
      footer = 'Gracias por su compra'
    } = options;

    const change = paid - total;
    const SEP = '---';
    const W = 32; // caracteres por línea en 80mm

    function padLine(left, right) {
      const space = W - left.length - right.length;
      return left + ' '.repeat(Math.max(1, space)) + right;
    }

    let lines = [
      `## ${storeName}`,
      SEP,
    ];

    if (receiptNumber) lines.push(`Ticket: ${receiptNumber}`);
    lines.push(`Fecha:  ${date}`);
    lines.push(SEP);

    for (const item of items) {
      const priceStr = `$${item.price.toFixed(2)}`;
      const nameWithQty = `${item.qty}x ${item.name}`;
      lines.push(padLine(nameWithQty, priceStr));
    }

    lines.push(SEP);
    lines.push(padLine('TOTAL:', `$${total.toFixed(2)}`));
    if (paid > 0) {
      lines.push(padLine('PAGÓ:', `$${paid.toFixed(2)}`));
      lines.push(padLine('CAMBIO:', `$${Math.max(0, change).toFixed(2)}`));
    }

    lines.push(SEP);
    lines.push(`# ${footer}`);
    lines.push('');
    lines.push('');

    text(lines.join('\n'));
  }

  return { text, escpos, html, image, ticket };

})();

// ============================================================
// EJEMPLOS DE USO
// ============================================================
/*

// 1. Ticket de venta completo (más fácil)
EnduroPrint.ticket({
  storeName: 'TACOS EL REY',
  receiptNumber: '00042',
  date: '15/06/2026 13:45',
  items: [
    { name: 'Taco de pastor', qty: 3, price: 18.00 },
    { name: 'Agua de horchata', qty: 1, price: 20.00 },
  ],
  total: 74.00,
  paid: 100.00,
  footer: '¡Hasta pronto!'
});

// 2. Texto simple
EnduroPrint.text('## HOLA MUNDO\n---\nEsto funciona!\n\n');

// 3. HTML rico
EnduroPrint.html('<h2>Recibo</h2><p>Gracias por comprar.</p>');

// 4. ESC/POS raw
EnduroPrint.escpos('1B40' + '1B610148656C6C6F0A'); // init + center + "Hello\n"

*/
