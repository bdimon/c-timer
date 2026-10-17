# Полное руководство по сборке, отладке и синхронизации Android-версии
## «Interval Timer: C & Web Suite + Android NDK»

---

### 1. Архитектурная модель (Zero-GC + Real-Time Engine)

Проект построен по **нативной отказоустойчивой модели**:

```text
┌─────────────────────────────────────────────────────────────┐
│                 Jetpack Compose UI (Material 3)             │
│   • Круговой индикатор фаз (Подготовка, Работа, Отдых)      │
│   • Менеджер программ тренировок (Табата, Бокс, HIIT, Core) │
│   • Модуль синхронизации профилей Web ↔ Android            │
└──────────────────────────────┬──────────────────────────────┘
                               │ StateFlow (UI State)
                               ▼
┌─────────────────────────────────────────────────────────────┐
│            TimerForegroundService (Android Service)         │
│  • Partial WakeLock (работа при заблокированном экране)     │
│  • Monotonic Clock Drift Compensation (защита от Doze Mode) │
│  • Ongoing Notification с Live Countdown & кнопками плеера  │
│  • AndroidAudioEngine (синтез тонов 880/440 Гц через PCM)   │
└──────────────────────────────┬──────────────────────────────┘
                               │ JNI Callbacks / Commands
                               ▼
┌─────────────────────────────────────────────────────────────┐
│             IntervalTimerNative (JNI Bridge C <-> JVM)      │
│                 (c_src/android/interval_timer_jni.c)        │
└──────────────────────────────┬──────────────────────────────┘
                               │ C99 API Calls
                               ▼
┌─────────────────────────────────────────────────────────────┐
│                 C-Ядро (c_src/interval_timer.c)             │
│            • Стейт-машина без дрифта времени                │
│            • Статическая память, 0 утечек, 0 GC             │
└─────────────────────────────────────────────────────────────┘
```

---

### 2. Структура файлов проекта

* `c_src/interval_timer.h` & `c_src/interval_timer.c` — высокоточное C99-ядро расчета фаз.
* `c_src/android/CMakeLists.txt` — конфигурация сборки нативной библиотеки `libinterval_timer_native.so`.
* `c_src/android/interval_timer_jni.c` — JNI-мост с мьютексами и прямым вызовом колбэков в JVM.
* `android/app/src/main/kotlin/com/intervaltimer/MainActivity.kt` — главный экран (Таймер + Каталог программ + Диалог синхронизации).
* `android/app/src/main/kotlin/com/intervaltimer/core/IntervalTimerNative.kt` — загрузка `.so` и JNI-декларации.
* `android/app/src/main/kotlin/com/intervaltimer/core/WorkoutPlanStorage.kt` — локальное хранилище профилей (SharedPreferences + JSON).
* `android/app/src/main/kotlin/com/intervaltimer/service/TimerForegroundService.kt` — фоновый сервис с WakeLock и компенсацией дрифта.
* `android/app/src/main/kotlin/com/intervaltimer/audio/AndroidAudioEngine.kt` — генератор синусоидальных звуковых тонов без mp3.
* `android/app/build.gradle.kts` & `android/gradle/libs.versions.toml` — зависимости и связь с CMake.

---

### 3. Открытие и сборка в Android Studio

1. **Системные требования:**
   - Android Studio (Iguana / Jellyfish / Koala / Ladybug или новее).
   - JDK 17 (устанавливается вместе со Studio или `sudo apt install openjdk-17-jdk`).
   - Android SDK API 35 + NDK (версия 25.x / 26.x / 27.x) + CMake 3.22.1+.
     *(Устанавливаются в Android Studio: `Settings` → `Languages & Frameworks` → `Android SDK` → `SDK Tools` → отметьте `NDK (Side by side)` и `CMake`).*

2. **Пошаговый запуск:**
   - Запустите Android Studio → нажмите **Open** → укажите папку `android/` проекта.
   - Дождитесь завершения `Gradle Sync`. Gradle автоматически вызовет CMake и создаст таски компиляции C-кода для ABI `arm64-v8a`, `armeabi-v7a`, `x86_64`.
   - Нажмите **Run** (`Shift + F10`) для запуска на смартфоне или эмуляторе.

3. **Проверка компиляции .so библиотеки в логах:**
   В панели `Build` → `Build Output` вы увидите строку:
   ```text
   [1/2] Building C object CMakeFiles/interval_timer_native.dir/interval_timer_jni.c.o
   [2/2] Linking C shared library .../libinterval_timer_native.so
   ```

---

### 4. Тестирование работы под нагрузкой и в спящем режиме

#### А. Тест выключенного экрана (Screen Off / Sleep Test):
1. Запустите таймер (например, «Табата (Классика)»).
2. Заблокируйте телефон аппаратной кнопкой Power.
3. **Что должно происходить:**
   - `TimerForegroundService` удерживает `PARTIAL_WAKE_LOCK`.
   - Звуковые сигналы (880 Гц / 440 Гц / метроном 3-2-1) звучат точно в положенные секунды.
   - На экране блокировки отображается Ongoing Notification с живым таймером, прогресс-баром и кнопками «Пауза/Старт» и «След. интервал».
   - Нажатие на уведомление моментально разворачивает приложение в активную фазу.

#### Б. Тест глубокого сна Doze Mode через ADB:
Для проверки поведения таймера при агрессивном энергосбережении Android:
```bash
# Перевести устройство в режим ожидания со снятием с зарядки
adb shell dumpsys battery unplug

# Принудительно включить глубокий Doze Mode
adb shell dumpsys deviceidle step deep
adb shell dumpsys deviceidle force-idle

# Проверить статус службы таймера
adb shell dumpsys activity services com.intervaltimer.service.TimerForegroundService
```
*Благодаря механизму **Monotonic Clock Drift Compensation** в `TimerForegroundService` на базе `SystemClock.elapsedRealtime()`, даже если ОС задержит выполнение потока на несколько секунд, таймер мгновенно нагонит пропущенные такты C-ядра без потери реального времени.*

#### В. Исключение из оптимизации батареи:
В верхней части экрана приложения отображается янтарный баннер с кнопкой «Разрешить» для добавления в белый список энергосбережения (`ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`).

---

### 5. Синхронизация данных (Web ↔ Android)

Веб-версия и Android-приложение используют **единый стандартизированный формат профилей**:

#### Способ 1: Мгновенный перенос по коду синхронизации (Compact Sync URI)
1. В веб-приложении откройте конструктор или пресеты и нажмите **«Синхронизация с Android»** (или иконку смартфона).
2. Выберите вкладку **«Код синхронизации»** и нажмите **«Скопировать код для Android»**.
   *(Код имеет вид: `itimer://plan?d=eyJuIjoi0KLQsNCx0LDRgtCwI...`)*
3. В Android-приложении в правом верхнем углу нажмите кнопку **«Web Sync»**, вставьте строку и нажмите **«Импортировать»**.
4. C-ядро Android мгновенно применит тренировочный план и переключится на экран таймера.

#### Способ 2: Сканирование QR-кода
1. В веб-версии откройте модальное окно синхронизации на вкладке **«QR-код»**.
2. Отсканируйте камерой смартфона или встроенным сканером — он декодирует `itimer://` ссылку прямо в приложение.

#### Способ 3: Экспорт / Импорт файлов `.itimer.json`
* Файлы `.itimer.json` можно передавать через Telegram, Google Drive, почту или сохранять локально.
* В Android-приложении в разделе **«Планы»** для любого профиля доступна кнопка **«Экспорт»** (копирует JSON-пакет для переноса обратно в веб-версию).

---

### 6. Возможные проблемы сборки и их решение

| Ошибка | Причина | Решение |
|---|---|---|
| `CMake was not found in PATH` | Не установлен CMake в Android SDK | В Android Studio: `SDK Manager` → `SDK Tools` → включите `CMake 3.22.1` |
| `NDK not configured` | Не указан путь к NDK | В Android Studio: `SDK Tools` → установите `NDK (Side by side)` |
| `UnsatisfiedLinkError: dlopen failed: library "libinterval_timer_native.so" not found` | Сборка C-библиотеки не выполнена для текущего ABI | Выполните `Build` → `Refresh Linked C++ Projects` и `Clean Project` |
| `Notification permission denied` | Android 13+ блокирует уведомления | В настройках приложения на телефоне разрешите «Уведомления» |
