# Lía

**Lía — Tus ojos. Tus manos. Tu libertad.**

Lía es un agente de accesibilidad para Android pensado para convertir el teléfono y su entorno en una interfaz conversacional: escuchar una intención, reconocer a la persona autorizada, comprender la pantalla y el mundo físico, actuar y verificar el resultado.

## Lo que ya existe en el repositorio

### Identidad de voz local

- registro de 3 a 5 muestras;
- embeddings de hablante con sherpa-onnx CAM++;
- comprobación de coherencia entre muestras;
- cifrado AES-256-GCM con clave en Android Keystore;
- descarga del modelo con verificación SHA-256;
- prueba de reconocimiento de la voz registrada;
- puerta central de autorización para impedir que órdenes no verificadas lleguen al ejecutor.

### Visión del mundo real

- CameraX con cámara trasera;
- OCR latino local;
- códigos de barras offline;
- estimación de color;
- estimación de iluminación;
- análisis bajo demanda para reducir batería y evitar narración continua.

### Contexto y seguridad física

- ubicación actual mediante APIs nativas de Android;
- geocodificación inversa cuando el proveedor la permite;
- geocercas para lugares seguros;
- batería y conectividad;
- alerta interna de batería baja y pérdida de internet;
- triple volumen-abajo como disparador SOS;
- botón media/headset como activador compatible con clickers Bluetooth;
- patrones hápticos para escuchando, completado, error y SOS.

### Cerebro local sin API

- importación desde el selector de archivos de un modelo que ya esté descargado;
- detección automática de **GGUF** o **LiteRT-LM** por la cabecera real del archivo;
- GGUF se ejecuta con llama.cpp y LiteRT-LM con LiteRT;
- Qwen y otros modelos GGUF quedan detrás de la misma interfaz de agente;
- selección automática del motor: el usuario no configura proveedor, endpoint ni API key;
- el modelo propone herramientas y la capa determinista de Lía valida antes de ejecutar.

### Control Android

El AccessibilityService ya está preparado para:

- Back;
- Home;
- Recents;
- tap;
- swipe;
- lectura futura de AccessibilityNodeInfo;
- atajos físicos globales.

## Arquitectura

Flujo central:

voz/chat/burbuja -> intención -> percepción -> cerebro local -> autorización -> acción -> verificación -> respuesta

Consulta:

- docs/ARCHITECTURE.md
- docs/AGENT_SYSTEM.md
- docs/VOICE_FIRST.md
- docs/VOICE_IDENTITY.md
- docs/REAL_WORLD.md

## En desarrollo

- palabra de activación local “Lía”;
- VAD y STT local;
- TTS local;
- ejecutor semántico sobre AccessibilityNodeInfo;
- WhatsApp, llamadas, música y aplicaciones generales;
- clasificador de billetes de córdobas y dólares;
- identificación de productos sin código conocido;
- descripción visual general;
- contactos de emergencia y envío SOS;
- chat escrito, hoja inferior y burbuja accesible conectados al mismo cerebro local;
- anti-replay reforzado para voz.

## Principios

- local-first y privacidad por defecto;
- accesibilidad desde el diseño;
- español como idioma principal;
- no anunciar éxito sin verificarlo;
- no adivinar en dinero, ubicación o acciones sensibles;
- acciones irreversibles requieren confirmación;
- el modelo de IA propone, una capa determinista autoriza.

## Estado

🚧 Prototipo Android en desarrollo activo con compilación y pruebas automáticas en GitHub Actions.
