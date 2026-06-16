# Mantener el SDK de la impresora intacto
-keep class com.printer.sdk.** { *; }
-keep class com.printer.sdk.PrinterInstance { *; }
-keep class com.printer.sdk.PrinterConstants { *; }
-keep class com.printer.sdk.Table { *; }
-dontwarn com.printer.sdk.**
