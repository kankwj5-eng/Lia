# Investigación de agentes pequeños para Lía

Este documento registra proyectos públicos estudiados para mejorar la arquitectura de Lía. No se incorporan dependencias automáticamente: cada idea se reimplementa de forma nativa y se mantiene detrás de las políticas de autorización de Lía.

## hanxi/droidrun-agent

- Licencia: MIT.
- Tamaño aproximado del repositorio al revisarlo: 53 KB.
- Enfoque: cliente pequeño para DroidRun Portal con herramientas de tap, swipe, screenshot, árbol de accesibilidad, estado del teléfono, apertura de aplicaciones y entrada de texto.
- Ideas útiles para Lía:
  - superficie de herramientas pequeña y explícita;
  - estado simplificado y estado completo separados;
  - herramientas con argumentos tipados;
  - no entregar al agente un shell arbitrario cuando basta una herramienta específica.

Lía ya posee equivalentes nativos para buena parte de esta superficie mediante AccessibilityService.

## The-JDdev/manusclaw-apk

- Licencia: MIT.
- Tamaño aproximado del repositorio al revisarlo: 23 KB.
- Enfoque: agente Android autónomo muy pequeño basado en AccessibilityService.
- Patrón útil:
  - observar;
  - elegir una sola acción estructurada;
  - ejecutar;
  - devolver el resultado al planificador;
  - repetir con un máximo de pasos.

Lía adopta el patrón general, pero no copia su acceso directo del modelo a AccessibilityService. En Lía, CommandAuthorizationGate permanece entre planificador y ejecución.

## haonox/PhoneAgent

- Licencia: Apache-2.0.
- Enfoque: runtime de agente visual con protocolo estricto, verificación y recuperación.
- Ideas adoptadas conceptualmente:
  - límite de pasos;
  - historial de eventos append-only;
  - detección de estancamiento;
  - recuperación con presupuesto finito;
  - evitar reintentos automáticos de acciones peligrosas;
  - separar «comando enviado» de «resultado verificado».

Estas ideas se implementan en Kotlin en `LiaMicroAgent`.

## honeynet/droidbot

- Licencia: MIT.
- Enfoque: generación inteligente de entradas y exploración para pruebas Android.
- Uso previsto en Lía: laboratorio de QA y exploración automática, no como runtime de usuario.

## Sikrid25/droidrun-rs

- Licencia: MIT.
- Enfoque: automatización Android en Rust con pipeline de árbol UI, driver grabable y más de cien pruebas.
- Idea útil: registrar acciones como una trayectoria reproducible para investigar regresiones.

## Política de incorporación

Antes de usar código de terceros:

1. comprobar licencia;
2. evitar componentes que requieran root o privilegios incompatibles con una app normal;
3. preferir APIs nativas Android en el producto;
4. mantener toda acción autónoma detrás de autorización y verificación;
5. documentar la procedencia de cualquier código incorporado directamente.
