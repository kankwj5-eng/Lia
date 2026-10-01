# Arquitectura de Lía

Lía mantiene un ciclo de agente, no una colección de macros:

voz -> identidad -> intención -> percepción -> plan -> autorización -> acción -> verificación -> respuesta

## Identidad y voz

La persona registra de 3 a 5 muestras. sherpa-onnx genera embeddings de hablante y Lía los guarda cifrados con Android Keystore.

Toda orden autónoma pasa por identidad de voz. Las acciones sensibles agregan otra comprobación y las irreversibles requieren confirmación explícita.

La biometría de voz no se considera una defensa absoluta contra grabaciones o clonación. La fase siguiente añade desafío dinámico para operaciones de alto riesgo.

## Percepción de pantalla

Orden de preferencia:

1. AccessibilityNodeInfo.
2. Texto, roles, estados y coordenadas.
3. OCR.
4. Visión multimodal cuando sea necesaria.

## Visión del mundo real

CameraX entrega imágenes a una canalización local. Ya existe un analizador bajo demanda con OCR latino local, códigos de barras, color aproximado y estimación de iluminación.

Próximos modelos especializados: billetes de córdobas y dólares, empaques sin código y descripción general de escenas.

## Ubicación

Lía usa APIs nativas Android y Google Location Services. Termux no es una dependencia del producto.

LocationContextProvider obtiene coordenadas, precisión y solicita geocodificación inversa. La dirección textual puede depender del proveedor disponible en el teléfono.

Lugares cercanos y transporte en tiempo real requieren una fuente de datos adicional al GPS.

## Seguridad física

AccessibilityService solicita filtrado de teclas y detecta tres pulsaciones rápidas de volumen abajo como SOS. Botones de auricular o media-play-pause se reservan para activación discreta de Lía.

El evento SOS está separado del envío final hasta configurar contactos y permisos de emergencia.

## Hápticos

Una vibración corta significa escuchando, dos cortas tarea completada, una larga error y un patrón fuerte SOS.

## Motor local

Los módulos de voz y lenguaje dependen de interfaces. Candidatos: sherpa-onnx, whisper.cpp, llama.cpp, MNN y LiteRT-LM.

## Ejecutor Android

AccessibilityService ya expone Back, Home, Recents, tap y swipe. El siguiente paso es operar AccessibilityNodeInfo para localizar, pulsar, escribir y verificar cambios.

El modelo nunca recibe acceso directo irrestricto: CommandAuthorizationGate valida antes de ejecutar.
