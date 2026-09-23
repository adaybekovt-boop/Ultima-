# Ultima — инструкция по улучшению и ускорению проекта

Документ основан на ревью исходников на коммите `e6763a8` (ветка `main`, 23.09.2026).
Все пункты привязаны к конкретным файлам. Номера строк указаны на момент ревью, после правок они сдвинутся.

Правила выполнения — `AGENTS.md` и `OPTIMIZATION_GUARDRAILS.md`. Любая оптимизация ниже
допустима только если она не меняет наблюдаемое поведение игры и откатывается в vanilla при сбое.

---

## 0. Текущее состояние

| Параметр | Значение |
|---|---|
| Код | `src/main` ≈ 16.3k строк, `src/client` ≈ 6.8k, `src/test` ≈ 8.7k |
| Модули | 24 (`UltimaModules.ALL`) |
| Mixin'ы | 56 общих (`ultima.mixins.json`) + 26 клиентских (`ultima.client.mixins.json`) |
| `bash scripts/check.sh` | **проходит** (≈57 с при прогретом кэше Gradle, JDK 25.0.4, Gradle 9.5.1) |
| Свежий клон | **не собирается** без ручной установки Gradle 9.5.1 и JDK 25 (wrapper в `.gitignore`) |

Главный вывод: архитектура fail-open и изоляция модулей через `UltimaMixinPlugin` сделаны хорошо.
Самые дешёвые и заметные выигрыши сейчас не в новых оптимизациях, а в устранении
**накладных расходов самой обвязки**. Проверки конфига, fail-open-обёртки и метрики,
включённые по умолчанию, стоят на горячих путях дороже части оптимизаций, которые они охраняют.

---

## 1. Top-10: что сделать в первую очередь

| # | Что | Где | Эффект |
|---|---|---|---|
| 1 | Кэшировать результат `UltimaConfig.isEnabled` в `boolean[]` при старте | `config/UltimaConfig.java:95-107` | Убирает `new HashSet` + `ArrayList` на каждом вызове. Сейчас это происходит на каждой проверке тега/состояния и в каждом кадре FSR |
| 2 | Облегчить успешный путь `FailOpenGuard` | `failopen/FailOpenGuard.java:102-196` | Убирает ConcurrentHashMap `contains`/`remove`, 2 volatile-чтения и лямбду на каждом вызове |
| 3 | Выключить `temporal` по умолчанию | `config/UltimaModules.java:189` | Модуль ничего не меняет в пикселях (бэкендов нет), но каждый кадр копирует матрицы, читает `System.getProperty` и опрашивает GPU-устройство |
| 4 | Удешевить `server_metrics` (включён по умолчанию) | `mixin/server_metrics/*` | Сейчас стоит таймер на каждый чанк в `tickChunk`, на каждый пакет (×3 на Netty) и двойной таймер AI у мобов |
| 5 | Добавить `cursor_step` в список несовместимости с Lithium | `config/UltimaModules.java:94-96` | Отменяемый `HEAD` на `Cursor3D.advance` при установленном Lithium — это риск конфликта |
| 6 | Закоммитить Gradle wrapper, задать Java toolchain | `.gitignore:14-17`, `build.gradle` | Свежий клон и CI начинают собираться «из коробки» |
| 7 | Объединить 8 JavaExec-задач в одну, убрать вложенные `Wave2FailOpenTest.run()` | `build.gradle:99-175`, `src/test/**` | `Wave2FailOpenTest` сейчас запускается из 6 точек входа, и каждая задача поднимает отдельную JVM с Minecraft |
| 8 | Включить кэш Gradle и daemon в CI, убрать matrix `repeat` | `.github/workflows/ultima-ci-validation.yml:26,93,113-138` | CI-прогон сокращается в разы |
| 9 | Удалить `Ultima-24-modules-2026-09-21.zip` из репозитория | корень | 513 КБ устаревшей копии проекта в git |
| 10 | Перенести `fsr`, `temporal`, `retained`, `meshing` из `src/main` в `src/client` | `src/main/java/dev/ultima/*` | Соблюдение правила «клиентский код только в client source set» |

---

## 2. Этап A — сборка, тесты, CI (ускорение разработки)

### A1. Воспроизводимая сборка

1. Убрать из `.gitignore` строки `gradlew`, `gradlew.bat`, `gradle/wrapper/` и закоммитить wrapper для Gradle 9.5.1.
   `scripts/ensure-wrapper.sh` оставить как fallback.
2. В `build.gradle` добавить toolchain, чтобы JDK 21 падал сразу с понятной ошибкой:
   ```gradle
   java {
       toolchain { languageVersion = JavaLanguageVersion.of(25) }
       withSourcesJar()
   }
   tasks.withType(JavaCompile).configureEach {
       options.release = 25
       options.encoding = "UTF-8"
   }
   ```
   После этого `sourceCompatibility`/`targetCompatibility` (строки 177-180) можно удалить.
3. `loom_version=1.17-SNAPSHOT` — плавающий снапшот. Закрепить релиз Loom для 26.2,
   как только он выйдет (по `AGENTS.md` менять версию без задачи нельзя, поэтому это отдельное решение).
4. `fabric.mod.json`: `"fabric-api": "*"` заменить на `">=0.156.0+26.2"`.

### A2. `gradle.properties`

```properties
org.gradle.jvmargs=-Xmx4G -Dfile.encoding=UTF-8
org.gradle.parallel=true
org.gradle.caching=true
org.gradle.configuration-cache=true
```

Configuration cache сейчас нельзя включить из-за блока `afterEvaluate` в `build.gradle:70-97`.
Этот блок читает `run/bench-client.*` на этапе конфигурации. Его нужно переписать
через `CommandLineArgumentProvider` с объявленным `inputs.file(...)`, после чего кэш включается.

### A3. Одна регрессионная задача вместо восьми

Сейчас `check` зависит от 8 JavaExec-задач (`build.gradle:99-175`), у которых нет `inputs`/`outputs`.
Поэтому они никогда не бывают UP-TO-DATE. Вдобавок:
- `MergedRegressionTest` уже вызывает почти все остальные наборы;
- `Wave2FailOpenTest.run()` дополнительно вызывается из `RecipeMatchCacheTest:29`,
  `TagBitsetEquivalenceTest:32`, `StatePropertyCacheEquivalenceTest:30`,
  `SlotMaskEntityQueryTest:36` и из собственной задачи;
- `CommandCompactionRecoveryTest` в `MergedRegressionTest` не входит.

Что сделать:
1. Удалить вызовы `Wave2FailOpenTest.run()` из чужих наборов.
2. Добавить `CommandCompactionRecoveryTest` в `MergedRegressionTest`.
3. Оставить одну задачу `regressionTest` с `inputs.files(sourceSets.test.runtimeClasspath)`
   и маркер-файлом в `outputs`, чтобы Gradle её кэшировал.
4. Долгосрочно: перевести наборы на JUnit 5 (`useJUnitPlatform()`). Это даст одну JVM,
   XML-отчёты для CI и кэширование «из коробки». Сейчас `test { enabled = false }`,
   и `./gradlew test` «зелёный», хотя ничего не запускает.

### A4. CI (`.github/workflows/ultima-ci-validation.yml`)

- Убрать `cache-disabled: true` (строка 93) и `--no-daemon` (строки 113-138).
  Без кэша каждая из 4 задач заново скачивает и ремапит Minecraft.
- Убрать `repeat: [1, 2]` (строка 26). Повторный прогон того же коммита ничего не проверяет.
- Добавить `concurrency: { group: ultima-ci-${{ github.ref }}, cancel-in-progress: true }` и `timeout-minutes: 45`.
- Удалить шаг проверки «ancestry» с захардкоженными SHA и ветками `cursor/*` (строки 34-81).
  Это была одноразовая проверка слияния, а теперь она ломает CI при удалении любой из этих веток или в PR из форка.
- Удалить триггеры на старые ветки `fix/...` и `integration/...` (строки 3-9).
- Mixin-smoke (`scripts/mixin-smoke.sh`) запускать один раз на Linux и обернуть в `timeout 15m`.
  В артефакты добавить `run/logs/latest.log`.
- Сейчас `build` вызывается дважды за задачу: напрямую и внутри `scripts/check.sh`. Достаточно `check.sh`.

### A5. Гигиена репозитория

- `git rm Ultima-24-modules-2026-09-21.zip`. В `.gitignore` добавить `*.zip`, `*.jfr`, `*.hprof`, `hs_err_pid*`, `crash-reports/`.
- Добавить `.gitattributes` с `*.sh text eol=lf`: скрипты запускаются и на Windows-раннере.
- `CHANGELOG.md:145-155` всё ещё называет `ultima-foundation-final-2.6.1` «main HEAD»
  и пишет, что PR #3 и #4 не слиты. Это противоречит README и текущему коду, секцию нужно обновить.
- `publishing` (`build.gradle:183-189`) публикует `dev.ultima:Ultima` без репозитория.
  Задать `artifactId = archives_base_name` или удалить плагин.

---

## 3. Этап B — накладные расходы по умолчанию (ускорение игры у всех пользователей)

Этот этап важнее всего для игроков: он касается модулей, включённых по умолчанию.

### B1. `UltimaConfig.isEnabled` — кэш на старте

`UltimaConfig.java:95-96` на каждый вызов создаёт `new HashSet<>()`.
`loadedIncompatibleMods` (`:501-511`) создаёт ещё и `ArrayList`. `UltimaModules.byKey` — линейный поиск по 24 модулям.

Горячие вызовы: `TagBitsetRuntime.moduleEnabled()` (на каждую проверку `Holder.is(TagKey)`),
`StatePropertyRuntime.mayCacheBlock/Fluid`, `FsrUpscaling.beginWorldPass` (каждый кадр).

**Решение.** Все модули применяются с политикой «нужен перезапуск», поэтому результат можно
вычислить один раз после загрузки конфига:
```java
private final Map<String, Boolean> resolved; // заполняется один раз в load()
public boolean isEnabled(final String module) { return Boolean.TRUE.equals(resolved.get(module)); }
```
Ещё лучше — дать `UltimaModules.Module` индекс и хранить `boolean[]`.
Горячие пути должны читать `static final`/`static volatile boolean`-флаг модуля, а не вызывать конфиг.
Отдельно: `VanillaClassGuard.isVanillaClass` вызывает `getName().startsWith` на каждый вызов.
Его нужно кэшировать через `ClassValue<Boolean>`.

### B2. `FailOpenGuard` — дешёвый успешный путь

Сейчас на каждом успешном вызове (`FailOpenGuard.java`) выполняется:
- `tripped.contains(caseId)` — ConcurrentHashMap;
- `maybeThrowForTest` — 2 volatile-чтения;
- `consecutive.remove(caseId)`, даже если ключа нет;
- лямбда `Supplier` на месте вызова.

**Решение:**
1. Поле `volatile boolean anyTripped` в `Module`. Проверять `tripped.contains` только если флаг поднят.
2. Поле `volatile boolean anyConsecutive`. `remove` на успехе выполнять только если был хотя бы один сбой.
3. `maybeThrowForTest` оставить за одним `static final boolean TEST_HOOKS = Boolean.getBoolean("ultima.testFaults")`.
   JIT полностью уберёт эту ветку в продакшене.
4. На самых горячих местах (`Holder.is`, `FluidState`, слоты хопперов) заменить лямбды на try/catch
   прямо в Mixin-методе. Семантика fail-open сохраняется: при `Throwable` выполняется `failOpen(...)` и vanilla-путь.

`Wave2FailOpenTest` проверяет наличие fail-open через поиск строк в исходниках
(`FailOpenGuard.supply`, `catch (Throwable`). Его нужно обновить одновременно с этим изменением.

### B3. `temporal` — выключить по умолчанию или сделать no-op

`UltimaModules.java:189` помечает модуль как включённый по умолчанию, хотя в описании сказано,
что он «не меняет пиксели» и что «DLSS/FSR backends are not implemented».
При этом каждый кадр (`temporal/GameRendererMixin` → `TemporalPipeline`) выполняются:
- `System.getProperty("ultima.temporal.mode")` (`TemporalPipeline.java:184`) — синхронизированный вызов;
- `RenderSystem.tryGetDevice().getDeviceInfo().backendName()` (`:211`);
- 2–4 копирования `Matrix4f` и 2 новых `RenderSize` (`TemporalResolution.java:14-16`).

**Решение:** `enabledByDefault = false` до появления реального бэкенда.
Если модуль должен остаться включённым, `beginWorldFrame` должен сразу выходить, когда активен
`NativePassthroughBackend`, а системное свойство и имя бэкенда нужно читать один раз в `initialize()`.

### B4. `terrain_metrics` — считать только при активном бенчмарке

`terrain_metrics/LevelRendererMixin.java:28-57` каждый кадр обходит все draw-списки
`drawGroupsPerLayer()` через итераторы `EnumMap` и `Int2ObjectOpenHashMap`.
Результат читает только `ClientFrameBenchmark`, который выключен по умолчанию.
Кроме того, `draws` и `sectionLayers` увеличиваются на одно и то же значение, то есть одно поле дублирует другое.

**Решение:** обход и `nanoTime`-фазы выполнять только при активном бенчмарке
(`-Dultima.clientBenchmark`, флаг `ClientFrameBenchmark.ENABLED`; нужен публичный геттер),
либо выключить модуль по умолчанию. Одно из полей `draws`/`sectionLayers` удалить.

### B5. `server_metrics` — меньше точек замера

Модуль включён по умолчанию. Каждый `begin`/`end` в `ServerMetrics.java:203-214` выполняет
volatile-чтение, `ThreadLocal.get`, volatile-чтение `clock`, `nanoTime` и `AtomicLong.addAndGet`.
Точки замера стоят на очень частых местах:

| Место | Проблема | Решение |
|---|---|---|
| `ServerLevelMixin` → `tickChunk` | 2 `nanoTime` на **каждый** чанк | Один таймер вокруг всего цикла тика чанков |
| `MobMixin` + `BrainMixin` | Оба пишут `TICK_AI`, у моба 4 вызова begin/end | Оставить один из двух |
| `PacketEncoderMixin`, `CompressionEncoderMixin`, `CipherEncoderMixin` | 6 вызовов таймера на каждый пакет в Netty-потоках, общий `AtomicLong` для всех потоков | `LongAdder` или счётчики на поток; выборка 1 из N пакетов |
| `ChunkMapTrackedEntityMixin` | атомарное сложение на каждое обновление трекера | Суммировать локально и сбрасывать раз в тик |

Корректность:
- **`PhaseClock` не сбрасывается в рабочих потоках.** `@At("RETURN")` не срабатывает при исключении,
  после этого `depth` остаётся ≥ 1 и серия метрик этого потока молчит до конца сессии
  (`ServerMetrics.java:160-164`, `468-501`). Нужен `@WrapMethod` с `try/finally`
  либо сброс при `begin`, если `depth != 0`.
- **Логирование лаг-тиков** (`ServerMetrics.java:179-185`, `408-420`) пишет INFO-строку на каждый тик дольше 50 мс,
  то есть до 20 строк в секунду на и так тормозящем сервере. Нужен лимит частоты (не чаще раза в секунду)
  и кэшированный `Logger`.
- Кольцевые буферы гистограмм получают `0` на тиках, где фаза не выполнялась.
  Это искажает перцентили `chunk.generate` и `network.*`.
- `ConnectionMixin` читает `channel.unsafe().outboundBuffer()` вне event loop Netty.
  Такое чтение нужно перенести в event loop или удалить.
- Комментарий «tens of microseconds per tick» в `ServerMetrics` не соответствует реальности:
  стоимость растёт с числом мобов, чанков и пакетов. Комментарий нужно исправить.

---

## 4. Этап C — корректность и совместимость

| # | Проблема | Файл | Решение |
|---|---|---|---|
| C1 | `cursor_step` включён по умолчанию, отменяет `Cursor3D.advance` и не отключается при Lithium | `UltimaModules.java:94`, `mixin/cursor_step/Cursor3DMixin.java:87-100` | Добавить `LITHIUM_FAMILY` в несовместимые моды |
| C2 | `(long)w*h*d` в `CursorMath.canUseCarry` может переполнить `long` | `util/CursorMath.java:15` | Проверять переполнение через `Math.multiplyExact` или делением перед сравнением |
| C3 | `RecipeCachePolicy.shouldBypassCache` возвращает `false` для неизвестного типа рецепта, хотя по контракту должен быть vanilla-путь | `recipe/RecipeCachePolicy.java:55-59` | `plan == null` → обходить кэш. Ничего не сохранять до `onRecipesReplaced` |
| C4 | `isRedstoneConductor` кэшируется по первой паре `(level, pos)` | `mixin/state_property_cache/BlockStateBaseMixin.java:60-86` | Кэшировать только для классов, для которых доказано, что они не читают мир, либо убрать кэш |
| C5 | Таблица свойств состояний: неатомарное чтение-изменение-запись в `int[]` из нескольких потоков | `cache/state/StatePropertyRuntime.java:450-494` | `VarHandle.getAndBitwiseOr` |
| C6 | `java_mesher`: `enableCaching()` без `finally` — после исключения кэш освещения на воркере остаётся грязным | `mixin/java_mesher/SectionCompilerMixin.java:98-167` | `try/finally` с `clearCache()`, как в `HybridSectionMesher` |
| C7 | `MesherCircuitBreaker.reset()` вызывается только из бенчмарка. Сработавший предохранитель действует до перезапуска игры, даже после F3+T | `meshing/MesherCircuitBreaker.java:75`, `MesherMetrics.java:51` | Вызывать при выходе из мира и при перезагрузке ресурсов |
| C8 | Магический ключ группы `173` для всех полупрозрачных секций | `client/renderer/retained/RetainedTerrainRenderer.java:362` | Использовать тот же ключ, что и vanilla `prepareChunkRenders`. Сверить с `.agent/vanilla-src` |
| C9 | Идентичность меша через `System.identityHashCode` — при коллизии рисуется старый диапазон | `RetainedTerrainRenderer.java:230`, `RetainedSectionRecord.java:87` | Хранить ссылку на `SectionMesh` или монотонный счётчик загрузок |
| C10 | Пайплайны шейдеров не закрываются при `invalidate()` — утечка GPU-программ на каждый F3+T | `RetainedTerrainPipelines.java:39-44,99-109`, `FsrPipelines.java` | Хранить `CompiledRenderPipeline` и закрывать его |
| C11 | GPU-буферы retained terrain освобождаются только в `LevelRenderer.close` и никогда не уменьшаются | `RetainedTerrainRenderer.java:84-95,375-382` | `reset()` при отключении от мира |
| C12 | `section_task_queue`: отменяемый `HEAD` на `poll` и вызов `removeTaskByIndex(-1)` на пустой очереди | `mixin/section_task_queue/SectionTaskDynamicQueueMixin.java:35-66` | Проверять пустую очередь до вызова. Сверить поведение с vanilla |
| C13 | FSR: три `@Redirect` на `mainRenderTarget` с `require = 1` | `mixin/fsr_upscaling/GameRendererMixin.java:51-77` | `@WrapOperation`, чтобы другие моды оставались в цепочке |
| C14 | Детект конфликтующих рендереров только по точным id `sodium`/`iris`/`canvas` | `UltimaModules.java:55` | Дополнить известными форками, а для рендер-модулей проверять наличие классов/entrypoint'ов |

Общее правило для отменяемых `HEAD`-инъекций, включённых по умолчанию
(`entity_section_lookup`, `full_cube_move`, `cursor_step`): по возможности переходить
на `@WrapOperation`/`@WrapMethod` с вызовом `original`, если быстрый путь не подходит.
Образец — `block_collision_shape/BlockCollisionsMixin.java:38-72`.

---

## 5. Этап D — ускорение opt-in модулей (перед тем, как включать их по умолчанию)

Сейчас часть экспериментальных модулей **может оказаться медленнее vanilla**,
потому что их обвязка дороже работы, которую они экономят. Исправлять до любых бенчмарков.

### D1. `state_property_cache`
- `WalkNodeEvaluatorMixin.java:22-43`: при попадании в кэш `getBlockState` вызывается дважды, при промахе — трижды.
  Нужен один `@WrapOperation` вокруг vanilla-вызова.
- `FluidStateMixin`: `isEmpty`/`isSource`/`getAmount` и так являются чтением поля.
  Обёртка с лямбдой, fail-open и упаковкой `Boolean`/`Integer` дороже самого вызова. Эти Mixin'ы нужно удалить.
- `PathType.values()` клонирует массив на каждом попадании. Нужен `static final PathType[]`.

### D2. `tag_bitsets`
`HolderReferenceMixin` на каждый `Holder.is` выполняет HashMap-поиск, `getId`, атомарный инкремент метрики
и проход через fail-open. Нужны плотный индекс тегов (int id → bitset), volatile-снапшот,
никаких проверок конфига и метрики только в бенчмарке.

### D3. `container_slot_mask`
- `SlotMaskTracker`: `Collections.synchronizedMap(new WeakHashMap<>())` — глобальная блокировка
  на все контейнеры сервера и клиента, при этом сравнение идёт по `equals`, а не по идентичности.
  Маску нужно хранить в поле контейнера через Mixin-интерфейс (`@Unique`).
- `tryExactEmpty` создаёт `SlotOccupancy` и лямбду ещё до проверки trusted-бита.
- `filterByHint` всегда создаёт `int[]`, даже если ни один слот не отфильтрован. В этом случае нужно возвращать исходный массив.
- `HopperBlockEntity` и `BaseContainerBlockEntity` оба оборачивают `setItem`/`removeItem`,
  поэтому вызов через `super` проходит маску дважды.

### D4. `blockentity_sleeping`
`WakeRegistry.needsWakes()` синхронизирован и вызывается из `Entity.setPosRaw` для **каждой**
движущейся сущности, а также из `Level.setBlock` и `BlockEntity.setChanged`, даже когда ни один хоппер не спит.
Нужны:
1. `volatile int watcherCount`, который обновляется только в `register`/`unregister`;
2. в `setPosRaw` ранний выход, если сущность не item/container-entity или упакованная координата блока не изменилась;
3. контроллер сна в поле хоппера вместо глобальной `synchronized`-карты.

### D5. `entity_query_early_out`
Нужны флайвейт для 16 комбинаций `EntitySectionOccupant`, `ClassValue<EntityQueryKind>`
вместо обхода иерархии классов и отсутствие лямбды на каждом `getEntities`.

### D6. `recipe_match_cache`
Ключ (`CraftingKey`/`FurnaceKey`) создаётся на каждый поиск и ещё раз при промахе, плюс вызываются `contains` и `get`.
Нужны один `get` с различением «нет записи» и «закэшированный пустой результат»
и сравнение с `lastKey` до аллокации.

### D7. `mesher_fast_path` / `java_mesher` (потоки компиляции секций)
- `HybridSectionMesher.java:162-185,324-357`: `Lookup`/`Result` и лямбда создаются для каждой непустой клетки
  (тысячи объектов на секцию). Нужно изменяемое scratch-состояние в `Scratch` и развёрнутый цикл по 6 граням.
- `MesherMetrics`: `AtomicLong` на каждый блок быстрого пути — общие кэш-линии для всех воркеров.
  Нужны счётчики на поток, публикуемые только в бенчмарке.
- `threadAllocatedBytes()` (`MesherMetrics.java:101-110`) вызывается дважды на каждую секцию без проверки режима.
  Вызывать только в бенчмарке.
- `PackedSectionVolume.begin()` заполняет `states` и `flags` (по 5832 int) через `Arrays.fill`.
  Если захват гарантированно пишет каждую клетку, эти два заполнения лишние (`blockEntitySlot = -1` нужен).
  Сначала проверить, что `capture` покрывает весь объём 18³.
- Интерьерный цикл повторно вызывает `getRenderShape()` и `getFluidState()`, хотя эти биты уже есть в `flags`.

### D8. `retained_terrain`
- `ByteBuffer.allocateDirect` на каждую загрузку заголовка, таблицы секций и indirect-команд
  (`RetainedGpuResources.java:120,142`, `SubmitGroup.java:190`). Заголовок «грязный» почти каждый кадр.
  Нужен переиспользуемый staging-буфер, а для буферов, которые GPU ещё читает, — двойная или тройная буферизация.
- `RetainedGpuTimers.java:53-71`: `pool.getValues` каждый кадр может блокировать CPU, если результат ещё не готов,
  плюс создаётся `OptionalLong[]`. Таймеры нужно убрать за debug-флаг и проверять готовность запроса.
- Полупрозрачный слой перестраивается каждый кадр (новые `EnumMap`, `ChunkSectionInfo`, лямбды).
  Следующий шаг — отдельная retained-группа для translucent, которая обновляется при смене сектора камеры.
- `terrain_retained.fsh` — копия vanilla-шейдера. Переопределения `minecraft:core/terrain` из ресурспаков
  к нему не применяются. `RgssEndpointSpecializer` ищет строку с отступом в 12 пробелов,
  а в копии отступ 4 пробела, поэтому специализация молча не срабатывает.
  Define `ULTIMA_GL_DRAW_PARAMETERS` нигде не читается.

### D9. `fsr_upscaling`
Константы EASU/RCAS нужно кэшировать до изменения размера или резкости: сейчас это 2 `EasuCon`
и 4 command encoder'а на кадр. Загрузку и отрисовку делать одним encoder'ом, а UBO закрывать в `failOpen`.

---

## 6. Этап E — структура проекта

1. **Клиентский код из `src/main` перенести в `src/client`:** пакеты `fsr`, `temporal`, `retained`, `meshing`.
   Сейчас они не ломают выделенный сервер, но `UltimaConfig` (загружается и на сервере) импортирует
   `dev.ultima.fsr.*`. В `main` оставить только маленький record настроек FSR, если серверный конфиг
   должен их сохранять.
2. **Удалить неиспользуемые API**: `ServerTelemetry.recordAiCleanSkip`, `recordAiInvalidation`,
   `recordChunkSerializeCacheMiss` не вызываются. В профилях они показывают вечные нули.
3. **Тесты-grep заменить поведенческими.** `Wave2FailOpenTest:527-634`, `VanillaClientHostingChecks`,
   `ServerTelemetryChecks`, `HopperSleepEquivalenceTest:332` проверяют текст исходников
   (`source.contains("...")`). Это хрупко: тест проходит, пока строка есть, и падает от переформатирования.
   Для Mixin'ов использовать ASM-проверки на скомпилированных классах, как в `MixinBytecodeChecks`.
   Для логики — прямые вызовы helper'ов.
4. **`RecipeMatchCacheTest` не тестирует продакшен-код.** Он сравнивает собственный `CachedScanner` с игрушечными
   рецептами и не вызывает `RecipeFirstMatchCache.lookup/store`. Нужно переписать на реальный класс или
   убрать из описания слово «differential».
5. **Пробелы в покрытии:** `render_snapshot`, `rgss_endpoint`, `section_task_queue`, `java_mesher`
   проверяются только на «модуль выключен по умолчанию».
6. `MixinBytecodeChecks.java:245-250` пропускает отсутствующий каталог `build/classes/java/client`.
   В этом случае он должен падать.
7. `ClientFrameBenchmark` (810 строк) и `TerrainFrameMetrics` (579) лучше разделить: сбор данных, JSON-сериализация, сцены.

---

## 7. Этап F — новые оптимизации, укладывающиеся в guardrails

Начинать только после этапов B и D: сначала обвязка должна стать дешёвой, иначе выигрыш не измерить.

| Идея | Сторона | Суть | Условие безопасности |
|---|---|---|---|
| Пропуск секций без random-tick блоков | сервер | Проверка палитры секции на `isRandomlyTicking` до обхода позиций | RNG и набор тикаемых позиций не меняются: секция пропускается только если в ней гарантированно нет таких блоков. При большой палитре — fallback |
| Коллизия с полным кубом без `VoxelShape` | сервер | AABB-тест против единичного куба вместо `Shapes.block().move(pos)` (сейчас это 4 объекта на вызов в `OffsetCubeVoxelShape`) | Только для `Shapes.block()` и целочисленного смещения; результат касания/пересечения идентичен vanilla |
| Кэш сериализации чанк-пакета | сервер | Не пересобирать данные чанка, отправляемого нескольким игрокам | Ключ — позиция + версия содержимого; инвалидация при изменении блока/света/сущностей. Сначала подтвердить горячесть по метрике `CHUNK_SERIALIZE` |
| Отсечение block-entity renderer'ов по фрустуму | клиент | Более узкий frustum-тест до построения render state | Только рендер, симуляция не трогается; автоотключение при Sodium/Iris |
| Отсечение частиц по фрустуму | клиент | Не рисовать частицы вне видимости | Частицы продолжают тикать как в vanilla |
| Батчинг загрузки мешей секций | клиент | Одна staging-копия вместо загрузки по секциям | Формат вершин и порядок отрисовки не меняются |

---

## 8. Как измерять

Каждое изменение из этапов B, D и F должно сопровождаться замером «до/после».

**Сервер:**
```bash
./gradlew runServer -Pultima.jfr=run/server.jfr      # JFR-профиль
bash scripts/bench-server-ab.sh                        # A/B MSPT
bash scripts/summarize-jfr.sh run/server.jfr
```
В игре: `/ultima profile 60` пишет JSON с фазами тика. Для B5 сравнивать MSPT при включённом
и выключенном `server_metrics` на одной сцене: много мобов, 100+ загруженных чанков, несколько игроков.

**Клиент:**
```bash
bash scripts/bench-client-ab.sh      # A/B кадров, требуется GPU
python3 scripts/summarize-client-bench.py off.json on.json
bash scripts/bench-mesher-ab.sh      # CPU-время мешинга
```
Для B3 и B4 сравнивать средний FPS, 1% low и CPU-время кадра при `temporal`/`terrain_metrics` = on/off.
Для D7 — `meshBuildNsPerSection` и аллокации в JFR (`jdk.ObjectAllocationSample`).

**Требования к бенчмаркам (сейчас не выполняются):**
- записывать `git rev-parse HEAD` в JSON результата;
- `summarize-client-bench.py` должен отказываться сравнивать прогоны с разными GPU/драйвером/vsync/
  дальностью прорисовки/разрешением;
- JVM бенчмарка: `-Xms6G -Xmx6G -XX:+AlwaysPreTouch`, чтобы рост кучи не смещал результаты;
- `bench-server.sh` управляет сервером через `tmux send-keys` и `sleep`. Надёжнее использовать RCON
  или функцию `minecraft:load` в датапаке. Прогон должен завершаться с ошибкой, если количество
  мобов в off/on различается.

---

## 9. Чек-лист для каждой правки

1. Найти vanilla-путь в `.agent/vanilla-src` (`bash scripts/bootstrap.sh`, если каталога нет).
2. Сделать минимальное изменение. Для Mixin'ов: `@WrapOperation`/`@WrapMethod` вместо отменяемого `HEAD`, когда это возможно.
3. Предусмотреть fail-open: при исключении — vanilla-поведение и WARN в лог.
4. `bash scripts/check.sh` — должен быть зелёным.
5. Для общих/серверных Mixin'ов: `bash scripts/mixin-smoke.sh` (реальный `runServer`).
6. Для клиентских Mixin'ов: `runClient` при наличии графического окружения.
7. Замер «до/после» по разделу 8. Без измеренного выигрыша нетривиальную оптимизацию не оставлять.
8. В PR записать: горячий путь, почему поведение эквивалентно, риски совместимости, способ проверки в игре.

## 10. Рекомендуемый порядок

1. **A1, A3, A4, A5** — сборка и CI. Это ускоряет все последующие итерации.
2. **B1, B2** — дешёвая обвязка. Это предусловие для всех остальных замеров.
3. **B3, B4, B5, C1** — выигрыш для всех игроков с настройками по умолчанию.
4. **C2–C14** — корректность.
5. **D1–D9** — довести opt-in модули до состояния, когда они стабильно быстрее vanilla.
6. **E** — структура и тесты (можно параллельно с п. 4–5).
7. **F** — новые оптимизации, по одной, каждая с A/B-замером.
