# Identidad de voz de Lía

## Enrolamiento

La configuración pide entre 3 y 5 frases. Cada muestra se captura en PCM mono a 16 kHz, se recortan silencios y sherpa-onnx CAM++ produce un speaker embedding.

Lía compara todas las muestras entre sí para evitar guardar un perfil incoherente.

## Almacenamiento

El audio de configuración no se persiste. Los embeddings se cifran con AES-256-GCM y la clave se crea dentro de Android Keystore.

Modelo inicial: 3dspeaker_speech_campplus_sv_en_voxceleb_16k.onnx.

SHA-256 esperado:

357a834f702b80161e5b981182c038e18553c1f2ca752ed6cec2052365d4129b

## Verificación

Una orden se convierte en un embedding y se compara contra todas las muestras registradas. Se usa más de una plantilla para corroborar.

El umbral actual es provisional y debe calibrarse con voces, ruido, distancias y teléfonos reales.

## Anti-replay

Pendiente para la siguiente etapa: VAD, desafío de frase aleatoria para alto riesgo, verificación de contenido e identidad simultáneos y segundo factor del dispositivo para dinero, credenciales o acciones irreversibles.
