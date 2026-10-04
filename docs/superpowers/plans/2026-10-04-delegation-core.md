# Núcleo de delegación de Lía — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Crear el núcleo comprobable de trabajos de investigación, con autorización ligada a la solicitud, cancelación e idempotencia, sin red ni acceso directo al teléfono.

**Architecture:** Contratos Kotlin independientes de Android y una máquina de estados determinista. Un coordinador consume un almacén y un proveedor inyectados; el proveedor simulado existe únicamente en tests. Este primer bloque no registra herramientas utilizables hasta que estén disponibles persistencia, autorización Android y ejecutor real.

**Tech Stack:** Kotlin/JVM 17, JUnit 4.13.2, Gradle 8.13; Android existente minSdk 26, compileSdk/targetSdk 36. Sin dependencias nuevas en este bloque.

**Spec:** `docs/superpowers/specs/2026-10-04-external-delegation-design.md`

## Global Constraints

- Objetivo 4.000 caracteres, resultado 80.000, 40 fuentes, documento 10 MiB, una tarea externa activa.
- Espera total 15 minutos; autorización con caducidad de cinco minutos antes del envío.
- Mantener los cinco perfiles y el cerebro local; no añadir lógica agentic a MainActivity.
- No enviar automáticamente pantalla, contactos, mensajes, notificaciones, audio ni toda la conversación.
- No guardar razonamiento privado ni credenciales; no aceptar llamadas de herramienta desde resultados externos.
- No reintentar un envío incierto; consultar idempotencia antes de reenviar.
- Un resultado tardío no completa una tarea cancelada.
- El núcleo no permite anunciar «PDF listo»: eso requiere documento validado por el bloque posterior.

## Review Focus

1. Solicitud con contenido cambiado tras autorizar: rechazo antes de enviar; prueba de Task 2.
2. Timeout después de que el proveedor aceptó: no duplicar coste; prueba de Task 4.
3. Cancelación concurrente con llegada del resultado: cancelar gana; prueba de Task 4.
4. Texto remoto que contiene órdenes o rutas: tratar como texto; prueba de Task 1.
5. Reloj retrocede o autorización futura: rechazar autorización inválida; prueba de Task 2.

## Archivos

Todos los nuevos archivos de producción están bajo `app/src/main/java/org/lia/accessibility/agent/delegation/`.
Tests bajo `app/src/test/java/org/lia/accessibility/agent/delegation/`.

| Archivo | Responsabilidad |
| --- | --- |
| `DelegationContracts.kt` | Solicitud, resultado, fuente, límites y errores tipados. |
| `DelegationProvider.kt` | Interfaz del proveedor y reconciliación de envío. |
| `DelegationAuthorization.kt` | Concesión ligada a solicitud y caducidad. |
| `DelegationTask.kt` | Registro inmutable, estados y eventos. |
| `DelegationStateMachine.kt` | Transiciones válidas y bloqueo de estados terminales. |
| `DelegationTaskStore.kt` | Contrato transaccional del almacén. |
| `DelegationCoordinator.kt` | Enviar, consultar, cancelar y recuperar sin duplicar. |

## Task 1: Contratos y validación

**Files:** Crear `DelegationContracts.kt`, `DelegationProvider.kt`; tests `DelegationContractsTest.kt`.

**Interfaces:**
- `DelegationRequest(taskId: String, idempotencyKey: String, topic: String, providerId: String, language: String = "es")`; IDs locales UUID, proveedor no vacío.
- `ResearchSection(heading: String, text: String)`, `ResearchSource(title: String, url: String)`.
- `ResearchResult(title: String, sections: List<ResearchSection>, sources: List<ResearchSource>, spokenSummary: String)`.
- `DelegationError`: NOT_CONFIGURED, AUTHENTICATION, RATE_LIMIT, NETWORK, TIMEOUT, INVALID_RESPONSE, UNCERTAIN_SUBMISSION, CANCEL_NOT_CONFIRMED.
- `ProviderReply<T>`: `Success(value: T)` o `Failure(error: DelegationError)`.
- `DelegationProvider`: `submit(request): ProviderReply<String>`, `status(remoteId): ProviderReply<RemoteTaskState>`, `result(remoteId): ProviderReply<ResearchResult>`, `cancel(remoteId): ProviderReply<Unit>`, `reconcile(idempotencyKey): ProviderReply<SubmissionResolution>`; funciones suspend.
- `SubmissionResolution`: Found(remoteId), ConfirmedAbsent, Unknown. `RemoteTaskState`: RUNNING, COMPLETED, FAILED, CANCELLED.

- [ ] Escribir tests `acceptsExactLimits`, `rejectsBlankTopic`, `rejectsInvalidId`, `rejectsOversizeTopic`, `rejectsOversizeResult`, `rejectsTooManySources`, `rejectsNonHttpsSource`, `preservesInstructionLikeTextAsData`.
  Assert: 4.000 aceptado/4.001 rechazado; 80.000 aceptado/80.001 rechazado contando título, headings, secciones, URLs y resumen; 40 fuentes aceptadas/41 rechazadas. Sin truncado silencioso.
- [ ] Ejecutar `gradle :app:testDebugUnitTest --tests '*DelegationContractsTest' --stacktrace`; confirmar fallo por símbolos ausentes.
- [ ] Implementar contratos y límites; validar URLs HTTPS con `java.net.URI`, host no vacío y sin userinfo. No abrir URLs ni interpretar texto.
- [ ] Repetir el comando; exigir tests verdes.
- [ ] Commit `feat(delegation): add validated research contracts`.

## Task 2: Autorización ligada a solicitud

**Files:** Crear `DelegationAuthorization.kt`; test `DelegationAuthorizationTest.kt`.

**Interfaces:**
- `DelegationGrant(taskId: String, providerId: String, requestHash: String, issuedAtMs: Long, expiresAtMs: Long)`.
- `DelegationAuthorization.requestHash(request: DelegationRequest): String` usa SHA-256 y campos UTF-8 con prefijo de longitud, no concatenación ambigua.
- `grant(request, nowMs, decision: AuthorizationDecision): DelegationGrant?`; solo `Allowed` produce grant y expiresAt = now + 300.000.
- `allows(request, grant, nowMs): Boolean`; exige identidad, hash, `issuedAt <= now < expiresAt` y duración exacta.
- Consumo de `CommandAuthorizationGate` existente; este objeto no captura ni verifica el segundo factor. No recibe un booleano público que permita inventarlo.

- [ ] Tests `deniedDecisionNeverGrants`, `changedTopicRejected`, `changedProviderRejected`, `changedLanguageRejected`, `grantExpiresAtFiveMinutes`, `futureGrantRejected`, `wrongTaskRejected`, `hashHasUnambiguousFields`.
- [ ] Ejecutar `gradle :app:testDebugUnitTest --tests '*DelegationAuthorizationTest' --stacktrace`; confirmar fallo inicial.
- [ ] Implementar signatures anteriores, validación del tiempo y protección frente a overflow de Long.
- [ ] Ejecutar tests; exigir PASS y conservar pruebas de autorización existentes verdes.
- [ ] Commit `feat(delegation): bind grants to exact requests`.

## Task 3: Máquina de estados y almacén transaccional

**Files:** Crear `DelegationTask.kt`, `DelegationStateMachine.kt`, `DelegationTaskStore.kt`; tests `DelegationStateMachineTest.kt` y `InMemoryDelegationTaskStore.kt` (solo tests).

**Interfaces:**
- `DelegationTask(request, state, revision: Long, createdAtMs: Long, updatedAtMs: Long, remoteId: String?, waitingFrom: DelegationState?, result: ResearchResult?, error: DelegationError?)`.
- Estados exactos de spec: AWAITING_AUTHORIZATION, QUEUED, SUBMITTING, RUNNING, WAITING_NETWORK, VERIFYING, COMPLETED, FAILED, CANCELLED.
- Eventos tipados: Authorized, BeginSubmit, Submitted(remoteId), NetworkLost, NetworkRestored, ResultReceived(result), DocumentVerified(documentId), Fail(error), Cancel.
- `DelegationStateMachine.apply(task, event, nowMs): TransitionResult` devuelve Applied(task) o Rejected(reason). DocumentVerified exige ID válido y resultado presente; solo esa transición permite COMPLETED.
- `DelegationTaskStore.createIfIdle(task): Boolean`, `get(taskId): DelegationTask?`, `compareAndSet(taskId, expectedRevision, updated): Boolean`; reserva atómica de una tarea activa y actualización por revisión, nunca leer-guardar sin CAS.

- [ ] Tests tabla de transiciones: flujo feliz, estados terminales inmutables, network restore al estado previo, envío sin grant imposible, VERIFIED sin resultado rechazado, cancel desde todos los activos, una tarea activa y CAS obsoleto rechazado.
- [ ] Ejecutar `gradle :app:testDebugUnitTest --tests '*DelegationStateMachineTest' --stacktrace`; comprobar fallo inicial.
- [ ] Implementar tabla explícita. Estados terminales: COMPLETED, FAILED, CANCELLED. Cada transición aplicada incrementa revision; timestamps no retroceden.
- [ ] Ejecutar tests; exigir PASS.
- [ ] Commit `feat(delegation): add transactional task state machine`.

## Task 4: Coordinador e idempotencia

**Files:** Crear `DelegationCoordinator.kt`; tests `DelegationCoordinatorTest.kt`, `FakeDelegationProvider.kt`.

**Interfaces:**
- Consume Task 1–3. Constructor `(store: DelegationTaskStore, provider: DelegationProvider, clock: () -> Long)`.
- `create(request): CoordinatorResult`; crea AWAITING_AUTHORIZATION o rechazo por tarea activa.
- `suspend submit(taskId, grant): CoordinatorResult`; valida grant y persiste SUBMITTING antes de red; guarda remoteId vía CAS al aceptar.
- `suspend refresh(taskId): CoordinatorResult`; consulta RUNNING, reconcilia SUBMITTING; resultado válido pasa a VERIFYING, sin afirmar documento listo.
- `suspend cancel(taskId): CoordinatorResult`; persistir CANCELLED primero, después cancel remoto si existe.
- `CoordinatorResult`: Updated(task), Rejected(reason). Errores remotos se conservan en task, sin secrets ni payload sin límite.

- [ ] Tests `noGrantNoNetwork`, `onlyOneSubmission`, `persistBeforeNetwork`, `timeoutDoesNotResubmit`, `reconcileFoundRecovers`, `unknownDoesNotResubmit`, `confirmedAbsentRequiresValidGrantBeforeResubmit`, `lateResultCannotUndoCancellation`, `cancelFailureStaysCancelled`, `networkRestoreKeepsRemoteId`, `fifteenMinuteDeadlineFails`, `remoteSuccessWaitsForDocumentVerification`.
- [ ] Ejecutar `gradle :app:testDebugUnitTest --tests '*DelegationCoordinatorTest' --stacktrace`; confirmar RED.
- [ ] Implementar coordinación con CAS. Resolver carreras: releer después de llamada remota y descartar si cancelado o revisión incompatible; si un submit tardío devuelve ID después de cancelar, solicitar cancel remoto sin revivir el registro.
- [ ] Ejecutar tests; exigir PASS. Fake con contadores y barreras controladas, no sleeps. Prueba de suspend mediante continuation síncrona y barreras del fake.
- [ ] Commit `feat(delegation): coordinate cancellable research jobs`.

## Task 5: Verificación y documentación del bloque

**Files:** Modificar `docs/AGENT_SYSTEM.md`; mantener README preciso sobre capacidades implementadas.

- [ ] Documentar que el núcleo existe pero todavía no realiza investigación ni entrega PDF, y que Fake vive solo en tests.
- [ ] Ejecutar `gradle :app:testDebugUnitTest :app:lintDebug :app:assembleDebug :app:assembleDebugAndroidTest --stacktrace`; exigir salida 0. Ejecutar `git diff --check`.
- [ ] Revisar catálogo/router/perfiles: no anunciar herramientas incompletas; revisar diff completo buscando secretos, acoplamiento Android en núcleo y cambios no relacionados.
- [ ] Commit, publicar sobre la rama indicada sin merge; comprobar Android CI y Emulator Lab sobre el SHA publicado, no sobre el SHA base.
- [ ] Reportar cambios, número real de tests y checks, y limitaciones pendientes. Si algún check falla, corregirlo antes de declarar el bloque validado.

## Bloques posteriores y cobertura restante

Estos bloques requieren sus propios planes antes de implementar. No quedan implícitamente completados por este núcleo.

| Bloque | Archivos / entregable | Verificación obligatoria |
| --- | --- | --- |
| Persistencia Android | `agent/delegation/storage/AtomicDelegationTaskStore.kt`, WorkManager, esquema versionado, cuarentena, 20 trabajos terminales y conservación de activos. | Recreación, corrupción, concurrencia, pérdida de red, Doze y reconciliación. |
| PDF local | `documents/ResearchPdfWriter.kt`, `ResearchPdfVerifier.kt`, `VerifiedDocumentStore.kt`, FileProvider y XML de rutas limitadas. | PdfRenderer abre/renderiza; 10 MiB, páginas/hash, archivo truncado, URI privada y borrado explícito. |
| Herramientas y voz | Catálogo/router/perfiles, `DelegationToolExecutor`, runtime sin observación obligatoria, continuidad tras autorización, consulta/cancelación/resumen y UI accesible. | Protocolo/perfil, segundo factor Android real, transcripción inyectada, regresión de herramientas locales; no decir PDF listo antes de validar. |
| Backend y OpenAI | `integrations/openai/`; backend autenticado con deduplicación, reconciliación y límite de coste. | Contrato HTTPS, token seguro, fuentes reales, errores/red, cancelación y ensayo end-to-end con credenciales. |
| Dispositivo físico | Escucha persistente, identidad y batería. | Mediciones y órdenes reales; no extrapolar desde emulador. |

El proyecto no incluye hoy un backend, URL, token ni credenciales OpenAI. Este plan no escoge un hosting ni inventa disponibilidad: el bloque real permanecerá deshabilitado hasta disponer de esos requisitos y aprobar su diseño. MCP entrante, AppFunctions, BrainManager y keyword spotting mantienen el alcance separado indicado en la spec.

## Ejecución recomendada

Ejecución directa por el agente en esta sesión, tarea a tarea, sin subagentes; el núcleo comparte interfaces estrechas y puede validarse con pruebas deterministas. Revisión del plan pendiente antes de iniciar cambios de producción.
