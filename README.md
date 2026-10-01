# Lía

**Lía — Tus ojos. Tus manos. Tu libertad.**

Lía es un agente de accesibilidad para Android diseñado para convertir el teléfono en una interfaz conversacional: escuchar una intención, comprender la pantalla, actuar dentro de aplicaciones y confirmar el resultado por voz.

## Primer objetivo técnico

La primera etapa implementa una **identidad de voz local**. Durante la configuración, la persona registra varias muestras de su voz. Lía crea embeddings de hablante en el propio dispositivo y, antes de aceptar una orden, verifica que la voz corresponda al perfil registrado.

La verificación de voz es una capa de seguridad, no una garantía absoluta: grabaciones o voces sintetizadas pueden intentar engañar a cualquier sistema biométrico. Por eso Lía separará las acciones normales de las acciones sensibles y añadirá comprobaciones adicionales cuando corresponda.

## Principios

- Local-first y privacidad por defecto.
- Accesibilidad desde el diseño, no añadida al final.
- Interfaz y voz en español.
- Confirmación antes de acciones irreversibles o sensibles.
- Recuperación de errores: observar, actuar, verificar y replanificar.
- Arquitectura modular para voz, percepción, planificación y ejecución Android.

## Estado

🚧 Desarrollo inicial.
