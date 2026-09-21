# ULTIMA_NEXT_PHASE_AUDIT

Аудит по production-коду. Документы, README, имена модулей и комментарии не использовались как источник истины.

## Источник, который реально прочитан

Проверенное дерево workspace — `main` @ `e6763a8`. В нём **нет** `iris_shader_frontend_artifact_cache`, `cross_pipeline_admission_broker` и `render_warmup_system`.

Эти три модуля, детерминированный replay и killer A/B harness живут только в `origin/agent/killer-modules-v1` @ `4f6ca5c` (main плюс около 10k строк). Этот коммит и есть текущий незамёрженный engineering head. Ниже «код» значит `4f6ca5c`, если не сказано иное.

`4f6ca5c` также **удаляет** относительно `main`:

- `src/test/java/dev/ultima/review/MixinBytecodeChecks.java` (336 строк bytecode-контрактов);
- `src/main/java/dev/ultima/mixin/container_slot_mask/ChestBlockEntityMixin.java` (`swapContents`).

Игровой A/B, `runClient`, `runServer` и GPU-бенчмарк не запускались. Performance value трёх experimental-модулей не доказан и из этого аудита не следует.

---

## 1. Executive summary

На стеке Sodium + Iris + Lithium текущая Ultima почти ничего не ускоряет.

Из 27 модулей при таком стеке mixin plugin оставляет включёнными по умолчанию только три:

- `cursor_step` — замена деления в `Cursor3D.advance` на перенос разряда;
- `server_metrics` — всегда включённые таймеры, включая `Mob.serverAiStep`, `Brain` и Netty encode/compress/cipher;
- `settings_ui` — кнопка на title screen.

Всё, что похоже на настоящую оптимизацию рендера, мешинга, коллизий, хопперов, тегов и pathfinding, помечено `incompatibleMods` и **не применяется**, пока загружены Sodium/Iris/Canvas или Lithium/Canary/Radium. Это безопасная архитектура и одновременно провал продуктовой цели.

Три experimental-модуля — единственная попытка работать поверх этого стека. В текущем виде:

| Модуль | Default | Что код делает на том jar, под который он подписан | Можно ли считать performance-фичей |
|---|---|---|---|
| `iris_shader_frontend_artifact_cache` | OFF | Ключ никогда не строится: схема полей требует `Parameters.textureOverrides`, в Iris `1.11.4+mc26.2` этого поля нет. Transform всегда идёт в Iris. | Нет. Это не кэш. |
| `cross_pipeline_admission_broker` | OFF, mode `trace` | `trace` ничего не отменяет. `control`/`static` отменяют весь deferred submit Sodium. Задачи не теряются и не дублируются. Выигрыш frametime возможен только потому, что полезная работа откладывается. | Нет, пока метрика — FPS, а не time-to-visible. |
| `render_warmup_system` | OFF, mode `warm` | Единственный adapter 18 раз читает `static final` геттеры `RenderTypes` и выбрасывает результат. Pipeline не компилируется. | Нет. |

Одного и того же ключа, который даёт два разных корректных transformed shader, в этом билде нет: ключ не создаётся. Это не доказательство полноты ключа на будущем jar. После починки схемы тот же hit path всё равно тяжелее, чем RAM LRU Iris на 400 записей, потому что каждый hit заново хеширует исходник и читает диск.

Жёсткий вывод: половина репозитория — vanilla-only рендерер и Lithium-shaped симуляция, которые целевой пользователь никогда не исполняет. Вторая половина — инфраструктура (config, fail-open, метрики), которая на включённых second-wave модулях дороже самой оптимизации. Killer-модули написаны в правильном слое (Iris CPU frontend, Sodium admission, first-use), но первый сломан об собственный fingerprint jar, второй умеет изображать ускорение, третий не прогревает GPU.

P0-крашей на дефолтном S+I+L конфиге нет. Есть P1, которые делают ближайший runtime-тест бессмысленным или вредным. Их надо закрыть до любого A/B, который кто-то будет читать как результат.

---

## 2. Current Ultima architecture

### Реестр

`UltimaModules` — список из 27 `Module(key, enabledByDefault, dependencies, incompatibleMods, clientOnly)`. Ключ модуля равен пакету mixin (`dev.ultima.mixin.<key>`).

`UltimaMixinPlugin.shouldApplyMixin` один раз на старте вызывает `UltimaConfig.isEnabled` и не применяет mixin выключенного модуля. Это единственная причина, почему module OFF на горячем пути равен нулю. Рантайм-флага «модуль выключен» на применённом mixin нет: настройка требует рестарт.

`fabric.mod.json`: environment `*`, common mixins в `ultima.mixins.json`, client mixins в `ultima.client.mixins.json`. Клиентские классы не тянутся сервером через common mixin config. Это соблюдено.

### Включение

`UltimaConfig.isEnabled(String)` на **каждый** вызов:

1. аллоцирует `HashSet`;
2. линейно ищет модуль в `UltimaModules.byKey`;
3. `loadedIncompatibleMods` всегда аллоцирует `ArrayList`, даже если список несовместимости пуст (`UltimaConfig.java` 506–509);
4. вызывает `FsrCompatibility.blocks(module)` для любого ключа, не только `fsr_upscaling`;
5. вызывает `KillerModuleCompatibility.isSupported`. Для не-killer модуля `state()` каждый раз аллоцирует новый `AdapterState` (`KillerModuleCompatibility.java` 64–65).

Горячие вызовы, пока соответствующий модуль включён и его mixin применён:

- `TagBitsetRuntime.moduleEnabled` из `lookupUnchecked` — каждый `Holder.is(TagKey)`;
- `StatePropertyRuntime.moduleEnabled` — каждый кэшируемый property probe;
- `FsrUpscaling.moduleEnabled` — каждый кадр, если FSR mixin применён.

На дефолтном S+I+L эти три пути не применены. Налог существует в тот момент, когда second-wave модули включают для измерения.

Зависимости: `collision_shell_skip` требует `cursor_step`. `java_mesher` пропускается, если включён `mesher_fast_path` (оба всё равно выключены при Sodium).

### Совместимость

Семейства зашиты в реестр:

- Lithium / Canary / Radium: entity section, collision shape, shell skip, supporting-block skip, full-cube move, hopper sleep, tag bitsets, state cache, slot mask, entity-query early-out.
- Sodium / Iris / Canvas: terrain metrics, retained terrain, render snapshot, java mesher, mesher fast path, section task queue, RGSS, temporal.
- FSR: Canvas в `incompatibleMods`. Iris выключается не через этот список, а через `FsrCompatibility.blocks`: списки известных Iris post-final и internal-resolution методов пустые, поэтому Iris всегда блокирует FSR. Sodium без Iris FSR не блокирует.
- Killer Iris/Sodium: fail-closed по точному version string, SHA-256 всего jar и SHA-256 одного class entry. Прогрев внешних fingerprint не требует.

`LoadedModCache` после первого probe нормальный. Дорогие аллокации вокруг него, а не сам cache.

### Fail-open

`FailOpenGuard` обслуживает recipe, tag bitsets, state cache, slot mask, entity-query. Успешный `supply` / `test` / `callNullable` / `run`:

- два volatile-чтения `maybeThrowForTest` (тестовый fault arm, в проде всегда null);
- `ConcurrentHashMap.remove` в `recordSuccess`, даже если ключа не было.

Circuit breaker: 3 подряд, не больше 256 tripped keys. `consecutive` не ограничен.

Killer-модули этим классом не пользуются. У них свой volatile `failedOpen` latch. Это две разные модели.

### Метрики

- `server_metrics` default ON, без incompatible mods.
- `terrain_metrics` default ON, выключается на Sodium/Iris/Canvas. На vanilla каждый кадр обходит draw groups.
- `client_benchmark` default OFF. Если модуль включён, mixin на `renderFrame` вызывает `timerQuery.get()` даже когда property `ultima.clientBenchmark` ложь. Сам `beginFrame` при выключенном property выходит сразу.
- Killer diagnostics пишутся в JSON бенчмарка.

### Бенчмарк

`ReplayTimeline` в режиме `tick` (дефолт) задаёт `routeUnit = gameTick - firstGameTick`. Кадры одного тика видят один и тот же unit. Режим `frame` сохранён и снова делает путь зависящим от FPS.

Камера двигается только при `yaw_sweep`, `chunk_flight` или hold/fixed position (`ClientFrameBenchmark.applyCamera`). Сцена `killer_route`, которую ставит `scripts/bench-killer-modules-ab.sh`, через `cameraModeForScene` получает **`stationary`**. Tick-маршрут для killer A/B камеру не двигает.

### Внешние адаптеры

- Iris: `TransformPatcherMixin` на private `transform` / `transformCompute`. Fingerprint: Iris `1.11.4+mc26.2`, jar `f1f7ab57…dd7c`, class `101fb251…c25f`. Сверено с разобранным `Parameters.class` этого jar.
- Sodium: `RenderSectionManagerMixin` и соседи. Fingerprint: Sodium `0.9.2+mc26.2`, jar `16a5e91d…864f`, class `5a409c73…864f`.
- C2ME: строка `present_no_stable_public_pressure_api`. Наблюдателя давления нет.
- Прогрев: своих внешних адаптеров нет. Строки `iris_programs`, `geckolib`, `modernfix_dynamic_resources` записываются в diagnostic status и никогда не вызываются.

---

## 3. Current performance reality

Доказанного выигрыша FPS, 1% low, MSPT, загрузки или stutter поверх Sodium + Iris + Lithium в этом репозитории нет. Игровой прогон не делался. По коду ожидание такое:

**Дефолт, S+I+L.** Пользователь получает `cursor_step` и `server_metrics`. Первое — несколько веток вместо деления на тех `Cursor3D`, которые Lithium не заменил. Второе — два `nanoTime` и atomic add на каждую инструментированную фазу, и отдельно пара begin/end на каждого моба и на encode/compress/cipher. Это не ускорение. Остальные default-on модули (`terrain_metrics`, `temporal`, collision family, entity section) mixin plugin не применяет.

**Дефолт, чистый vanilla.** Включены collision fast-paths, entity section lookup, cursor, temporal passthrough, terrain metrics, server metrics. Это единственная конфигурация, где Ultima вообще меняет работу кадра и тика. Temporal не меняет пиксели и каждый world frame копирует матрицы и читает system property. Terrain metrics каждый кадр считает draw groups. Относительно Sodium это не конкурент: Sodium заменяет сам terrain pipeline, и Ultima в этот момент отключается.

**Opt-in second wave** (recipe, tags, state, slot mask, entity early-out, hopper sleep) выключены и на Lithium не живут, кроме recipe. На включённом пути перед полезной работой стоят `isEnabled` и `FailOpenGuard`.

**Три killer-модуля.** Выключены. При включении на точном Iris 1.11.4 кэш даёт 0 hits. Broker в `trace` добавляет телеметрию и не меняет очередь. Broker в `control` может снизить среднее время кадра, не сделав чанки быстрее видимыми. Warmup в `warm` трогает уже инициализированные static fields после `ShaderManager.apply`.

Итог для продукта: Ultima сейчас — набор vanilla-микропатчей с правильным auto-disable плюс три неготовых эксперимента. Смысла «поставить поверх Sodium, Iris и Lithium» в текущем коде нет.

---

## 4. Critical P0/P1 findings

P0 нет. Нет сценария, в котором дефолтный конфиг портит мир, теряет чанк или отдаёт чужой shader. Условные P1 ниже обязательны до runtime-теста, иначе тест измеряет не ту вещь.

### ICA-1 — Iris cache не строит ключ на единственном поддерживаемом jar

- Severity: **P1**
- File: `src/client/java/dev/ultima/client/iris/cache/IrisTransformKeyEncoder.java`
- Method: `fieldSchema`, `buildPlan`
- Lines: 321–330, 106–112
- Failure: `Parameters` в Iris `1.11.4+mc26.2` имеет поля `patch`, `textureMap`, `type`, `name`. Схема требует ещё `textureOverrides`. `buildPlan` сравнивает множества имён и возвращает unsupported. `encode` возвращает null на каждом concrete parameter type, потому что все наследуют `Parameters`. `IrisFrontendArtifactCache.begin` пишет unkeyable miss и не отменяет transform.
- Impact: модуль «включён», fingerprint jar совпадает, `failedOpen == false`, hit rate равен нулю. Каждый вызов всё равно платит backend-check и reflective `areDebugOptionsEnabled`. A/B cold/warm покажет шум и накладные расходы, не кэш.
- Fix: убрать `textureOverrides` из схемы (или генерировать схему из этого class file). Тест должен грузить реальный `Parameters` / `SodiumParameters` / `VanillaParameters` из pinned jar или checked-in `javap` dump и требовать `encode(...) != null`. Пока этого теста нет, stubs в `src/test/java/net/irisshaders/...` содержат `textureOverrides` и тест зелёный на коде, который в игре не работает.

Ответ на главный вопрос этапа 3A: **same key → different correct output в этом билде не достижим**, потому что same key не создаётся. Это не сертификат полноты ключа.

### CPB-1 — control mode залипает после одного тяжёлого кадра

- Severity: **P1**
- File: `src/main/java/dev/ultima/broker/AdmissionController.java`
- Method: `updateHysteresis`, `permit`
- Lines: 208–223, 148–166
- Failure: вход в `pressureHigh`, если последний кадр > 150% target (25 мс при 16.67 мс) или p95 > 108% target. Выход только если p95 < 92% target (15.33 мс) и GPU low и server low. Клиент, который стабильно сидит на бюджете кадра, после одного hitch не выходит: 16.67 мс не меньше 15.33 мс.
- Impact: deferred initial build и не-important rebuild остаются на duty cycle 50 мс до конца сессии. Средний frametime падает, потому что кадры не мешат. Чанки перед игроком ждут.
- Fix: отпускать давление, когда p95 снова ниже порога входа, а не ниже 92% target. Одиночный sample не должен latch'иться без срока.

### CPB-2 — deny отменяет весь deferred loop

- Severity: **P1**
- File: `src/client/java/dev/ultima/mixin/cross_pipeline_admission_broker/RenderSectionManagerMixin.java`, `AdmissionController.permit`
- Method: `ultima$admitDeferredBeforeDequeue`
- Lines: mixin 51–63; controller 155–166
- Failure: `ci.cancel()` на HEAD `submitDeferredSectionTasks` пропускает весь `while`, не один task. `busyWorkers` / `totalWorkers` записываются и `permit` их не читает. Следующий permit запускает полный бюджет Sodium, не «долг». При 16 мс кадре и minimum interval 50 мс примерно один кадр из четырёх мешит, остальные нет.
- Impact: единственный механизм улучшения frametime — откладывание полезной работы. Задачи не теряются: pending flag Sodium остаётся, следующий не-cancel их соберёт заново. Двойного исполнения нет: broker не вызывает `submitSectionTask`. «Чанк никогда не станет видимым» навсегда — нет, пока живёт minimum interval 50 мс. «Чанк видимый позже, а FPS красивее» — да.
- Fix: не отменять метод целиком. Если gate остаётся, уменьшать уже существующий upload/build budget и отдавать пропущенный backlog на следующем permit. Успех измерять временем до upload, не средним frametime.

### CPB-3 — GPU-вход контроллера мёртв

- Severity: **P1**
- File: `src/client/java/dev/ultima/mixin/cross_pipeline_admission_broker/MinecraftMixin.java`
- Method: `ultima$brokerFrameEnd`
- Lines: 24–28
- Failure: читается `TimerQuery.get()`. Вызова `getStatus()` нет. В 26.2 `get()` усредняет массив результатов, который заполняет `getStatus()`. Без F3 GPU-utilization или metrics recorder слоты остаются 0, broker пишет `-1`. `gpuHigh` ложь, `gpuLow` истина (`latestGpuNanos <= 0`). GPU не закрывает gate и не мешает release.
- Impact: GPU-bound кадр с дешёвым CPU никогда не defer'ится. Документированный GPU-вход не существует в обычной игре. Тот же `timerQuery.get()` стоит в benchmark mixin: колонка GPU в JSON на том же условии пустая.
- Fix: на render thread вызвать `getStatus()` и брать последний ненулевой sample. Пока статус `AWAITING_VALUES`, передавать `-1` и не считать это «GPU в порядке» в release-условии.

### W-1 — warmup не прогревает GPU

- Severity: **P1**
- File: `src/client/java/dev/ultima/client/warmup/VanillaRenderTypeWarmupAdapter.java`
- Method: `warm`
- Lines: 11–29, 51–57
- Failure: mode `warm` (дефолт при включённом модуле) вызывает 18 no-arg геттеров `RenderTypes` и выбрасывает `RenderType`. В 26.2 это `static final` accessors. Они не вызывают `RenderType.prepare()`, `GpuDevice.precompilePipeline` или `loadCriticalShaders`. Класс к моменту RETURN `ShaderManager.apply` уже инициализирован vanilla (font, item, level, публичные `LINES`). Повторный reload class init не повторяет.
- Impact: diagnostics могут сказать `warmed`. Пользовательский hitch на glint, portal, lightning, text, entity cutout не двигается. Texture-keyed фабрики (`text(Identifier)`, `entityCutout`, `eyes`, `armorCutoutNoCull`) в список не входят. Пассивные строки Iris/GeckoLib/ModernFix не имеют классов.
- Fix: удалить adapter как performance-работу. Если направление живо, единственный осмысленный вызов — `precompilePipeline` / `loadCriticalShaders` для pipeline, который ещё cold после `ShaderManager.apply`, на render thread, с дедлайном внутри вызова. Пока этого нет, mode по умолчанию должен быть `profile`, а JSON не должен содержать `changesRenderInitialization: true`.

### BENCH-1 — killer A/B не измеряет то, ради чего написаны модули

- Severity: **P1**
- File: `scripts/bench-killer-modules-ab.sh`, `ClientFrameBenchmark.cameraModeForScene`, `applyCamera`
- Lines: script scene `killer_route`; `cameraModeForScene` 961–963; `applyCamera` 334–338; warmup/sample defaults 924–934
- Failure: `killer_route` → camera mode `stationary`. `applyCamera` выходит, не вызывая `snapTo`. Tick timeline тикает, камера стоит. Дефолт 200 warmup ticks + 1200 sample ticks смотрит на уже построенный спавн. Shader reload внутрь sample не ставится. `artifact-cold` двигает каталог кэша только у ON и делает это до входа в мир; compile, если он есть, попадает в загрузку/warmup, не в окно. `artifact-warm` праймит отдельным прогоном, sample снова stationary.
- Impact: cache A/B не видит reload. Broker A/B почти не видит streaming. Warmup A/B сравнивает `profile` и `warm` при включённом модуле с обеих сторон (`OFF_MODULES = ON_MODULES` в профиле `warmup`) и измеряет 18 field read, которые уже случились на reload до sample.
- Fix: для broker — `chunk_flight` с tick-rate, и fail прогона, если `deferrals == 0` в control. Для cache — принудительный shader reload на фиксированном route tick внутри sample и fail, если `hits == 0` (после ICA-1). Для warmup — не публиковать frametime delta как результат, пока adapter не вызывает compile.

---

## 5. P2/P3 findings

### P2

**ICA-2.** `IrisFrontendArtifactCache.begin` / `materialize`. Даже после починки схемы hit отменяет Iris `transform` до статического LRU на 400 записей и каждый раз делает SHA-256 исходника плюс чтение файла и новые `String`. In-process reload, который Iris отдал бы из RAM, становится диском. Диск имеет смысл на холодном процессе, не на втором apply в том же процессе.

**ICA-3.** `ArtifactCacheStore.cleanupIfNeeded`. Eviction удаляет жертву без её stripe lock и делает `index.remove` по ключу, не по идентичности `EntryMeta`. Параллельная запись того же ключа может быть удалена. Порча payload при этом не собирается: чтение либо miss, либо checksum fail.

**ICA-4.** `ArtifactCacheStore.read`. `IOException` увеличивает `readFailures` и оставляет index entry. Следующие lookup повторяют ошибку.

**CPB-4.** `AdmissionController.Config` делает `maximumDeferNanos = max(minimum, maximum)`. `permit` сначала проверяет maximum, затем minimum. Minimum (50 мс) разрешает раньше, чем maximum (250 мс) вообще может сработать, и `markPermit` обнуляет `deferredSince`. Ветка 250 мс мертва. Тест `overloadedQueueCannotStarve` допускает на 51 мс и проходит с удалённым maximum.

**CPB-5.** Возраст starvation — `nanoTime - pendingUpdateSince` головы heap, не самого старого task. Sodium `clearPendingUpdate` не обнуляет timestamp. Important submit оставляет секцию в deferred heap с древним timestamp, `urgent` становится true, весь batch обходит control. По-настоящему старая секция за молодой головой этот bypass не получает. Живость задач держит 50 мс interval, не 2-секундный escape.

**CPB-6.** Server enter > 45 мс, exit < 40 мс. Singleplayer spike, после которого MSPT сидит в 40–45 мс, вместе с CPB-1 не отпускает pressure. Multiplayer передаёт `-1` и server не latch'ится.

**CPB-7.** Сдвиг камеры > 128 блоков делает `telemetryAtNanos = MIN_VALUE`. `prepareFrame` случается до `updateChunks` того же кадра, stale check в `permit` открывает полный deferred batch ровно на hitch-кадре (телепорт, элитра). `reason` у reset не используется.

**CPB-8.** Телеметрия врёт, scheduling не меняет. `sodiumTaskSubmitted` на HEAD 3-arg `submitSectionTask` считает no-op после important path. `meshReady` считает output, который `addBuildOutput` отклонил. RETURN `ChunkJobTyped.execute` считает completion после mid-flight cancel. `reset` не чистит кольца. `outstandingInitialBuilds` — неатомарный `++` на volatile.

**W-2.** Inject на RETURN `ShaderManager.apply`. Vanilla уже скомпилировал то, что компилирует внутри apply. Следующий read не переносит эту работу раньше. `clearPipelineCache` на следующем reload GPU pipeline сбрасывает, static `RenderType` не пересобирается, generation token нет.

**W-3.** `FirstUseProfiler.begin` на каждый `RenderType.create` и каждый `precompilePipeline` до конца процесса аллоцирует строку ключа и берёт общий lock, чтобы часто вернуть `Token.IGNORED`. После третьего сэмпла дешёвого выхода нет.

**W-4.** Первый `FluidRenderer.tesselate` на chunk worker помечает кадр render thread как first-use. `IN_WARMUP` — `ThreadLocal` только pump-потока. Один fluid mesh выключает профайлер навсегда.

**W-5.** `EntityRenderers.createEntityRenderers` и `ParticleResources.registerProviders` — eager vanilla. Обёртка таймера вокруг работы, которая и так выполняется. Warmup их не вызывает.

**W-7.** Бюджет 2 мс проверяется до `warm()`, timeout — после возврата. Один `get()`, если бы он реально исполнял `<clinit>` или compile, может стоить весь кадр. Не-render thread: `pump` молча возвращается, план остаётся pending.

**W-8.** `DEFERRED` и `CANCELLED` терминальны. Disconnect не перезапускает план. Следующий шанс — новый `ShaderManager.apply`. Adapter, который успел дотронуться до всех item, но вышел за timeout, записывается как timeout.

**D4 / HOP-1.** `HopperWorldInspector.watchPositions` — сам хоппер, клетка сверху, клетка facing. `VanillaContainerClassifier.classifyCompound` возвращает `sleepSafe`, если обе половины allowlist. `onContainerMutated` будит по `blockEntity.getBlockPos()`. Вторая половина двойного сундука — другой pos, в watch list её нет. Пустой double chest, хоппер смотрит в одну половину, предметы кладут в другую: хоппер остаётся спать. Модуль default OFF и выключен на Lithium. На vanilla при включении это потеря переноса предметов.

**D5.** `BrewingFirstMatchCache.invalidate()` никто в production не вызывает. `PotionBrewingMixin` не сбрасывает таблицу на пересборке mixes. Datapack reload варит по старому первому совпадению до рестарта процесса. Recipe crafting table чистится из `RecipeManager.apply`. Brewing — нет.

**D6.** `BlockStatePropertyPack.pathType` на hit вызывает `PathType.values()`, который копирует массив enum.

**D7.** `HybridSectionMesher.compile` на каждую секцию: 4× `nanoTime` и 2× `ThreadMXBean.getThreadAllocatedBytes`. Модуль, который должен мешить быстрее, на каждой секции спрашивает JVM про аллокации. Default OFF, при Sodium mixin не применяется.

**D8.** `server_metrics` default ON на S+I+L. `MobMixin` и `BrainMixin` дают 4 `nanoTime` на моба за тик. Три Netty encoder mixin — пара `nanoTime` на пакет. `logLagTick` при MSPT > 50 мс делает `String.format` и INFO. Это ухудшает лаг, который измеряет.

**D9.** `SectionTaskDynamicQueueMixin.ultimaCompactPoll`: пустая очередь оставляет оба индекса −1, условие `!hasRecompileTask` истинно, вызывается `removeTaskByIndex(-1)`. Раннего `setReturnValue(null)` нет. Vanilla-тело метода в дереве нет; если callee не принимает −1, это крэш на пустом poll. Модуль default OFF и выключен на Sodium.

**D10.** `WalkNodeEvaluatorMixin` на HEAD сам вызывает `level.getBlockState`, затем при miss vanilla читает state ещё раз.

**D11.** `StatePropertyRuntime.classifyBlock` обновляет слово таблицы обычным read-modify-write без атомика. Потерянный update — лишний miss, не чужой hit, пока биты только ставятся из vanilla return.

**D13.** `SlotMaskTracker` — `Collections.synchronizedMap(WeakHashMap)`. Каждый слот хоппера: глобальный lock, потом `FailOpenGuard.test`, потом CHM `remove`. Verify раз в 32 использования: пропущенная мутация до этого момента пропускает занятый слот.

**D14.** На `4f6ca5c` удалён `ChestBlockEntityMixin.swapContents`. Каталог `VanillaInventoryMutationSources` по-прежнему пишет, что `swapContents` закрыт через `setItems`. На `main` отдельный mixin существует именно потому, что это прямая подмена ссылки на список. При мёрже killer-ветки маска сундука после `swapContents` снова может остаться trusted и описывать чужое содержимое. Модуль default OFF.

**D15.** Успешный recipe lookup аллоцирует `FurnaceKey` / `CapturedStack` и проходит `FailOpenGuard`. На 4096 записях `FirstMatchTable.put` делает `clear()`.

**D16.** `EntitySectionOccupant.of` аллоцирует объект на add/remove, чтобы обновить счётчики. Ранний выход срабатывает только на пустой секции; непустой запрос платит probe и всё равно идёт в vanilla.

**D17.** `terrain_metrics` `LevelRendererMixin` после prepare обходит все draw groups каждый кадр. На Sodium модуль выключен. На vanilla это default ON и ничего не ускоряет.

**D18.** `temporal` default ON, на Sodium/Iris/Canvas выключен. На vanilla `applyRequestedMode` каждый world frame читает `System.getProperty`, backend — passthrough, пиксели те же.

**D20.** `CompileTaskMixin` меняет `onSpinWait` на `LockSupport.parkNanos(50_000)`. Ожидание upload становится не короче 50 мкс.

**D21.** `RenderRegionCacheMixin`: если `createRegion` бросает, RETURN `unbind` не выполняется, `ThreadLocal` intern table остаётся. Следующий extract на этом потоке шарит maps мёртвого cache.

**D22.** `OffsetCubeVoxelShape.move` складывает `offsetX + (int) dx`. Переполнение `int` заворачивает куб. Vanilla double-списки так не делают. Общий mutable `BitSetDiscreteVoxelShape` (`UNIT`) стоит у всех таких shape.

**D23.** `Cursor3DMixin` interior index считается в `int` (`width * height + width + 2`). `canUseCarry` проверяет объём в `long`, не этот промежуточный index.

**REG-1.** Удаление `MixinBytecodeChecks` на killer-ветке. Контракт сигнатур mixin больше не проверяется тестом, который был на `main`.

### P3

**ICA-5.** Verify mode увеличивает `hits` и `transformNanosSaved` до сравнения. При mismatch вызывающий всё равно получает свежий результат Iris, файл удаляется, кэш latch'ится off. Счётчики врут, shader — нет.

**ICA-6.** Null device latch'ится как `unsupported_gpu_backend` на весь процесс.

**W-6.** Diagnostic rows Iris/GeckoLib/ModernFix выглядят как adapters.

**W-9.** Seen-set профайлера ограничен 2048, не evict'ится, держит строки до конца процесса. Сверх cap ключи пропадают без счётчика.

**W-10.** `gpuResourceDelta` захардкожен `-1`. `memoryDeltaBytes` — `totalMemory - freeMemory` за окно. `changesRenderInitialization()` истинен на весь mode `warm`.

**W-11.** При включённом модуле каждый кадр платит `beginFrame`/`endFrame` и idle `pump`, даже когда план терминален.

**D24.** FSR при применённом mixin зовёт полный `isEnabled` каждый кадр. Redirect `mainRenderTarget` тоже стоит на кадрах, где upscale не активен.

**D25.** Recipe table на переполнении очищается целиком.

**CPB-9.** Trace mode на каждый кадр: несколько `nanoTime`, `getAverageTickTimeNanos()` integrated server, раз в 8 кадров sort 128, `SectionStorage` lookup головы heap, volatile increments. Это не ноль. Рядом с мешингом мало. Для модуля, который в trace ничего не меняет, это вся его цена.

---

## 6. Iris Artifact Cache review

### Поток

Вызовы Iris (`ShaderCreator`, composite/final/shadow/DH) попадают в private `TransformPatcher.transform` / `transformCompute`. Mixin стоит на этих двух методах. `patchDepth` не кэшируется и из других классов этого jar, по разбору агента, не зовётся.

Miss: тело Iris выполняется. У Iris свой static LRU на 400 ключей (`parameters.equals` + исходники + `zZeroToOne` + shadow). Потом `EnumASTTransformer`. Ultima на RETURN сохраняет `Map<PatchShaderType, String>`. Бинарники GL не сохраняются. Это правильно: handle не переносится между процессами и драйверами.

Hit: `cir.setReturnValue` на HEAD, тело Iris и его RAM LRU не исполняются.

### Ключ

Хешируется schema int, домен, adapter id, fingerprint jar+class, версии Iris и Minecraft, debug options, `isZZeroToOne`, graphics/compute, имя программы, стадии с presence-bit (null и `""` различаются), имя класса parameters, и нестатические поля иерархии кроме scratch `type` и `name`.

Поля, которые схема согласна кодировать, если имя класса совпало: patch, texture map, alpha, shadow, geometry flags, texture stage, vanilla inputs, hasChunkOffset, isLines, isClouds. Shader options как отдельные uniforms в transform closure этого jar не читаются; они уже в тексте исходника, а исходник в ключе.

Не входит в ключ и не должно, пока результат от них не зависит: backend-строка (есть отдельный latch «только opengl»), `Parameters.type` / `name` (Iris затирает их на каждую стадию; имя программы передаётся аргументом и хешируется).

`textureOverrides` входит в ожидаемое множество и **отсутствует в jar**. Поэтому остальная полнота ключа сейчас не исполняется.

Риск same-key/different-output после починки схемы, который надо закрыть тестом на реальном class file, а не stubs:

- порядок `textureMap`, если transformer не коммутативен (сейчас encoder заявляет order-independent payload; это надо сверить с `TextureTransformer.forEach`);
- debug options и `VK` conformance, если их начнут звать из transform после обновления Iris;
- статические поля transformer'а, которые не являются аргументом `Parameters`.

Пока схема не совпадает, это гипотезы, не воспроизведённый баг.

### Диск

Формат: magic `UIFA`, schema 2, 32 байта ключа, время, nanos, длина, SHA-256 payload. Null стадия и пустая строка различаются presence-bit. Чтение требует совпадения ключа, отсутствия хвоста, лимита 8 стадий. Запись: temp в том же каталоге, `force(true)`, `ATOMIC_MOVE`, fallback на неатомарный replace только если atomic move не поддерживается. Checksum отсекает рваный файл.

Нет file lock и нет подхвата индекса другого процесса до рестарта. Два клиента на одном cache dir могут потерять запись (ICA-3), не смешать байты. Лимиты: 256 МиБ, 2048 записей, payload до 64 МиБ. Eviction по mtime, не чаще раза в 60 с. Новый ключ защищён от собственного cleanup, поэтому один файл может превысить `maxBytes`.

### Hit path, если ключ когда-нибудь появится

На каждый вызов, включая hit: список стадий, reflective debug flag, SHA-256 всего исходника и графа parameters, stripe lock, чтение всего файла, второй SHA-256, новые строки, `EnumMap`. RAM-слоя нет. `force(true)` на каждой записи. Это легко дороже повторного transform для маленькой программы и имеет смысл только если glsl-transformer на паке стоит заметно больше диска. В репозитории такого измерения нет.

Verify mode гоняет оба пути и сравнивает `Map.equals`. Это единственный честный режим для первого прогона после смены схемы. Сейчас он недостижим.

Reload: `ShaderManagerMixin` только чистит `ThreadLocal` и пишет wall time. Диск не сбрасывается. Это верно, если ключ полный: другой пак — другой исходник — другой ключ. Пока ключ не строится, reload ничего не доказывает.

Вывод: направление (персистентный CPU frontend, не подмена driver compile) — единственное в репозитории, которое Iris сам не закрывает диском. Реализация на подписанном jar не работает. Чинить схему и мерить reload. Не мерить stationary FPS.

---

## 7. Admission Broker review

### Lifecycle Sodium, как его режет код

Sodium владеет очередью. Broker не dequeue'ит.

1. `onSectionAdded` ставит `INITIAL_BUILD`. Важные block updates идут в `importantTasks`.
2. Cull собирает `DeferredTaskList`. Непосещённые pending остаются флагом на секции и попадут в следующий список.
3. `updateChunks` за кадр (кроме Flawless Frames) сначала submit'ит important, потом deferred.
4. Mixin может отменить deferred метод до `dequeueNextSectionPos`. Important, blocking и awaited к этому моменту уже ушли.
5. `urgent` — это Flawless Frames **или** возраст головы heap > 2 с. Не телепорт и не ломание блока. Urgent всегда `permit == true`.
6. Sodium сам снимает pending type при постановке job. Повторный 3-arg submit с type 0 — no-op. Двойного исполнения broker не добавляет.
7. `setInfo` ставит built-флаг до upload. Счётчик «first renderable» срабатывает здесь, не когда пиксели на экране.
8. Смена мира, disconnect, shader reload, камера > 128 блоков зовут `AdmissionController.reset` и не чистят кольца метрик.

### Потеря, дубль, вечная невидимость

- Потеря task: нет. Cancel не снимает pending flag.
- Двойное исполнение: нет. Broker не schedule'ит.
- Вечная невидимость: нет при живом 50 мс interval. Есть долгая невидимость на всё время `pressureHigh`.
- Ложный FPS: да, в `control` и `static`. `static` просто разрешает каждый N-й вызов `permit` (дефолт 2), игнорируя давление. Это честный «мы пропускаем работу» baseline, не оптимизация.

`trace` (дефолт, если модуль включили и property не задан) не отменяет. Неизвестный mode string тоже `trace`.

Module OFF: mixin не применяется, накладных нет. Module ON + fail-open: mixin остаётся, `permit` возвращает true после одного volatile read. Часть хуков (камера, upload) снаружи try/catch.

`shouldApply` проверяет hash jar и одного класса, не дескрипторы методов. Inject'ы Sodium стоят с `require = 0`. Промах по методу молча отключает кусок: телеметрия без gate или gate без age peek. Плохой `@Shadow` валит старт. Скрипт `check-killer-adapters.sh` не проверяет все цели, которые mixin реально трогает (`ChunkJobTyped.started`, `baseOffsetX`, `updateChunks`, `prepareFrame`).

Hysteresis, мёртвый maximum defer, мёртвый GPU и head-age bypass разобраны как CPB-1..7.

Вывод: брокер не владеет задачей и поэтому не теряет её. Он владеет только правом войти в чужой цикл. Это слишком грубый рычаг для frametime. Пока успех определяется средним временем кадра, модуль будет «выигрывать» вредным способом. Пока GPU sample не читается, он ещё и слепой.

---

## 8. Render Warmup review

Единственная реализация `WarmupAdapter` — `VanillaRenderTypeWarmupAdapter`. Она зарегистрирована и вызывается.

Что прогревается: ничего на GPU. Восемнадцать чтений static field.

Что профилируется:

| Mixin | Что измеряет |
|---|---|
| `RenderType.create` | конструирование CPU-объекта, включая eager `<clinit>` |
| `GpuDevice.precompilePipeline` | реальный compile, который vanilla и так делает на apply, до того как warmup попросили |
| `EntityRenderers.createEntityRenderers` | один blob на всю eager-регистрацию |
| `ParticleResources.registerProviders` | то же |
| первый `FluidRenderer.tesselate` | один реальный mesh на воркере, потом флаг навсегда |

Что выключено: сам модуль (default OFF → mixin не стоят). Любой mode кроме `warm` не вызывает adapter. Iris/GeckoLib/ModernFix не имеют `discover`/`warm`.

Side effects production adapter: нет GL, нет fake entity/world, нет подмены render state. Тест `adapterCanRestoreState` восстанавливает `int` у фейкового adapter и production класс не конструирует.

Потоки: `pump` требует render thread и молча ждёт, если его нет. Профайлер на chunk worker не помечен как warmup.

Сильные ссылки: capped 2048, не бесконечные, но не evict'ятся.

Повторы: терминальные `DEFERRED`/`CANCELLED` не ретраятся. Следующий resource reload начинает 18 чтений заново.

Вывод прямым текстом: текущий static RenderType warmup полезной работы не делает. Vanilla эти объекты и так создаёт до хука. Дорогой first-touch профайлер при включённом модуле живёт на `RenderType.create` и `precompilePipeline` всё оставшееся время процесса.

---

## 9. Review of every existing Ultima module

Вердикт REMOVE не ставится: ни один модуль не является мёртвым кодом без поведения. FREEZE значит «не развивать и не включать по умолчанию, пока нет измерения на целевом стеке».

Общее для всех Lithium-family и renderer-family: на S+I+L mixin не применяется, user-visible value равен нулю. Ниже это не повторяется в каждой строке отдельным абзацем.

### entity_section_lookup

- DEFAULT: ON
- ACTIVE WITH: vanilla. Не Lithium. Не имеет значения Sodium/Iris.
- TARGET: CPU, entity queries
- REAL MECHANISM: если бокс пакуется и объём ≤ 1024 и ≤ `sections.size()`, отменяется обход колонки и секции берутся прямым probe в порядке sign-split.
- HOT PATH: каждый entity query в бюджете
- OVERHEAD: OFF ноль. ON — hash get на кандидата вместо tree walk; большой бокс падает в vanilla.
- CORRECTNESS: порядок визитов пересобран руками из long-key sort.
- COMPATIBILITY: тот же метод, что заменяет Lithium.
- CURRENT VALUE: реальный на vanilla, ноль на целевом стеке.
- VERDICT: **FREEZE**. Не второй Lithium.

### block_collision_shape

- DEFAULT: ON
- ACTIVE WITH: vanilla only
- TARGET: CPU, collision iterator
- REAL MECHANISM: конструктор `BlockCollisions` не вызывает `Shapes.create` сразу; `computeNext` строит shape при первом не-кубе.
- HOT PATH: создание collision iterator
- OVERHEAD: OFF ноль. ON убирает shape для air/full-cube запросов.
- CORRECTNESS: поле `entityShape` снаружи выглядит null до `computeNext`.
- COMPATIBILITY: Lithium.
- CURRENT VALUE: ноль на S+I+L.
- VERDICT: **FREEZE**.

### collision_shell_skip

- DEFAULT: ON, зависит от `cursor_step`
- ACTIVE WITH: vanilla only
- TARGET: CPU, collision
- REAL MECHANISM: если курсор умеет interior-only и ни одна покрытая секция не `maybeHas(hasLargeCollisionShape || moving_piston)`, оболочка в один блок не читается.
- HOT PATH: каждый collision query, palette `maybeHas` по выросшему боксу
- OVERHEAD: OFF ноль. ON выигрывает только на чистых кубах; один забор в секции выключает skip на весь запрос.
- CORRECTNESS: shape, торчащий из клетки без `hasLargeCollisionShape`, будет обрезан.
- COMPATIBILITY: Lithium.
- CURRENT VALUE: ноль на S+I+L.
- VERDICT: **FREEZE**.

### supporting_block_shape_skip

- DEFAULT: ON
- ACTIVE WITH: vanilla only
- TARGET: CPU, `findSupportingBlock`
- REAL MECHANISM: если shape `== Shapes.block()`, `VoxelShape.move` не вызывается. Результат — куб в нуле, не в позиции блока. Вызывающий обязан игнорировать shape.
- HOT PATH: поиск опорного блока
- OVERHEAD: маленький, одна identity-проверка
- CORRECTNESS: чужой result-бифунктор, который читает shape, получит куб в origin.
- COMPATIBILITY: Lithium.
- CURRENT VALUE: ноль на S+I+L.
- VERDICT: **FREEZE**. Identity-check `== Shapes.block()` не расширять на «любой полный куб».

### full_cube_move

- DEFAULT: ON
- ACTIVE WITH: vanilla only
- TARGET: allocations, `VoxelShape.move` полного куба
- REAL MECHANISM: `Shapes.block().move(целый offset)` возвращает `OffsetCubeVoxelShape` вместо `ArrayVoxelShape`.
- HOT PATH: каждый такой move, который supporting-block не пропустил
- OVERHEAD: всё ещё три `OffsetCubeCoords` на вызов. Не ноль аллокаций.
- CORRECTNESS: D22, общий mutable discrete shape.
- COMPATIBILITY: Lithium.
- CURRENT VALUE: скромный GC на vanilla, ноль на S+I+L.
- VERDICT: **IMPROVE** только если модуль остаётся для vanilla-сборки: `getCoords` из трёх int без списков; `long` сумма и `super.move` при переполнении; не шарить mutable `BitSetDiscreteVoxelShape`. Для целевого стека **FREEZE**.

### cursor_step

- DEFAULT: ON
- ACTIVE WITH: да, в том числе S+I+L. `incompatibleMods` пуст.
- TARGET: CPU, `Cursor3D.advance`
- REAL MECHANISM: перенос x/y/z вместо деления индекса, если `width*height*depth` влезает в int. Interior mode включается только по просьбе shell skip.
- HOT PATH: каждый шаг `Cursor3D`, не только коллизии
- OVERHEAD: OFF ноль. ON — несколько веток вместо трёх делений.
- CORRECTNESS: D23 на огромных объёмах.
- COMPATIBILITY: Lithium может mixin'ить тот же `Cursor3D`. Конфликта на уровне реестра нет.
- CURRENT VALUE: маленький и на целевом стеке тоже, только если эти вызовы горячие. Это не причина ставить мод.
- VERDICT: **IMPROVE**: interior index в `long`, отказ от interior при переполнении. Не строить вокруг этого продукт.

### server_metrics

- DEFAULT: ON
- ACTIVE WITH: да, S+I+L
- TARGET: instrumentation, server tick / AI / chunk / net
- REAL MECHANISM: volatile flag, ThreadLocal clock, два `nanoTime`, atomic add на интервал. Nested AI на Mob и Brain. `endTick` складывает интервалы в ring.
- HOT PATH: каждый инструментированный участок, каждый моб, каждый encode
- OVERHEAD: default-on налог. Не оптимизация.
- CORRECTNESS: геймплей не меняет. `logLagTick` ухудшает уже плохой тик.
- COMPATIBILITY: Lithium не в списке, и правильно: это не замена логики. Цена всё равно платится рядом с Lithium.
- CURRENT VALUE: отрицательная на целевом стеке, пока включена всегда.
- VERDICT: **IMPROVE**. Default OFF. Удалить always-on `MobMixin`, `BrainMixin` и три Netty wrap. Оставить один интервал тика за флагом `/ultima profile`.

### blockentity_sleeping

- DEFAULT: OFF
- ACTIVE WITH: vanilla only
- TARGET: CPU, hopper `tryMoveItems`
- REAL MECHANISM: после пустого тика `HopperSleepPolicy` может усыпить хоппер, если соседи — точный allowlist и occupancy не позволяет push/pull. Будит `setBlock`, container mutation, `Entity.setPosRaw`, `addEntity`.
- HOT PATH: пока хоть один хоппер спит, каждый item `setPosRaw` берёт lock `WakeRegistry`.
- OVERHEAD: OFF ноль. ON — пропуск transfer плюс глобальный wake.
- CORRECTNESS: HOP-1, double chest. Контроллеры — `IdentityHashMap` всех хопперов, которые тикали.
- COMPATIBILITY: Lithium уже спит хопперы и делает это шире (печи и т.д. здесь как раз refuse).
- CURRENT VALUE: ноль на S+I+L. На vanilla прототип с дырой.
- VERDICT: **FREEZE**. Не чинить в продукт рядом с Lithium. Если когда-нибудь включать на vanilla: либо оба pos половин `CompoundContainer` в `watchPositions`, либо `NeighborState.unknown()` для compound.

### recipe_match_cache

- DEFAULT: OFF
- ACTIVE WITH: да, Lithium не в `incompatibleMods`. Код это обосновывает: у Lithium нет кэша recipe lookup.
- TARGET: CPU, crafting / furnace / brewing lookup
- REAL MECHANISM: первый результат ordered scan кладётся в таблицу до 4096 ключей (item, components, count, геометрия сетки). Нечистый или неизвестный класс рецепта выключает тип целиком. `RecipeManager.apply` чистит crafting/furnace. Brewing не чистится.
- HOT PATH: каждый lookup аллоцирует ключ и идёт через fail-open
- OVERHEAD: OFF ноль. ON выигрывает, только если vanilla scan длиннее аллокации ключа.
- CORRECTNESS: D5, stale brewing. Hinted `getRecipeFor` не пишет в кэш.
- COMPATIBILITY: единственный server cache, который имеет право жить рядом с Lithium.
- CURRENT VALUE: не доказан. Это единственный simulation-кандидат под целевой серверный стек.
- VERDICT: **IMPROVE**. Вызвать `invalidate()` из пересборки `PotionBrewing`. Убрать `FailOpenGuard` с hit path. Не аллоцировать ключ на повтор того же furnace input. Не включать по умолчанию, пока профиль не покажет `getRecipeFor` в топе.

### tag_bitsets

- DEFAULT: OFF
- ACTIVE WITH: vanilla only
- TARGET: CPU, `Holder.Reference.is(TagKey)` для block/item/fluid/entity type/biome
- REAL MECHANISM: immutable `TagKey → long[]` после bind. Drop на `refreshTagsInHolders` до rebind. Неизвестный тег и id вне диапазона — vanilla.
- HOT PATH: каждый covered `is()`
- OVERHEAD: ON сейчас `isEnabled` + fail-open remove + HashMap get + atomic hit, чтобы заменить set contains. Может быть медленнее vanilla.
- CORRECTNESS: drop до rebind закрывает окно старого набора. Это сделано правильно.
- COMPATIBILITY: Lithium кэширует tag-derived flags и занимает те же методы.
- CURRENT VALUE: ноль на S+I+L.
- VERDICT: **RADICAL REDESIGN** или заморозка. Если оставлять для vanilla: один `volatile` snapshot, bit test без `isEnabled` и без fail-open. Если это не быстрее vanilla `is()` на микробенче без Minecraft — удалять модуль следующим проходом, не раньше.

### state_property_cache

- DEFAULT: OFF
- ACTIVE WITH: vanilla only
- TARGET: CPU, path type, redstone flags, pathfinding, fluid amount/height
- REAL MECHANISM: ленивый `int[]` по state id. Modded класс и Land path provider не кэшируются в момент записи.
- HOT PATH: перечисленные методы
- OVERHEAD: fail-open + `isEnabled` + `getId`. Hit path type ещё копирует `PathType.values()`. Miss в walk node читает state дважды.
- CORRECTNESS: D11, гонка записи. Чтение land-provider не повторяется: если провайдер появился после заполнения ячейки, старое значение живёт до tag refresh.
- COMPATIBILITY: Lithium `PathNodeCache` / `BlockStateFlags`.
- CURRENT VALUE: ноль на S+I+L.
- VERDICT: **FREEZE**. Не второй Lithium path cache.

### container_slot_mask

- DEFAULT: OFF
- ACTIVE WITH: vanilla only
- TARGET: CPU, hopper insert/extract и `isEmpty`
- REAL MECHANISM: weak identity map на битсет. Trusted mask пропускает пустые слоты. Полный rescan каждые 32 использования. Не-allowlist помечается untrusted.
- HOT PATH: слот хоппера
- OVERHEAD: глобальный synchronized WeakHashMap + fail-open на слот. Дороже прямого `getItem` на коротких контейнерах.
- CORRECTNESS: окно до verify; D14 на killer-ветке для `swapContents`.
- COMPATIBILITY: Lithium уже ведёт occupancy.
- CURRENT VALUE: ноль на S+I+L. На vanilla в текущем виде сомнителен из-за лока и fail-open.
- VERDICT: **FREEZE**. При любом возврате: поле на контейнере, не WeakHashMap; без fail-open на bit test; вернуть `swapContents` mixin с `main`.

### entity_query_early_out

- DEFAULT: OFF
- ACTIVE WITH: vanilla only
- TARGET: CPU, пустые entity section queries
- REAL MECHANISM: счётчики player/living/item/total. Cancel только если все пересечённые секции пусты для этого kind. `COLLIDABLE` = total, поэтому секция с чем угодно не даёт early-out снаряду.
- HOT PATH: add/remove аллоцируют occupant. Непустой запрос всё равно probe'ит до 1024 ключей и идёт в vanilla.
- OVERHEAD: ON часто дороже, потому что горячий случай — непустая секция.
- CORRECTNESS: неизвестный `EntityAccess` ставит unknown и запрещает early-out. Это безопасный отказ.
- COMPATIBILITY: Lithium, и модуль делит storage с `entity_section_lookup`.
- CURRENT VALUE: ноль на S+I+L.
- VERDICT: **FREEZE**. Если оживлять: четыре счётчика без объекта и probe только когда глобальный счётчик kind равен нулю.

### client_benchmark

- DEFAULT: OFF
- ACTIVE WITH: да, можно включить на S+I+L
- TARGET: instrumentation
- REAL MECHANISM: при property пишет распределения frame time, CPU, GC и killer JSON. Камера — `ReplayTimeline`.
- HOT PATH: `renderFrame` HEAD/RETURN, если mixin применён
- OVERHEAD: без property `beginFrame` пустой, но `timerQuery.get()` вызывается всё равно.
- CORRECTNESS: см. раздел 13. Геймплей вне бенчмарка не меняет, пока property выключен. `snapTo` во время бенчмарка двигает игрока.
- COMPATIBILITY: не конфликтует по реестру. На Sodium terrain counters из `terrain_metrics` отсутствуют, потому что тот модуль выключен.
- CURRENT VALUE: нужен как измеритель, не как оптимизация.
- VERDICT: **IMPROVE**. `timerQuery.get` / `getStatus` только когда sample реально пишется. Сцену killer не оставлять stationary.

### terrain_metrics

- DEFAULT: ON
- ACTIVE WITH: vanilla only
- TARGET: instrumentation, terrain prepare/submit
- REAL MECHANISM: `nanoTime` вокруг prepare и обход draw groups для счётчиков.
- HOT PATH: каждый кадр vanilla-клиента
- OVERHEAD: обход групп даже когда никто не записывает бенчмарк
- CORRECTNESS: не меняет картинку. Retained path закрывает интервал сам, потому что cancel пропускает RETURN metrics.
- COMPATIBILITY: Sodium/Iris/Canvas.
- CURRENT VALUE: ноль на целевом стеке.
- VERDICT: **IMPROVE** для vanilla-сборок: обход групп только пока sampler активен. Для продукта **FREEZE**, default лучше OFF.

### retained_terrain

- DEFAULT: OFF
- ACTIVE WITH: vanilla only
- TARGET: GPU submit, opaque terrain
- REAL MECHANISM: `LevelRenderer.prepareChunkRenders` priority 1100, cancellable. `RetainedTerrainRenderer.prepare` может подменить `ChunkSectionsToRender`. Null — vanilla.
- HOT PATH: только если модуль был включён на старте
- OVERHEAD: второй terrain submitter
- CORRECTNESS: большой собственный lifetime буферов, compaction, query rotation. Это уже мини-рендерер.
- COMPATIBILITY: Sodium делает ту же замену pipeline и выключает модуль.
- CURRENT VALUE: ноль на S+I+L. На vanilla не измерен этим аудитом.
- VERDICT: **FREEZE**. Не второй Sodium.

### render_snapshot

- DEFAULT: OFF
- ACTIVE WITH: vanilla only
- TARGET: allocations, `SectionCopy` block-entity maps
- REAL MECHANISM: intern maps по identity внутри одного extract. Палитры не шарятся.
- HOT PATH: extract региона
- OVERHEAD: одна копия на chunk map вместо копии на секцию
- CORRECTNESS: D21, ThreadLocal leak при исключении. Мутация map посреди extract видна соседним секциям; vanilla копировал каждый раз.
- COMPATIBILITY: Sodium не идёт через этот `SectionCopy`.
- CURRENT VALUE: ноль на S+I+L.
- VERDICT: **FREEZE**. Если трогать: `try/finally` вокруг bind.

### java_mesher

- DEFAULT: OFF
- ACTIVE WITH: vanilla only
- TARGET: CPU, `SectionCompiler.compile`
- REAL MECHANISM: packed scan, thread-local `MutableBlockPos` и renderer, tessellation всё ещё vanilla. При включённом `mesher_fast_path` этот mixin пропускается.
- HOT PATH: каждая секция vanilla mesher
- OVERHEAD: меньше iterator garbage, те же quads
- CORRECTNESS: порядок визитов заявлен как `BlockPos.betweenClosed`. Отдельный oracle в тестах есть.
- COMPATIBILITY: Sodium свой mesher.
- CURRENT VALUE: ноль на S+I+L.
- VERDICT: **MERGE** в fast path и удалить второй `SectionCompiler` mixin, если vanilla mesher вообще остаётся. Иначе **FREEZE**.

### mesher_fast_path

- DEFAULT: OFF
- ACTIVE WITH: vanilla only
- TARGET: CPU, section compile
- REAL MECHANISM: снимок 18³, cached unit-cube quads, иначе vanilla `ModelBlockRenderer` / fluids. Кэш сбрасывается при смене identity model set.
- HOT PATH: каждая секция
- OVERHEAD: D7, измерения съедают выигрыш
- CORRECTNESS: исключение в compile не отменяет vanilla `compile` (mixin не `setReturnValue` при throw), builders в catch отпускаются. Это правильный fail-open, обёрнутый дорогими метриками.
- COMPATIBILITY: Sodium.
- CURRENT VALUE: ноль на S+I+L.
- VERDICT: **FREEZE** как продукт. Если остаётся лабораторным: убрать `threadAllocatedBytes` и `nanoTime` из `compile`, копить long на scratch и публиковать раз на секцию.

### section_task_queue

- DEFAULT: OFF
- ACTIVE WITH: vanilla only
- TARGET: CPU, очередь section tasks
- REAL MECHANISM: один проход compact cancelled, затем полный rescan дистанции для nearest initial vs recompile. Upload spin заменён на park 50 мкс.
- HOT PATH: каждый poll, по-прежнему O(n)
- OVERHEAD: D9 на пустой очереди; park меняет задержку
- CORRECTNESS: политика квоты recompile сохранена, пустой poll — нет
- COMPATIBILITY: Sodium свой scheduler, этот mixin выключен.
- CURRENT VALUE: ноль на S+I+L.
- VERDICT: **FREEZE**. Если чинить лабораторно: пустая очередь возвращает null до `removeTaskByIndex`; `onSpinWait` не трогать.

### rgss_endpoint

- DEFAULT: OFF
- ACTIVE WITH: vanilla only
- TARGET: GPU, terrain fragment shader
- REAL MECHANISM: строковая замена исходника `core/terrain`, чтобы крайние `blendFactor` брали nearest или RGSS.
- HOT PATH: компиляция шейдера, не кадр
- OVERHEAD: хрупкий match
- CORRECTNESS: ранний return меняет производные, если `sampleRGSS` не чистый. Собственный текст модуля говорит отвергнуть его без 3% GPU.
- COMPATIBILITY: Sodium/Iris. Iris свои terrain shaders.
- CURRENT VALUE: ноль. Измерения нет.
- VERDICT: **FREEZE**.

### temporal

- DEFAULT: ON
- ACTIVE WITH: vanilla only
- TARGET: задел под temporal upscaling
- REAL MECHANISM: копирует view/projection, камеру, color/depth views. Backend — `NativePassthroughBackend`. Пиксели не меняются.
- HOT PATH: каждый world frame vanilla
- OVERHEAD: копии матриц и `System.getProperty` ради backend, которого нет
- CORRECTNESS: не меняет картинку. DLSS/FSR2 здесь нет.
- COMPATIBILITY: выключен на Sodium/Iris/Canvas, то есть ровно там, где temporal upscaler имел бы смысл рядом с нормальным рендерером.
- CURRENT VALUE: отрицательный на vanilla, ноль на целевом стеке.
- VERDICT: **FREEZE**. Default OFF. Не развивать, пока нет backend, который меняет работу GPU.

### fsr_upscaling

- DEFAULT: OFF
- ACTIVE WITH: Sodium без Iris — да, если пользователь включил. Iris — нет, mixin не применяется. Canvas — incompatible. S+I+L — нет.
- TARGET: GPU, внутреннее разрешение мира
- REAL MECHANISM: FSR1 EASU+RCAS, мир в меньший target, upscale до HUD. Redirect `mainRenderTarget`.
- HOT PATH: кадр, если mixin применён
- OVERHEAD: redirect и `isEnabled` даже когда план не upscale'ит
- CORRECTNESS: без официального Iris post-final хука включение рядом с Iris либо no-op, либо blit не того буфера. Gate это запрещает. Это правильно.
- COMPATIBILITY: пустые списки capability methods означают «Iris навсегда запрещён», пока кто-то не докажет хук. Sodium-only — отдельный рендерер-таргет поверх Sodium, его надо мерить отдельно.
- CURRENT VALUE: не FPS-алгоритм, а обмен разрешения на скорость. На S+I+L нуля.
- VERDICT: **FREEZE**, пока нет sodium-only прогона с картинкой и frametime. Не обещать совместимость с Iris.

### settings_ui

- DEFAULT: ON
- ACTIVE WITH: да, включая S+I+L
- TARGET: UI
- REAL MECHANISM: кнопка на title screen, если нет Mod Menu.
- HOT PATH: нет
- OVERHEAD: один виджет
- CORRECTNESS: нет
- COMPATIBILITY: нет
- CURRENT VALUE: удобство, не производительность
- VERDICT: **KEEP**.

### iris_shader_frontend_artifact_cache

- DEFAULT: OFF
- ACTIVE WITH: только точный Iris `1.11.4+mc26.2` с двумя SHA-256. Lithium не участвует. Sodium в ключе косвенно через `SodiumParameters`, но модуль — про Iris transform.
- TARGET: CPU, shader reload / pack switch. Не FPS кадра.
- REAL MECHANISM: см. раздел 6. Сейчас механизм не достигает записи.
- HOT PATH: каждый `TransformPatcher.transform`, когда модуль применён
- OVERHEAD: OFF ноль. ON — prelude на каждый transform и ноль hits.
- CORRECTNESS: ICA-1. Порчи shader при текущем jar нет, потому что return value Iris не подменяется.
- COMPATIBILITY: fail-closed fingerprint — правильно и означает, что любой другой билд Iris модуль молча выключит.
- CURRENT VALUE: ноль до починки схемы. После починки — только cold process / reload, и только если диск дешевле transformer.
- VERDICT: **IMPROVE**. Это один из двух модулей, в которые ещё имеет смысл вкладываться. Не включать по умолчанию.

### cross_pipeline_admission_broker

- DEFAULT: OFF, runtime mode default `trace`
- ACTIVE WITH: только точный Sodium `0.9.2+mc26.2`. Iris не обязателен. Lithium не участвует.
- TARGET: stutter / frame time за счёт откладывания мешинга. Не уменьшение работы.
- REAL MECHANISM: см. раздел 7.
- HOT PATH: каждый кадр и каждый deferred admission, когда mixin применён
- OVERHEAD: OFF ноль. `trace` — телеметрия. `control` — отмена цикла.
- CORRECTNESS: задачи не теряются. Видимость ухудшается под давлением. Метрика first-visible врёт на полшага (до upload).
- COMPATIBILITY: `require = 0` на Sodium inject. C2ME только строка в JSON.
- CURRENT VALUE: не доказан и в `control` опасен как источник ложного FPS.
- VERDICT: **RADICAL REDESIGN** рычага (budget, не cancel всего метода) до любого «у нас вырос FPS». Иначе **FREEZE**.

### render_warmup_system

- DEFAULT: OFF, mode default `warm` если включили
- ACTIVE WITH: любой клиент, внешних fingerprint нет. Рядом с Sodium/Iris mixin'ы vanilla RenderType всё равно встанут, если модуль включён: incompatible list пуст.
- TARGET: задуман как stutter первого кадра. Фактически CPU field reads.
- REAL MECHANISM: см. раздел 8.
- HOT PATH: при включении — каждый `renderFrame` плюс каждый `RenderType.create` и `precompilePipeline`
- OVERHEAD: OFF ноль. ON — профайлер навсегда и бесполезный pump.
- CORRECTNESS: render state не портит. Обещает работу, которой нет.
- COMPATIBILITY: не лезет в Iris program link. Поэтому и не может прогреть shader pack.
- CURRENT VALUE: нет.
- VERDICT: **FREEZE**. Не инвестировать в текущий adapter.

---

## 10. Hot-path overhead audit

Module OFF ≈ ноль там, где выключение сделано через `shouldApplyMixin`. Это лучшая часть архитектуры. Исключений «mixin всегда стоит и каждый вызов смотрит флаг» для обычных модулей нет: флаг читается при применении.

Module ON fast path не минимален.

| Частота | Что происходит | Должно ли |
|---|---|---|
| per `Holder.is` при tag_bitsets | `HashSet` + `ArrayList` + `AdapterState` + CHM `remove` + bit test | bit test и volatile snapshot |
| per state probe при state cache | то же + иногда `PathType.values()` | чтение ячейки |
| per recipe lookup | новый ключ + fail-open CHM remove | сравнение с последним ключом |
| per hopper slot при slot mask | synchronized WeakHashMap + fail-open | поле контейнера |
| per entity add/remove при early-out | новый occupant | инкремент счётчика |
| per mob tick при server_metrics (default ON, S+I+L) | 4× `nanoTime` | ничего, пока не идёт profile |
| per packet encode/compress/cipher | 2× `nanoTime` | ничего на always-on |
| per frame vanilla temporal | копии матриц + `getProperty` | не стоять в дефолте |
| per frame vanilla terrain_metrics | обход draw groups | только во время записи |
| per frame, client_benchmark mixin применён, property выключен | `timerQuery.get()` | не звать |
| per frame, broker ON | `nanoTime`, иногда sort, lookup головы очереди, volatile adds | в trace — семплировать, не каждый кадр |
| per transform, Iris cache ON | reflection + отказ схемы | после фикса всё ещё полный SHA-256 |
| per `RenderType.create`, warmup ON | строка + lock | выключить после третьего сэмпла |
| per section, mesher fast path ON | `getThreadAllocatedBytes` | не в compile |
| per collision `Cursor3D` | несколько веток | приемлемо |
| per lagging server tick | `logLagTick` аллоцирует и логирует | счётчик |

`FailOpenGuard` на успешном вызове дороже, чем многие из этих оптимизаций. `maybeThrowForTest` нельзя оставлять в production hit path: это тестовый крюк. `recordSuccess` должен выходить сразу, если `consecutive` пуст, и не делать `remove` на каждом hit.

`KillerModuleCompatibility.state` для обычного модуля нельзя звать из `isEnabled`. Не-killer путь должен быть `static final` sentinel без аллокации. Лучше: на старте посчитать `boolean[] enabledByOrdinal` и больше не интерпретировать политику.

Строковые ключи сами по себе дёшевы, если сравнение случается раз на старте. Они дороги, потому что каждый hot call заново ищет модуль и заново проверяет Iris для FSR.

---

## 11. Compatibility/overlap audit

| ULTIMA MODULE | OTHER MOD | OVERLAP | ПОЧЕМУ ТЕКУЩИЙ ПОДХОД ПОЛЕЗЕН ИЛИ НЕТ | ЧТО УНИКАЛЬНОГО ОСТАЁТСЯ |
|---|---|---|---|---|
| entity_section_lookup, collision_*, full_cube_move, supporting skip | Lithium, Canary, Radium | тот же entity section iteration и collision | Полезен только без Lithium. Auto-disable правильный. Проигрывает: Lithium уже владеет этим и включается у целевого пользователя. | Ничего, что стоило бы развивать. |
| cursor_step | Lithium | частичный, если Lithium не mixin'ит `Cursor3D` | Маленький остаток. Не оправдание мода. | Микро. Не инвестировать. |
| blockentity_sleeping | Lithium | hopper sleep | Lithium шире и без дыры double chest этого кода. | Ничего. |
| tag_bitsets, state_property_cache | Lithium | tag-derived flags, path cache | Двойной HEAD-cancel был бы опасен, поэтому disable правильный. | Ничего рядом с Lithium. |
| container_slot_mask, entity_query_early_out | Lithium | occupancy, entity queries | То же. Текущая реализация ещё и тяжелее vanilla на hit path. | Ничего. |
| recipe_match_cache | Lithium | нет recipe lookup cache | Единственный честный non-overlap на сервере. | Короткий first-match без аллокации на повтор. Не хопперы. |
| retained, java mesher, fast path, snapshot, section queue, RGSS, terrain metrics, temporal | Sodium | весь chunk meshing и terrain submit | Auto-disable правильный. Это второй рендерер для людей без Sodium. Целевой пользователь его не видит. | Ничего внутри SectionCompiler. |
| temporal, FSR | Iris | post pipeline, разрешение, шейдеры | Temporal выключен. FSR выключен, потому что хука нет. Не спорить с Iris за composite. | Не обещать FSR+Iris. |
| fsr_upscaling | Sodium без Iris | Sodium не делает FSR1 | Единственный GPU-эффект, который может жить рядом с Sodium. Это quality/perf tradeoff, не бесплатные FPS. | Отдельный sodium-only прогон. Не приоритет. |
| iris artifact cache | Iris | Iris имеет RAM LRU 400 на процесс, не диск | Пока ключ сломан — бесполезен. После починки уникален только cold process. Hit path, который обходит RAM LRU, вреден. | Диск + свой RAM перед диском. Не второй glsl-transformer. |
| admission broker | Sodium | Sodium уже делит important vs deferred, worker count, upload budget | Отмена всего deferred loop хуже собственного бюджета Sodium. Trace не уникален. Control уникален только как внешний frame-time сигнал, и этот сигнал сейчас неправильный. | Честный сигнал GPU + уменьшение budget, не второй scheduler. |
| render warmup | Sodium, Iris, ModernFix, vanilla | все уже eager-initят RenderType и шейдеры на apply | Текущий adapter проигрывает даже vanilla. | Ничего в текущем списке из 18 геттеров. |
| server_metrics | нет прямого овнера | Spark/observable-подобные таймеры | Не оптимизация. Всегда включённый Mob/Netty wrap проигрывает «не ставить мод». | Профиль по запросу. |
| chunk/noise/packet mixins внутри server_metrics | C2ME, VMP, Noisium, Krypton | только измеряют gen, region IO, compression, cipher | Измерять можно. Оптимизировать эти пути вторым разом не нужно: овнеры уже есть, публичного API давления у C2ME код сам признаёт отсутствующим. | Не писать свой chunk serializer. |

ImmediatelyFast, EntityCulling, MoreCulling, FerriteCore, Alternate Current в коде почти не упоминаются. Отдельных mixin против них нет. Это хорошо: Ultima их не переоптимизирует. Это плохо только как напоминание, что уникальной работы в этих слоях тоже нет.

Главный повтор: Ultima оптимизирует vanilla terrain и vanilla collision, а продукт обещан людям, у которых это уже заменено.

---

## 12. Test quality audit

Тесты, которые что-то доказывают независимо от реализации:

- `TagBitsetEquivalenceTest`, `StatePropertyCacheEquivalenceTest` — сравнение с отдельным oracle, не с тем же методом.
- `RecipeMatchCacheTest` — большой набор поведения кэша, включая инвалидацию crafting. Он не ловит отсутствие production-вызова brewing `invalidate`, потому что тест зовёт `invalidate` сам.
- `HopperSleepEquivalenceTest` — таблица решений. Она не строит двойной сундук и второй pos. Политика `sleepSafe` для compound проходит, дыра watch list не видна.
- `ArtifactCacheStoreTest` corruption, truncation, schema, parallel same key — реальные байты store. Это хороший тест хранилища и плохой тест ключа.
- `ReplayTimelineTest` — реально проверяет, что tick unit не зависит от числа кадров. Он не проверяет, что сцена `killer_route` эту траекторию применяет к камере.
- `AdmissionControllerTest` — проверяет контроллер как чистую функцию. Тест maximum defer проходит на minimum interval. Это тавтология относительно мёртвой ветки.

Тесты, которые почти ничего не доказывают:

- `IrisTransformKeyEncoderTest` гоняется на stubs, у которых `Parameters` содержит `textureOverrides`. Зелёный тест не связан с jar `1.11.4`.
- `ArtifactCacheStoreTest.keyMaterialChangesMiss` хеширует литералы `"source=A;settings=1;..."`. Encoder не вызывается. Слова settings/iris/schema в строке — не поля ключа.
- `readOnlyDirectoryDoesNotEscape` пишет и читает и почти ничего не утверждает.
- `BudgetedWarmupPlanTest` не конструирует `VanillaRenderTypeWarmupAdapter`. Restore state — локальный `int`.
- `Wave2FailOpenTest` и куски `SourceReadingInventory` проверяют, что исходник содержит подстроку `FailOpenGuard`. Это контракт стиля, не поведения.
- `MergedModuleContractTest` фиксирует число модулей и дефолты. Полезно как реестр, бесполезно как доказательство скорости.
- `MixinBytecodeChecks` на `main` был ближе к контракту байткода. На `4f6ca5c` файла нет.

Чего нет и что нужно без Minecraft:

- Differential: реальный `Parameters.class` из pinned jar → `encode != null`; удаление одного поля из dump → `encode == null`.
- Property: для store, любой байтовый payload, roundtrip; null stage ≠ empty stage ≠ omitted stage; trailing byte отвергнут.
- Fuzz: мутация файла кэша, длина, schema, дубликат stage name.
- Fault injection: уже есть arm test fault. Нет injection на диск (короткая запись, crash между temp и move) кроме существующих corrupt тестов.
- Concurrency: два payload на один ключ; eviction против записи другого ключа; два процесса на один каталог.
- Lifecycle: brewing reload без ручного `invalidate` должен miss. Double chest: мутация непосмотренной половины должна будить. Пустой `poll` очереди не вызывает index −1.
- Bytecode contract: вернуть проверку дескрипторов Sodium/Iris mixin targets против pinned jar (`require = 0` это не заменяет).
- Контроллер: sequence «один кадр 30 мс, потом 100 кадров ровно target» должен выйти из pressure. Сейчас выйдет только если p95 упадёт ниже 92%.
- Бенч: тест, что `cameraModeForScene("killer_route")` не `stationary`, либо что harness передаёт `chunk_flight`. Сейчас такой тест зафиксировал бы баг.

PASS этих тестов не означает, что кэш, брокер или warmup корректны в игре.

---

## 13. Benchmark harness audit

Что сделано правильно:

- `ReplayTimeline` в tick mode даёт один `routeUnit` на игровой тик независимо от FPS. Граница sample: unit в `[warmup, warmup+sample)`. Кадр, на котором unit перешёл границу, в распределение не пишется.
- Оба соседа пары получают один и тот же `REPLAY_MODE=tick`, одну сцену, один скрипт клиента.
- Порядок пар чередуется on/off, чтобы не путать прогрев машины с эффектом.
- `broker-control` и `broker-static` включают модуль с обеих сторон и меняют только mode (`trace` против `control`/`static`). Инструментация брокера симметрична. Это правильное сравнение.
- Summarizer для `changesScheduling` ругается, если p99 visibility proxy, hole proxy или throughput уехали больше чем на 10% / 5%. Поля JSON совпадают с именами в `KillerModuleDiagnostics`. Это защита от голого FPS.
- `artifact-cold` уносит только каталог Ultima, не shader cache драйвера. Есть отказ на небезопасном пути.

Где harness врёт или молчит:

1. **Камера killer-сцены не едет.** `killer_route` → `stationary` → `applyCamera` return. Детерминированная траектория есть в классе и не подключена к этому профилю. Streaming, ради которого существует брокер, в окне 1200 тиков почти не происходит: 200 тиков warmup уже построили спавн.
2. **Нет внутритиковой интерполяции.** Позиция квантуется тиком. Для воспроизводимости это нормально, когда камера вообще двигается. Partial tick не входит в unit. Оба соседа одинаковы, ложного расхождения FPS из-за интерполяции нет. Ложное ощущение «мы пролетели маршрут» — есть, из-за пункта 1.
3. **Число кадров в sample не фиксировано.** Фиксировано число тиков. Медленная сторона пишет меньше кадров и другое wall-clock окно. Сравнение распределений frame time по одному и тому же tick-пути корректно только если путь один и мир к тику T одинаков. Брокер как раз делает мир к тику T разным (меньше готовых секций).
4. **GPU колонка пустая** без `getStatus()`. Сравнивать GPU нельзя.
5. **Visibility proxy — `setInfo` до upload.** Guardrail смотрит не на пиксели. Throughput делится на время с конструирования `BrokerMetrics`, не на sample window. Падение throughput только в sample тонет в warmup.
6. **`warmup` профиль не выключает модуль на OFF.** Обе стороны с модулем, разница mode `profile`/`warm`. Начальное состояние отличается на 18 field read до sample. Это честный тест «adapter ничего не делает» и нечестный тест «module ON против OFF».
7. **`all-control` несимметричен.** OFF — все killer выключены, ON — все три плюс control. Frametime смешивает телеметрию, диск и deferral.
8. **`artifact-warm` не ставит reload внутрь sample.** После прайма stationary-кадр кэш не читает. Разница FPS не про кэш. Холодный прогон пишет диск во время загрузки, то есть может сделать ON медленнее вне sample и никак внутри.
9. **`terrain_metrics` на Sodium выключен.** На целевом стеке в JSON нет независимых visible section counts. Дыры видны только через broker proxy, и только если брокер включён с обеих сторон.
10. **Успех по среднему frametime при росте `deferrals` и плоском proxy в пределах 10%** всё ещё можно прочитать как победу. Summarizer не требует, чтобы deferral не был объяснением.

Исправление harness до прогона: сцена с `chunk_flight` и tick rates; shader reload на известном тике; fail если cache `hits == 0`; fail если control снизил frametime и одновременно поднял возраст до upload; GPU через `getStatus`; sample-window counters, не lifetime.

---

## 14. Architecture improvements

Менять только то, что режет overhead, ошибки или сопровождение.

1. **Launch latch вместо интерпретатора.** После `UltimaConfig.load` массив `boolean` по ординалу модуля. Hot path читает одно поле. `isEnabled(String)` оставить для UI. Удаляет HashSet, ArrayList, AdapterState и FSR-probe с каждого lookup.

2. **Один fail-open, и не на hit path.** `maybeThrowForTest` за флагом, который в проде константа false (или отсутствует в production source set). `recordSuccess` — no-op, если карта пуста. Circuit breaker не должен писать в CHM на успехе.

3. **Перестать растить vanilla renderer.** Retained, java mesher, fast path, RGSS, temporal, snapshot, section queue — отдельные mixin на один и тот же vanilla pipeline. Каждый новый кадр работы здесь не виден целевому пользователю. Архитектурно это уже второй клиент. Заморозить одним решением, не «ещё одним слоем абстракции».

4. **Fingerprint-адаптер оставить fail-closed, но проверять дескрипторы, не только SHA-256 класса.** Иначе `require = 0` применяет половину mixin. Это меньше кода в рантайме и меньше ложных прогонов.

5. **Не вводить общий metrics framework.** Сейчас проблема в том, что метрики стоят на hot path всегда (`server_metrics`, mesher, warmup profiler, broker trace). Общая библиотека таймеров это не лечит. Лечит «нет вызова, пока не идёт sample».

6. **Не объединять все кэши.** У них разные lifetime (recipe reload, tag bind, shader pack, section mesh). Общий cache manager добавит синхронизацию. Общее, что стоит разделить: политика инвалидации должна быть тестом на событие, а не комментарием. Brewing это уже показал.

Не делать: единый `Module` base class с виртуальными `onTick`, шину событий, dependency injection, ещё один слой поверх Sodium renderer.

---

## 15. Up to 5 new optimization opportunities

Новых сильных модулей, которые стали видны из кода и которые Sodium, Iris и Lithium ещё не забрали, мало. Ниже не пять идей «давайте ещё». Три направления, которые код уже почти трогает, и явный отказ от остальных.

### 1. Cold-process Iris transform cache, после ICA-1, с RAM-слоем перед диском

- EXACT BOTTLENECK: CPU `EnumASTTransformer` на apply shader pack. Не driver link. Не FPS кадра.
- EXACT INTEGRATION POINT: private `TransformPatcher.transform` / `transformCompute`, как сейчас. RAM map впереди `ArtifactCacheStore`. Диск только если процесса Iris LRU уже нет.
- WHY OTHERS DO NOT OWN IT: Iris держит 400 записей в куче процесса и не пишет transformed source. Sodium не трансформирует Iris GLSL. Lithium не при чём.
- EXPECTED USER-VISIBLE EFFECT: короче смена пака и холодный заход после рестарта. Не выше FPS в уже загруженном мире.
- CORRECTNESS RISK: неполный ключ. Закрывается verify mode на первом apply и тестом на реальный class file.
- HOW TO PROVE OR KILL: без Minecraft можно убить идею, если pinned jar после фикса схемы всё ещё даёт null key, или если сериализация не roundtrip'ится. В игре: reload внутри sample. Убить, если disk+hash не быстрее transformer на этом паке хотя бы на сотни миллисекунд. Не смотреть на stationary frametime.

### 2. Уменьшение Sodium upload/build budget по честному GPU sample, не cancel метода

- EXACT BOTTLENECK: кадры, где deferred meshing и upload попадают в тот же кадр, что и GPU-тяжёлый shader pass. Код брокера уже стоит в `submitDeferredSectionTasks(ChunkJobCollector, UploadResourceBudget)`.
- EXACT INTEGRATION POINT: аргумент budget этого метода, не HEAD-cancel.
- WHY OTHERS DO NOT OWN IT: бюджет Sodium не смотрит на `TimerQuery` этого кадра. Iris не scheduling'ует chunk upload. Lithium не клиентский mesher.
- EXPECTED USER-VISIBLE EFFECT: ниже хвост frame time при полёте, без дыр. Средний FPS может не вырасти.
- CORRECTNESS RISK: CPB-1/2 уже показывают, как легко превратить это в «кадр короче, чанк позже».
- HOW TO PROVE OR KILL: убить сразу, если нельзя прочитать GPU timestamp без F3. Убить в игре, если p99 времени до upload растёт больше, чем падает p99 frame time. Пока рычаг — cancel всего цикла, направление не доказывать, а переписывать.

### 3. Recipe first-match без обвязки, только если это видно в профиле

- EXACT BOTTLENECK: повторный ordered scan `RecipeManager.getRecipeFor` / brewing на одинаковом входе. Код кэша уже есть и Lithium его не закрывает.
- EXACT INTEGRATION POINT: существующие mixin `RecipeManager` и `PotionBrewing`, после D5.
- WHY OTHERS DO NOT OWN IT: в реестре это прямо отражено пустым `incompatibleMods`, и обход кода Lithium-family это подтверждает: овнера нет.
- EXPECTED USER-VISIBLE EFFECT: MSPT на серверах с автокрафтом, не FPS клиента.
- CORRECTNESS RISK: stale brewing, неполный ключ компонентов, thrash на 4096.
- HOW TO PROVE OR KILL: без Minecraft — тест «rebuild brewing без ручного invalidate → miss». Убить модуль, если после удаления аллокации ключа микробенч lookup не быстрее scan на реалистичном числе рецептов. Не включать по умолчанию вслепую.

### Чего нет

Четвёртого и пятого направления, которые стоило бы открывать, в этом коде не видно. Worldgen, сеть, свет, chunk IO, entity culling и Sodium mesher уже имеют других овнеров, а локальные попытки Ultima в эти слои либо только измеряют (`server_metrics`), либо выключаются. Писать туда новый модуль сейчас — повторение чужой работы.

---

## 16. What NOT to work on

- Не писать второй terrain renderer: retained, RGSS, temporal passthrough, render snapshot, section task queue, java mesher, mesher fast path. На целевом стеке их нет. На vanilla они не обгоняют Sodium.
- Не расширять collision / entity-section / full-cube / supporting-block. Lithium это выключает.
- Не доводить hopper sleep, tag bitsets, state property cache, slot masks, entity early-out как конкурентов Lithium. У hopper ещё и double chest.
- Не тратить следующий цикл на `VanillaRenderTypeWarmupAdapter`. Восемнадцать геттеров не станут warmup от настройки бюджета.
- Не включать FSR рядом с Iris и не искать обход пустого списка хуков. Обход — это как раз баг совместимости.
- Не оптимизировать region IO, noise, compression, cipher. Модуль их только таймит. C2ME, Noisium, Krypton, VMP уже в этом слое. Код брокера сам пишет, что у C2ME нет стабильного API давления.
- Не добавлять общий cache framework, общий таймерный фреймворк и новые mixin «на 0.1%».
- Не запускать killer A/B в текущем виде и не читать его FPS как результат.
- Не мёржить `4f6ca5c` в `main` как есть: вместе с модулями уедут сломанная Iris-схема, удаление `swapContents` и удаление `MixinBytecodeChecks`.

---

## 17. Prioritized development roadmap

### P0 — исправить немедленно

Игрового P0 нет. Ниже — блокеры, без которых любой прогон будет принят за измерение.

**P0-1. Починить схему Iris-ключа и запретить зелёный тест на stubs.**

- WHAT: убрать `textureOverrides` из `fieldSchema` (фактические поля pinned jar: `patch`, `textureMap`, `type`, `name`). Тест открывает `iris-fabric-1.11.4+mc26.2.jar` и требует non-null key для `SodiumParameters`, `VanillaParameters`, `ComputeParameters`, `DHParameters`.
- WHY: иначе модуль мёртв на единственном jar, который он согласен включать, а текущий тест это скрывает.
- FILES: `IrisTransformKeyEncoder.java`, `IrisTransformKeyEncoderTest.java`, новый тест на jar, не stubs.
- RISK: если в transformer есть зависимость от поля, которого мы не увидели, verify mode на первом реальном apply обязан поймать mismatch и latch off. До verify не подменять return value в проде, либо оставить verify обязательным на первую версию.
- EXPECTED BENEFIT: кэш впервые может записать artifact. Не FPS.
- DEPENDENCIES: нет.
- HOW TO VERIFY WITHOUT MINECRAFT: `javap` pinned class, `encode != null`, roundtrip store, null stage ≠ `""`, corrupted file miss.
- RUNTIME LATER: один shader reload с verify mode. Сравнить строки Iris и кэша. Потом выключить verify и мерить wall time reload, не FPS.

**P0-2. Не считать текущий broker-control и warmup прогоны данными.**

- WHAT: в harness для `broker-control` помечать результат невалидным, пока не закрыты CPB-1 и CPB-3. Для `warmup` не сравнивать frametime. Это правка скрипта и summarizer, не «ускорение».
- WHY: иначе следующий отчёт зафиксирует ложный выигрыш или ложный ноль.
- FILES: `scripts/bench-killer-modules-ab.sh`, `scripts/summarize-client-bench.py`, `ClientFrameBenchmark.cameraModeForScene`.
- RISK: низкий, это отказ публиковать число.
- EXPECTED BENEFIT: не сожрать неделю на интерпретацию шума.
- DEPENDENCIES: нет.
- HOW TO VERIFY WITHOUT MINECRAFT: тест, что сцена killer не `stationary`; summarizer бросает ошибку, если `hits == 0` при включённом cache; summarizer не выдаёт warmup delta как pass.
- RUNTIME LATER: только после P1 контроллера.

### P1 — до первого runtime test

**P1-1. Переписать условие release и убрать мёртвый maximum defer.**

- WHAT: выход из `pressureHigh`, когда p95 ниже порога входа. GPU `<= 0` не считается low. Либо удалить maximum, либо сделать так, чтобы minimum его не вытеснял. Один тест: 30 мс, затем кадры ровно в target, ожидание `pressureHigh == false` за конечное число кадров.
- WHY: CPB-1 и CPB-4.
- FILES: `AdmissionController.java`, `AdmissionControllerTest.java`.
- RISK: gate станет реже. Это и нужно.
- EXPECTED BENEFIT: control перестаёт быть перманентным дросселем.
- DEPENDENCIES: нет.
- HOW TO VERIFY WITHOUT MINECRAFT: чистая последовательность `onFrame`/`permit`.
- RUNTIME LATER: смотреть `deferrals` и возраст до upload, не средний FPS.

**P1-2. Перестать отменять весь deferred submit.**

- WHAT: не `ci.cancel()` на весь метод. Временный безопасный шаг до бюджетного рычага: в `control` не менять scheduling вообще (фактически оставить trace), пока нет уменьшения budget.
- WHY: CPB-2. Ложный FPS опаснее отсутствующего FPS.
- FILES: `RenderSectionManagerMixin.java`, `CrossPipelineBroker.java`.
- RISK: модуль временно ничего не ускоряет. Это честно.
- EXPECTED BENEFIT: нельзя случайно «выиграть» бенчмарк.
- DEPENDENCIES: P1-1, если рычаг всё же останется.
- HOW TO VERIFY WITHOUT MINECRAFT: тест, что default mode и `control` до появления budget API оба возвращают permit true. Bytecode-тест, что inject больше не cancellable, либо cancellable только за явным флагом, которого нет в дефолте.
- RUNTIME LATER: полёт с дырами в террейне как критерий провала.

**P1-3. Читать GPU timestamp одинаково в брокере и в бенчмарке.**

- WHAT: `getStatus()` перед чтением, последний валидный sample, иначе −1. Не трактовать −1 как «GPU свободен».
- WHY: CPB-3 и пустая GPU-колонка harness.
- FILES: оба `MinecraftMixin` (`cross_pipeline_admission_broker`, `client_benchmark`).
- RISK: лишний timestamp query раз в кадр, только когда модуль бенчмарка или брокера включён.
- EXPECTED BENEFIT: впервые появляется шанс увидеть GPU-bound.
- DEPENDENCIES: нет.
- HOW TO VERIFY WITHOUT MINECRAFT: нельзя доказать значение таймера без GL. Можно тестом на источник убедиться, что вызов `getStatus` есть, а `<= 0` не входит в release как успех. Это необходимо, но не достаточно.
- RUNTIME LATER: кадр с заведомо тяжёлым шейдером должен дать ненулевой gpu sample без открытого F3.

**P1-4. Починить killer scene и окно измерения кэша.**

- WHAT: `chunk_flight` для broker-профиля. Для artifact-профиля — reload на фиксированном тике внутри sample. Fail при `hits == 0`. Warmup-профиль не публикует FPS.
- WHY: BENCH-1.
- FILES: `ClientFrameBenchmark.java`, `bench-killer-modules-ab.sh`, `summarize-client-bench.py`.
- RISK: полёт нагружает машину сильнее stationary. Это цель.
- EXPECTED BENEFIT: прогон начинает отвечать на заданный вопрос.
- DEPENDENCIES: P0-1 для cache fail-on-zero-hits, иначе fail будет всегда и это тоже полезно.
- HOW TO VERIFY WITHOUT MINECRAFT: unit-тест дефолтов сцены и разбор аргументов скрипта.
- RUNTIME LATER: сам прогон. Не раньше P0/P1 кода.

**P1-5. Launch latch для `isEnabled` и дешёвый success path fail-open.**

- WHAT: `boolean[]` на старте. `recordSuccess` не трогает map, если она пуста. `maybeThrowForTest` не читать на каждом hit в проде.
- WHY: иначе включённые для теста tag/state/recipe/slot модули измеряют обвязку.
- FILES: `UltimaConfig.java`, `KillerModuleCompatibility.java`, `FailOpenGuard.java`, `TagBitsetRuntime.java`, `StatePropertyRuntime.java`.
- RISK: латч обязан повторять текущие правила incompatible/FSR/fingerprint. Рестарт по-прежнему нужен для mixin. Это уже так.
- EXPECTED BENEFIT: module ON перестаёт аллоцировать на каждый probe. Не новый FPS сам по себе.
- DEPENDENCIES: нет.
- HOW TO VERIFY WITHOUT MINECRAFT: тест, что 100k `isEnabled` не создают объектов (allocation через счётчик или явный latch API). Тест fail-open по-прежнему trip'ает после трёх ошибок. Тест success не вызывает `remove`, если failures не было.
- RUNTIME LATER: не требуется для корректности латча.

**P1-6. Не потерять при мёрже `swapContents` и bytecode checks.**

- WHAT: вернуть `ChestBlockEntityMixin` с `main`. Вернуть контракт дескрипторов mixin, хотя бы для killer targets и slot-mask.
- WHY: D14, REG-1.
- FILES: mixin с `main`, новый или восстановленный тест.
- RISK: низкий.
- EXPECTED BENEFIT: маска сундука не врёт после swap. Адаптер не применяется наполовину молча.
- DEPENDENCIES: мёрж killer-ветки.
- HOW TO VERIFY WITHOUT MINECRAFT: тест исходника/байткода, что `swapContents` инвалидирует обе стороны; javap pinned Sodium/Iris на список методов из mixin.
- RUNTIME LATER: двойной сундук / swapContents только если slot mask вообще включат.

### P2 — после первого стабильного runtime

Делать только если P0/P1 прогон показал, что направление живо.

- Brewing `invalidate` на реальном rebuild (`PotionBrewingMixin`, `BrewingFirstMatchCache`). Без этого recipe cache нельзя включать.
- Убрать аллокацию ключа на повтор furnace input.
- `server_metrics` default OFF, снять Mob/Brain/Netty always-on, убрать `logLagTick` в счётчик.
- `PathType.values()` заменить на статическую копию, если state cache ещё включают на vanilla.
- Пустой poll section queue: вернуть null. Иначе модуль не включать даже в лаборатории.
- `full_cube_move`: убрать три списка и общий mutable bitset, если vanilla-сборка ещё поддерживается.
- `cursor_step`: long index для interior.
- RAM-слой Iris кэша перед диском (ICA-2), только если reload-тест показал, что диск медленнее RAM LRU и быстрее transformer.
- Eviction под stripe lock (ICA-3).
- Профайлер warmup: после третьего сэмпла не аллоцировать ключ. Имеет смысл только если модуль не заморожен.
- `try/finally` в `RenderRegionCacheMixin`, если snapshot не заморожен.

### P3 — future research

- Budget-shaping Sodium вместо trace-only, если GPU timestamp в игре ненулевой и полёт показывает хвост именно на upload. Убить при первом росте time-to-visible.
- Sodium-only FSR как отдельный продуктный вопрос, не как FPS-модуль рядом с Iris.
- Recipe cache в дефолте dedicated server, только по профилю.
- Ничего из renderer family, пока целевой стек включает Sodium.

---

## TOP 10 NEXT CHANGES

Порядок — порядок инженерной работы, не список идей.

1. Исправить `fieldSchema` Iris под реальные поля `Parameters` в `1.11.4+mc26.2` и добавить тест, который читает этот jar, а не stub с `textureOverrides`. Сейчас кэш физически не может сработать, и все остальные работы по нему бессмысленны.

2. Запретить summarizer'у и скрипту killer A/B публиковать успех cache при `hits == 0`, успех warmup по frametime и успех broker-control, пока gate отменяет весь deferred submit. Иначе следующий прогон закрепит ложный вывод.

3. Перевести killer-сцену брокера на tick-based `chunk_flight`, а сцену кэша — на shader reload внутри sample window. Стационарный спавн не нагружает ни streaming, ни transform.

4. Починить release hysteresis: выход по порогу входа, `gpu <= 0` не считается здоровым GPU, удалить мёртвую ветку maximum defer и покрыть это тестом последовательности кадров. Иначе `control` навсегда остаётся дросселем после одного hitch.

5. Снять `ci.cancel()` со всего `submitDeferredSectionTasks`, пока нет уменьшения существующего budget. Модуль должен уметь только наблюдать, пока рычаг опасен. Ложный FPS хуже отсутствующего.

6. В обоих mixin кадра читать GPU через `getStatus()`, и не кормить ноль в контроллер как «GPU low». Без этого и брокер, и бенчмарк слепы к той половине кадра, ради которой их писали.

7. Сделать launch-time `boolean` для модулей и убрать `ConcurrentHashMap.remove` с успешного пути `FailOpenGuard`. Иначе любой честный замер second-wave модуля измеряет обвязку Ultima.

8. Выключить `server_metrics` по умолчанию и снять always-on обёртки с `Mob`, `Brain` и Netty. Это единственная default-on работа на стеке Sodium + Iris + Lithium, и она не ускоряет.

9. При переносе killer-ветки вернуть `ChestBlockEntityMixin.swapContents` и проверку дескрипторов mixin против pinned jar. Ветка сейчас удаляет уже найденный fix и bytecode-контракт.

10. Добавить RAM-кэш transformed source перед диском и не обходить Iris LRU на повторном apply в том же процессе. Делать это только после пункта 1 и только если reload-тест покажет, что диск дешевле transformer. До этого диск с `force(true)` на каждую программу легко медленнее промаха.

## TOP 3 MOST IMPORTANT MODULES TO INVEST IN

1. `iris_shader_frontend_artifact_cache`. Единственный механизм в репозитории, который делает работу, не занятую Sodium и Lithium, и лишь частично занятую Iris (RAM на процесс, не диск). Сейчас он сломан об схему полей. Инвестиция — починка ключа, verify, измерение reload. Не инвестиция — FPS-график.

2. `cross_pipeline_admission_broker`, но только после того, как он перестанет отменять работу и начнёт читать настоящий GPU time. Идея внешнего сигнала давления Sodium не принадлежит. Текущий рычаг принадлежит помойке. Если после честного сигнала хвост кадра не связан с upload, модуль закрыть, а не настраивать коэффициенты.

3. `recipe_match_cache`. Единственный server-модуль, которому реестр правильно разрешает жить рядом с Lithium. Инвестиция маленькая: инвалидация brewing и ключ без аллокации. Включать в дефолт только по профилю. Это не клиентский FPS.

## TOP 3 MODULES / DIRECTIONS TO STOP SPENDING TIME ON

1. `render_warmup_system` в текущем виде. Static `RenderType` уже создан vanilla к моменту хука. Дальнейшая настройка бюджета, диагностических строк Iris/GeckoLib/ModernFix и first-use профайлера не приближает прогрев.

2. Vanilla client renderer: `retained_terrain`, `java_mesher`, `mesher_fast_path`, `rgss_endpoint`, `temporal`, `render_snapshot`, `section_task_queue`, `terrain_metrics` как продукт. На Sodium + Iris они не загружаются. Это второй рендерер для конфигурации, которую проект сам объявил нецелевой.

3. Lithium-shaped simulation: `blockentity_sleeping`, `tag_bitsets`, `state_property_cache`, `container_slot_mask`, `entity_query_early_out` и семейство collision (`entity_section_lookup`, `block_collision_shape`, `collision_shell_skip`, `supporting_block_shape_skip`, `full_cube_move`). Auto-disable уже признаёт поражение. Новый код здесь не появится у целевого пользователя. `cursor_step` оставить как есть и не развивать.
