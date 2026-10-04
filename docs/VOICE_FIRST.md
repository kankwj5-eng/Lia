# Lía: voz primero, accesibilidad primero, útil para todos

Lía debe poder utilizarse sin mirar ni tocar la pantalla. Esa condición no crea una
versión separada de la aplicación: define la calidad mínima de toda la experiencia.

## Principios

1. **Una orden completa debe poder darse por voz.**
   Si la persona dice “Lía, abre WhatsApp y busca a mamá”, el agente debe recibir la
   orden sin exigir abrir la interfaz de Lía.

2. **La pantalla es opcional, no obligatoria.**
   El chat, la consola visual y los botones sirven a quien los prefiera, pero ninguna
   tarea cotidiana debe depender exclusivamente de leer un estado visual.

3. **Feedback multimodal.**
   - vibración corta: Lía oyó la activación;
   - respuesta hablada: resultado o problema relevante;
   - vibración doble: tarea completada;
   - vibración larga: error que requiere atención.

4. **La escucha continua es local y explícita.**
   El usuario activa “Siempre atenta”. El micrófono no debe enviarse a servicios
   externos. El pipeline actual es:
   - captura local 16 kHz;
   - detector ligero de actividad de voz;
   - Whisper local solo cuando existe un segmento de habla;
   - detección de “Lía”;
   - identidad de voz opcional;
   - envío al agente.

5. **Accesibilidad primero no significa público limitado.**
   Lía se diseña para personas ciegas, con baja visión, movilidad reducida o dificultad
   para manipular el teléfono, pero la misma aplicación debe ser atractiva y útil para
   cualquier persona que quiera un asistente Android local y agentic.

## Modos de entrada

```
“Lía …”            ─┐
botón Hablar        ─┤
burbuja             ─┤
asistente del sistema├→ misma conversación → mismo coordinador → mismas herramientas
chat escrito        ─┤
hardware/headset    ─┘
```

No deben existir motores separados para “modo accesible” y “modo normal”.

## Escucha persistente

Cuando Lía es el asistente del sistema, Android mantiene su
`VoiceInteractionService` disponible. El modo atento vive ahí y debe permanecer
ligero. El LLM, OCR, visión y otras operaciones pesadas solo se activan después de
una orden.

El modo atento requiere:
- permiso de micrófono;
- reconocimiento offline instalado;
- Lía seleccionada como asistente del sistema;
- preferencia “Siempre atenta” habilitada.

Si la protección por identidad está habilitada también requiere perfil y modelo de
voz válidos.

## Privacidad

La frase de activación y la transcripción continua se procesan localmente. Los
buffers de audio se sobrescriben después de procesarlos y no se guardan como
historial. La conversación persistida contiene texto acotado, no audio ni
razonamiento interno.

## Evolución

El detector actual usa un gate acústico ligero y Whisper local después de detectar
habla. La siguiente optimización será reemplazar la mayor parte de esas
transcripciones por un keyword spotter pequeño dedicado a “Lía”, reduciendo CPU y
batería sin cambiar el contrato de `voice/listening/`.

Ese cambio debe poder hacerse dentro de la carpeta de escucha sin modificar
orquestación, herramientas, conversación o UI.
