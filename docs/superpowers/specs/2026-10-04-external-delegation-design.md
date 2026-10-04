# Lía: delegación de investigación y documentos

Estado: propuesta concreta para revisión; todavía no implementada.
Base inspeccionada: `feature/agent-console-permissions`, commit
`1d7b44a560cd2caccd467eec94004077061749c3`, PR #2.

## Objetivo y alcance

Una persona dice «Lía, investiga este tema y hazme un PDF». Lía coordina
la investigación, conserva la tarea durante interrupciones, genera un documento
verificable y anuncia el resultado por voz, sin exigir abrir ChatGPT.
El cerebro local mantiene el control y los cinco perfiles existentes.
La integración externa es opcional: las capacidades Android locales siguen
funcionando cuando no hay proveedor configurado o conexión.

Primer incremento: contratos, máquina de estados persistente, autorización,
proveedor simulado para pruebas, PDF local y flujo accesible. Segundo incremento:
adaptador real de OpenAI con búsqueda web, configurado y probado con credenciales.
Un resultado simulado nunca se presenta al usuario como investigación real.
MCP bidireccional, AppFunctions, wake-word y BrainManager son trabajos separados.

## Hechos encontrados en el código

- `LiaAgentCoordinator` selecciona perfil, crea modelo por ejecución y lo cierra.
- `LiaLocalAgentRuntime` exige observar la pantalla en cada paso y retorna
  `NeedsAuthorization`; no ofrece un registro durable de trabajos externos.
- `AuthorizedToolExecutor` ya separa autorización, router y ejecución asíncrona.
- `StrictToolProtocol` y `LiaAgentProfiles` limitan herramientas por especialista.
- `ActionRisk.SENSITIVE` exige segundo factor. No basta marcarlo en el catálogo:
  el nuevo flujo debe recibir y validar la autorización antes de enviar datos.
- `ConversationStore` guarda conversación breve; no debe convertirse en almacén
  de tareas ni credenciales.

## Distribución de responsabilidades

Dentro de `org.lia.accessibility`:

| Módulo | Responsabilidad |
| --- | --- |
| `agent/delegation/` | Contratos, capacidades, proveedor, política y coordinación de trabajos. |
| `agent/delegation/storage/` | Registro durable, versiones y transiciones atómicas. |
| `documents/` | Renderizado PDF, validación, almacenamiento privado y apertura. |
| `integrations/openai/` | Transporte autenticado, búsqueda y normalización de respuestas. |
| `agent/tools/` | Catálogo, protocolo, riesgos, router y ejecutor de las nuevas herramientas. |
| `agent/runtime/` | Pausa/reanudación y seguimiento de trabajos fuera del ciclo de pantalla. |
| `security/` | Autorización ligada a tarea, proveedor y contenido aprobado. |

`MainActivity` presenta estados y recoge preferencias; no contiene transporte,
planificación, bucles de espera ni construcción de documentos.

## Contratos y límites

`DelegationRequest`: ID local UUID, clave de idempotencia, objetivo explícito,
proveedor, idioma, formato PDF y límites. Nunca incluye automáticamente pantalla,
contactos, mensajes, notificaciones, audio ni toda la conversación.

`DelegationProvider`: capacidades disponibles y operaciones `submit`, `status`,
`result`, `cancel`. Devuelve errores tipados: configuración ausente, autenticación,
límite, conexión, timeout, respuesta inválida o cancelación no confirmada.

`ResearchResult`: título, secciones de texto, fuentes con título/URL y resumen oral.
Es contenido no confiable; nunca se interpreta como una llamada de herramienta.
El adaptador transforma la respuesta remota al contrato local y aplica límites.

`VerifiedDocument`: ID del artefacto, ruta privada, tamaño, páginas y SHA-256.
El modelo recibe el ID y el estado; no controla rutas ni elige archivos arbitrarios.

Límites iniciales: objetivo 4.000 caracteres, resultado 80.000, 40 fuentes,
documento 10 MiB, una tarea externa activa. Espera total 15 minutos; ninguna
operación de red mantiene al planificador local ocupado durante ese intervalo.
El coste máximo lo impone el backend; si no puede imponerlo, la configuración
debe mostrarlo y exigir un límite operativo antes de habilitar la integración.

## Herramientas y perfiles

| Herramienta | Parámetros | Riesgo/reintento |
| --- | --- | --- |
| `delegate_research` | `topic`, `provider` | SENSITIVE; envío solo tras autorización; sin reenvío ciego. |
| `delegation_status` | `task_id` | ROUTINE; repetible para tareas propias. |
| `cancel_delegation` | `task_id` | ROUTINE; operación local idempotente. |
| `open_document` | `document_id` | ROUTINE; solo artefactos locales validados. |

General y Navegación reciben estas herramientas; los otros perfiles conservan
sus capacidades. El protocolo valida parámetros y perfil antes de cualquier
ejecución. La generación PDF es una fase determinista del trabajo, no una orden
de código remoto ni un parámetro libre del modelo.

## Autorización y credenciales

La integración está desactivada por defecto. La configuración explica proveedor,
datos enviados y posibles costes. La autorización de una tarea queda ligada al
ID, proveedor y hash de la solicitud, con caducidad de cinco minutos antes del
envío. Cambiar el tema o proveedor invalida la autorización pendiente.

Se reutiliza la política de segundo factor existente: decir «sí» no se considera
por sí solo un segundo factor. La confirmación y su estado deben poder explicarse
por voz; su implementación debe usar un mecanismo disponible en Android sin
introducir una dependencia de leer la pantalla.

La configuración de un despliegue de producción usa un backend con token del
usuario; la clave del proveedor permanece en el servidor. No se introduce una
clave compartida en la APK, en recursos, prompts, registros ni commits.
Las tareas ya enviadas pueden consultarse sin volver a conceder autorización
de envío. Una reanudación nunca autoriza una nueva solicitud silenciosamente.

## Ciclo persistente

Estados: `AWAITING_AUTHORIZATION`, `QUEUED`, `SUBMITTING`, `RUNNING`,
`WAITING_NETWORK`, `VERIFYING`, `COMPLETED`, `FAILED`, `CANCELLED`.
Se conserva el estado anterior al perder red y el identificador remoto.

Persistir la solicitud antes del envío y el ID remoto al recibirlo. Si el proceso
muere después de enviar pero antes de guardar la respuesta, consultar la clave
de idempotencia mediante el backend. Si el proveedor no soporta reconciliación,
marcar la situación como incierta y pedir decisión; nunca repetir a ciegas un
trabajo que puede generar coste. El backend del incremento real debe ofrecer
este contrato antes de habilitar envíos automáticos.

Persistencia privada con escritura atómica (`AtomicFile`), versión de esquema,
exclusión mutua y cuarentena de registros corruptos. Retención: últimos 20 trabajos;
los trabajos activos no se eliminan y los documentos se borran mediante una acción
explícita. No guardar razonamiento privado ni copias de las fuentes completas.

Trabajo Android mediante WorkManager con restricción de red y backoff; observar
el estado remoto con esperas espaciadas. No prometer continuidad instantánea en
Doze, tras cierre forzado o bajo restricciones de batería. Reconciliar al volver
a iniciar Lía. Cancelar bloquea primero los resultados localmente y luego solicita
cancelación remota; una respuesta tardía no completa una tarea cancelada.

## PDF y feedback accesible

Renderizar localmente texto y fuentes con `PdfDocument`, paginación explícita y
tipografía legible. Generar a archivo temporal y promoverlo solo tras verificar
cabecera, límite de tamaño y apertura con `PdfRenderer`, incluido al menos un
render de la primera página. Registrar páginas y hash. Un archivo vacío,
ilegible o truncado produce error, nunca «PDF listo».

Abrir mediante FileProvider y permiso temporal de lectura, sin almacenamiento
global. Ofrecer resumen oral y lectura de las secciones originales; un PDF
producido con `PdfDocument` no se declara etiquetado o plenamente accesible.

Estados orales: esperando autorización, investigando, esperando conexión,
preparando documento, listo y error. Dos vibraciones solo después de validación
y guardado. «Detén la investigación» cancela; «¿cómo va?» consulta el trabajo;
«lee el resumen» usa el resultado guardado sin otra solicitud externa.

## Verificación y aceptación

Pruebas unitarias: transiciones, autorización ausente/caducada/cambiada, rechazo
por perfil, entradas fuera de límites, idempotencia, cancelación con resultado
tardío, error tipado, serialización, recuperación de registro y datos mínimos.
Proveedor de prueba determinista con controles de fallo y sin red real.

Pruebas Android: muerte/recreación del proceso, pérdida de red, PDF válido e
inválido, URI privada, diálogo accesible, consulta y cancelación por voz usando
transcripción inyectada. El smoke test no se etiqueta como prueba de micrófono.

Para declarar la integración real terminada: petición auténtica al proveedor,
fuentes recuperadas, PDF abierto, cancelación y recuperación verificadas;
Android CI (unit tests, lint, APK) y Emulator Lab verdes sobre el nuevo commit.
Escucha persistente, batería, reconocimiento e identidad requieren teléfono físico
y se reportan por separado, sin atribuir al emulador resultados de esos ensayos.

## Orden de entrega

1. Contratos, política y máquina de estados con pruebas.
2. Persistencia, recuperación y conexión con autorización/runtime.
3. PDF local, verificación y feedback accesible con proveedor de prueba.
4. Backend y adaptador OpenAI reales; configuración y límites de uso.
5. Validación end-to-end y regresión de herramientas Android existentes.

Documentación oficial consultada:
- https://developers.openai.com/api/docs/guides/tools
- https://developers.openai.com/api/docs/guides/tools-code-interpreter
- https://developers.openai.com/api/docs/guides/tools-connectors-mcp

Decisión recomendada: API para delegación saliente y PDF local; MCP entrante como
otro incremento con autenticación y herramientas limitadas. Automatizar toques
en ChatGPT no forma parte de este primer flujo.
