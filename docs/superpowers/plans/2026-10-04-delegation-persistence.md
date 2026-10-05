# Persistencia de tareas de Lía — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task, directamente sobre `main`, sin PR ni subagentes.

**Goal:** Conservar trabajos entre recreaciones del proceso y permitir que el coordinador existente reconcilie una tarea guardada sin repetir su envío.

**Architecture:** Un codec Kotlin versionado y una política de actualización pura preceden al almacén Android. El almacén mantiene un único snapshot privado mediante AtomicFile, con exclusión mutua entre instancias y procesos. Se prueba recuperación usando el coordinador real y un proveedor de prueba, sin habilitar todavía investigación en la app.

**Tech Stack:** Kotlin 2.2.20/JVM 17, JUnit 4.13.2, Android AtomicFile, FileChannel/FileLock; Android minSdk 26 y compileSdk/targetSdk 36. Sin nuevas dependencias.

**Spec:** `docs/superpowers/specs/2026-10-04-external-delegation-design.md`

## Global Constraints

- Trabajar y publicar directamente sobre `main`, sin PR.
- Objetivo 4.000 caracteres, resultado 80.000, 40 fuentes, una tarea externa activa.
- Retener los últimos 20 trabajos terminales y conservar todos los activos; el contrato admite como máximo un activo.
- Conservar revisión, clave de idempotencia, ID remoto, estado previo de espera, resultado, error, documentId y submissionStarted.
- No guardar grants, credenciales, audio ni razonamiento privado.
- No eliminar documentos al podar el historial de tareas.
- No tratar un registro ilegible como un historial vacío ni permitir un nuevo envío después de corrupción.
- No activar herramientas incompletas ni conectar un proveedor simulado a la app.

## Review Focus

1. Reinicio después del envío pero antes de guardar ID remoto: reconciliar por la misma clave; Task 4.
2. Dos instancias reservan simultáneamente la tarea activa: solo una gana; Task 3.
3. Snapshot truncado o checksum erróneo: conservar evidencia y bloquear nuevas escrituras; Tasks 1 y 3.
4. Versión de esquema futura: conservar archivo y rechazar sin sobrescribir; Tasks 1 y 3.
5. Espacio insuficiente/interrupción durante escritura: no anunciar guardado; recuperar snapshot anterior; Task 3.

## Estructura de archivos

Prefijo de producción: `app/src/main/java/org/lia/accessibility/agent/delegation/storage/`.
Tests JVM: `app/src/test/java/org/lia/accessibility/agent/delegation/storage/`.
Tests Android: `app/src/androidTest/java/org/lia/accessibility/agent/delegation/storage/`.

| Archivo | Responsabilidad |
| --- | --- |
| `DelegationSnapshotCodec.kt` | Serializar y validar snapshot completo sin Android ni dependencias JSON. |
| `DelegationStoragePolicy.kt` | Admisión, CAS y retención antes de escribir. |
| `DelegationStorageException.kt` | Errores tipados de esquema, corrupción y lectura/escritura. |
| `RecoverableDelegationTaskStore.kt` | Extender el contrato existente con lectura del snapshot. |
| `DelegationFileLock.kt` | Bloqueo por ruta canónica y bloqueo de archivo lateral. |
| `AtomicDelegationTaskStore.kt` | Lectura/escritura atómica, comprobación de guardado y cuarentena. |

## Task 1: Codec limitado y versionado

**Files:** Crear `DelegationSnapshotCodec.kt`, `DelegationStorageException.kt`; test `DelegationSnapshotCodecTest.kt`.

**Interfaces:** `DelegationSnapshotCodec.encode(tasks: List<DelegationTask>): ByteArray`, `decode(bytes: ByteArray): List<DelegationTask>`.
`DelegationStorageException` extiende IOException con causa opcional y razón tipada: CORRUPT, UNSUPPORTED_SCHEMA, IO_FAILURE.

- [ ] Escribir tests `roundTripEveryState`, `preservesSpanishAndEmoji`, `preservesRemoteRecoveryMetadata`, `rejectsTruncatedInput`, `rejectsBadChecksum`, `rejectsFutureSchema`, `rejectsOversizeSnapshot`, `rejectsDuplicateIds`, `rejectsMultipleActiveJobs`, `rejectsTrailingBytes`, `rejectsMalformedUtf8`.
  Comprobar campos de request y resultado por valores; ResearchResult no implementa igualdad estructural, así que no comparar objetos por referencia.
- [ ] Ejecutar `gradle :app:testDebugUnitTest --tests '*DelegationSnapshotCodecTest' --stacktrace`; Expected: fallo por codec ausente.
- [ ] Implementar formato binario: magic ASCII `LIATASK1`, versión Int 1, tamaño de payload Int, payload y SHA-256 de payload. UTF-8 con prefijos Int y decoder estricto. Estados/errores se guardan por nombre, no ordinal. Límite de archivo 16 MiB; máximo 21 tareas, 40 fuentes por informe, 40.000 secciones por informe y 320.000 bytes por string; aplicar además los límites de caracteres originales al reconstruir contratos. Validar longitudes antes de reservar memoria y rechazar bytes sobrantes.
- [ ] Repetir tests; Expected: PASS. Confirmar que ni grant ni credenciales forman parte del formato.
- [ ] Commit `feat(delegation): add bounded versioned task snapshots` sobre main.

## Task 2: Política y lectura recuperable

**Files:** Crear `DelegationStoragePolicy.kt`, `RecoverableDelegationTaskStore.kt`; test `DelegationStoragePolicyTest.kt`.

**Interfaces:** `RecoverableDelegationTaskStore : DelegationTaskStore` añade `snapshot(): List<DelegationTask>`.
`DelegationStoragePolicy.create(current, task): List<DelegationTask>?`, `update(current, taskId, expectedRevision, updated): List<DelegationTask>?`, `retain(tasks): List<DelegationTask>`.

- [ ] Tests `onlyOneActiveJob`, `duplicateIdentityRejected`, `staleCasRejected`, `requestCannotChange`, `revisionMustAdvanceByOne`, `terminalCannotChange`, `timestampCannotRegress`, `remoteIdCannotChange`, `submissionStartedCannotBeCleared`, `initialTaskMustBeUnsent`, `keepsTwentyMostRecentTerminalAndActive`, `equalTimestampsHaveStableRetention`.
- [ ] Ejecutar `gradle :app:testDebugUnitTest --tests '*DelegationStoragePolicyTest' --stacktrace`; Expected: RED.
- [ ] Implementar creación únicamente en AWAITING_AUTHORIZATION, revisión 0, sin resultado/ID remoto/documento ni envío iniciado. CAS exige misma request, creación y revisión previa; incremento exacto sin overflow, tiempo no regresivo y estado previo no terminal. Identidades únicas entre registros retenidos; los nuevos objetivos reciben nuevos UUID y el proveedor conserva la deduplicación remota. Retener terminales por updatedAt descendente, UUID como desempate estable, y sumar el activo. Rechazar cualquier resultado que exceda el contrato del codec; no usar retención para ocultar datos corruptos.
- [ ] Repetir tests; Expected: PASS. No tocar ConversationStore ni cambiar las 53 pruebas anteriores.
- [ ] Commit `feat(delegation): define durable task admission and retention`.

## Task 3: Almacén Android atómico

**Files:** Crear `DelegationFileLock.kt`, `AtomicDelegationTaskStore.kt`; Android tests `AtomicDelegationTaskStoreTest.kt`.

**Interfaces:** `AtomicDelegationTaskStore(context: Context) : RecoverableDelegationTaskStore` usa `context.noBackupFilesDir/delegation/tasks.bin`.
Constructor secundario interno `(directory: File)` para pruebas instrumentadas en directorios temporales privados.
`DelegationFileLock.withLock(directory: File, block: () -> T): T` usa ruta canónica fija, lock JVM compartido entre instancias y FileChannel.lock sobre `tasks.lock`; cierra/release en finally.

- [ ] Tests instrumentados `survivesStoreRecreation`, `twoInstancesHaveAtomicCas`, `concurrentCreationHasOneWinner`, `interruptedWriteRestoresPreviousSnapshot`, `corruptionQuarantinedAndBlocked`, `futureSchemaPreserved`, `ioFailureNeverClaimsSave`, `retentionNeverDeletesDocumentFiles`, `snapshotIsDefensive`, `allFilesStayPrivate`.
  Usar CountDownLatch y executor con timeout, sin sleeps. Para interrupción, escribir parcialmente con el AtomicFile real, llamar failWrite y volver a abrir. Para I/O, usar directorio/archivo incompatible como fixture real, sin permisos root simulados.
- [ ] Ejecutar `gradle :app:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=org.lia.accessibility.agent.delegation.storage.AtomicDelegationTaskStoreTest`; Expected: fallo por clase ausente. Si el entorno local carece de SDK/emulador, usar el Emulator Lab existente y registrar el resultado antes de continuar.
- [ ] Implementar todas las operaciones bajo ambos locks; releer el archivo en cada transacción. Archivo ausente significa snapshot vacío únicamente si no existe backup ni marcador de corrupción. Leer con límite antes de decode. startWrite/finishWrite y failWrite ante fallo; sincronizar descriptor y comprobar por lectura los bytes confirmados antes de devolver éxito. Error de persistencia lanza IO_FAILURE, no devuelve falso como si fuera un conflicto CAS.
- [ ] Corrupción: copiar bytes a `quarantine/<UUID>.bin`, preservar original/backup y guardar marcador durable `blocked`; siguientes operaciones lanzan CORRUPT. Nunca borrar ni reiniciar historial automáticamente. Si falla la cuarentena, conservar original y seguir bloqueando por su lectura inválida. Versión futura: UNSUPPORTED_SCHEMA sin mover, modificar ni crear un nuevo snapshot. No incluir contenido de objetivos en nombres o mensajes de error.
- [ ] Repetir pruebas reales Android; Expected: PASS. Inspeccionar lint y límites de API para minSdk 26.
- [ ] Commit `feat(delegation): persist tasks with atomic locked storage`.

## Task 4: Recuperación del coordinador y validación

**Files:** Crear Android test `DelegationRecoveryTest.kt`; actualizar `docs/AGENT_SYSTEM.md` para reflejar la capacidad real del almacén.

**Interfaces:** Consumir el coordinador/proveedor existentes; FakeDelegationProvider de esta prueba se define en androidTest y jamás en producción.

- [ ] Tests `recreatedCoordinatorReconcilesWithoutResubmit`, `runningJobResumesWithSameRemoteId`, `cancelledUncertainSendStillCleansUp`, `expiredGrantIsNotPersistedOrReused`, `verificationResultSurvivesRecreation`, `corruptStorePreventsProviderSubmission`.
  Primero guardar SUBMITTING/UNCERTAIN_SUBMISSION en el almacén real; luego crear otro almacén/coordinador y devolver Found para la misma clave. Assert: pasa a RUNNING con el ID recuperado y submit nunca se invoca. Para grant expirado, reconstruir coordinador y exigir autorización nueva antes de cualquier reenvío.
- [ ] Ejecutar tests instrumentados y confirmar RED antes de cualquier corrección de integración; si pasan de entrada, registrar que cubren comportamiento existente sobre el almacén recién implementado, sin introducir una corrección innecesaria.
- [ ] Corregir únicamente defectos demostrados. Documentar que esta etapa ofrece persistencia y recuperación a los módulos, pero no una orden de investigación completa ni ejecución automática al arrancar la app.
- [ ] Ejecutar suite completa, Android Lint, APK y pruebas instrumentadas. Publicar directamente main, comprobar Android CI y Emulator Lab sobre el nuevo SHA y corregir fallos antes de declarar el bloque validado.
- [ ] Revisar diff en una pasada separada, sin subagentes; registrar decisiones/desviaciones y limitaciones.

## Alcance posterior

WorkManager, integración con la autorización/voz, PDF y proveedor real siguen pendientes. Se conectarán al almacén recuperable cuando exista un proveedor configurado; este bloque no inicia trabajos de red ni mantiene el modelo cargado. Tampoco incorpora una pantalla para reiniciar registros corruptos: la corrupción se informa mediante error tipado para el futuro flujo accesible de recuperación.

Referencia oficial consultada: https://developer.android.com/reference/android/util/AtomicFile

## Ejecución

Mantener el método ya elegido: ejecución directa, sin subagentes, sobre main. Revisión de este plan pendiente antes de modificar código de producción.
