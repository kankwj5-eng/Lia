# Visión y contexto del mundo real

## Ya cableado

WorldVisionActivity usa CameraX. El análisis se activa bajo demanda para ahorrar batería y evitar narración constante. Puede leer texto impreso, códigos de barras, estimar el color central y la iluminación.

LocationContextProvider obtiene ubicación actual y precisión e intenta producir una dirección legible mediante Geocoder.

DeviceStatusMonitor entrega batería, estado de carga e internet validado.

HardwareShortcutDetector reconoce triple volumen-abajo como SOS y botones media/headset como activación discreta. HapticFeedback define patrones diferenciados.

## Billetes

No se debe identificar dinero solamente por OCR o color. Se añadirá un clasificador entrenado y probado específicamente con Córdoba nicaragüense y dólar estadounidense, incluyendo anverso, reverso, billetes doblados, gastados y condiciones variables de luz.

Si la confianza no es suficiente, Lía debe pedir mover o volver a enfocar el billete en vez de adivinar.

## Productos

El código de barras se detecta offline, pero convertirlo en nombre comercial requiere una base de datos. Habrá caché local, consulta remota opcional y visión/OCR para empaques sin código conocido.

## Transporte

GPS no sabe qué autobús se aproxima. Se necesitan feeds GTFS/GTFS-Realtime cuando existan, o reconocimiento visual del número/rótulo y una base de rutas.

## Geocercas

Android permite geocercas de entrada y salida. Deben configurarse explícitamente y con radios tolerantes al error GPS para reducir avisos falsos.

## Privacidad

La cámara no graba continuamente ni almacena imágenes por defecto. Ubicación y contactos de emergencia se guardarán solamente cuando la persona lo configure.
