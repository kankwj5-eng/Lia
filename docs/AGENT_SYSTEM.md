# Arquitectura agentic de Lía

Este documento define cómo debe crecer Lía sin convertirse en una colección de parches.

## Principio central

Lía usa un **cerebro local compartido** y un **coordinador único**. Los agentes especializados no son procesos autónomos sin límite: son perfiles de trabajo con instrucciones, herramientas y presupuestos distintos.

Flujo estable:

```
entrada (chat/voz/burbuja)
        ↓
conversation/        contexto reciente y limitado
        ↓
agent/orchestration/ selección de especialista y coordinación
        ↓
agent/planning/      construcción de contexto y siguiente acción
        ↓
agent/tools/         contrato, validación, riesgo y routing
        ↓
agent/runtime/       ciclo observar → actuar → verificar → recuperar
        ↓
Android / visión / voz / sistema
```

## Lo aprendido de sistemas avanzados

### 1. Un coordinador debe conservar la propiedad

Para Lía usamos el patrón manager + especialistas acotados. Un especialista existe solo cuando necesita instrucciones, herramientas o políticas diferentes.

No se permite delegación recursiva ilimitada. Lía no debe crear cadenas de agentes que creen otros agentes.

### 2. Herramientas pequeñas en contexto, catálogo grande fuera de contexto

El catálogo global puede crecer, pero el modelo solo ve las herramientas relevantes al agente activo.

Ejemplo:

- Visión: pantalla, OCR, cámara, voz y finalizar.
- Comunicación: pantalla, apps, mensajes, notificaciones y llamadas.
- Dispositivo: linterna, volumen, multimedia, conectividad, alarmas.
- Navegación: árbol de UI, abrir apps, click, texto, scroll y web.
- General: coordinador para tareas mixtas.

El protocolo valida nuevamente el allow-list. Ocultar una herramienta del prompt no es una medida de seguridad suficiente.

### 3. Contexto como recurso limitado

Nunca introducir todo el historial, todas las herramientas o todas las observaciones al modelo.

`agent/context/AgentContextPolicy.kt` concentra los presupuestos de contexto.

`conversation/ConversationStore.kt` conserva una historia local acotada. Solo una ventana pequeña y reciente llega al planificador.

Para tareas largas se debe evolucionar hacia:

1. historial reciente;
2. resumen/compaction;
3. notas estructuradas de objetivo y progreso;
4. recuperación bajo demanda.

### 4. Verificar el mundo, no confiar en que el modelo diga “listo”

Una tarea termina porque el estado observable confirma progreso, no porque el LLM declare éxito.

Toda herramienta que cambia pantalla debe observar de nuevo.

Las acciones sensibles o irreversibles pasan por autorización determinista.

### 5. El modelo piensa; la capa determinista manda

El LLM propone una llamada estructurada.

```
modelo
  ↓
StrictToolProtocol
  ↓
perfil del agente
  ↓
EffectiveToolRiskPolicy
  ↓
CommandAuthorizationGate
  ↓
executor
```

Nunca se entrega al modelo acceso directo a Android.

## Estructura objetivo

```
org/lia/accessibility/
├── accessibility/       árbol de pantalla, gestos, AccessibilityService
├── agent/
│   ├── context/         presupuestos y compactación
│   ├── orchestration/   coordinador, perfiles, routing entre agentes
│   ├── planning/        prompts y planificador local
│   ├── runtime/         loop agentic, trayectorias y recuperación
│   └── tools/           catálogo, protocolo, routing y ejecutores
├── ai/                  motores GGUF/llama.cpp, LiteRT y modelos
├── conversation/        historial y memoria conversacional
├── permissions/         inspección/navegación de permisos Android
├── vision/              percepción de pantalla y mundo real
├── voice/               STT, TTS e identidad de voz
├── notifications/       lectura y respuesta
├── system/              operaciones del sistema Android
├── location/            contexto geográfico
├── safety/              accesos físicos y SOS
├── security/            autorización y políticas deterministas
└── ui/                  presentación; no debe contener lógica del agente
```

## Reglas para cambios futuros

1. No añadir lógica agentic a `MainActivity`.
2. No crear una herramienta sin prueba de protocolo/routing.
3. No crear un agente nuevo si un perfil existente puede resolver la tarea con un skill o herramienta.
4. No cargar todo el catálogo de herramientas en cada prompt.
5. No persistir razonamientos internos del modelo.
6. Persistir solo estado útil: objetivos, resultados, eventos estructurados y conversación acotada.
7. Una acción sensible no puede degradarse a rutinaria por decisión del LLM.
8. Cada cambio estructural debe dejar unit tests, lint y emulator lab verdes.
9. Los módulos deben depender hacia abajo; UI no debe convertirse en el coordinador.
10. Antes de ampliar autonomía, ampliar observabilidad, verificaciones y límites.

## Próximas capas

- mover `tools` y `runtime` a sus paquetes definitivos;
- BrainManager para mantener un modelo local caliente y controlar memoria;
- resumen/compaction de conversación;
- timeline visible de agente sin exponer razonamiento privado;
- skills cargables bajo demanda;
- visión que devuelve resultados directamente al runtime;
- reanudación segura después de una autorización;
- suite de evals de tareas Android reproducibles.
