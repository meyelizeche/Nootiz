# AGENTS.md — Notiz Android Client (`com.fumoctl.notiz`)

Este documento es la guía de referencia técnica, arquitectónica y de mantenimiento para agentes de IA y desarrolladores que trabajen en **Notiz**, la aplicación nativa de Android para el backend colaborativo de notas en Markdown [`notiz-back-api`](https://github.com/fumoctl/notiz-back-api).

---

## 1. Visión General del Proyecto

- **Nombre del paquete**: `com.fumoctl.notiz`
- **Lenguaje y UI**: Kotlin + Jetpack Compose (Material 3)
- **SDK**: `minSdk = 26` (Android 8.0), `targetSdk = 36`, `compileSdk = 36.1`
- **Backend objetivo**: `notiz-back-api` (Rust / Axum / SQLite, puerto por defecto `3000`).
- **Propósito**: Cliente móvil nativo que permite configurar dinámicamente la URL base del servidor API, autenticarse con JWT, organizar notas en carpetas, etiquetarlas, usar listas de tareas interactivas (`- [ ]` / `- [x]`), adjuntar imágenes binarias (BLOB), compartir notas con permisos granulares (`read` / `edit`) y programar recordatorios en el calendario del dispositivo.

---

## 2. Estructura del Proyecto

```text
app/src/main/
├── AndroidManifest.xml
├── java/com/fumoctl/notiz/
│   ├── MainActivity.kt                  # Punto de entrada y máquina de estados de navegación
│   ├── data/
│   │   ├── local/
│   │   │   └── SessionPreferences.kt    # Persistencia local (URL base, JWT, usuario, recordatorios)
│   │   ├── model/
│   │   │   └── Models.kt                # DTOs y modelos de dominio (User, Note, Folder, Share, etc.)
│   │   └── remote/
│   │       └── NotizApiClient.kt        # Cliente HTTP/JSON y Multipart sobre HttpURLConnection
│   └── ui/
│       ├── screens/
│       │   ├── AuthScreen.kt            # Login / Registro + selector dinámico de URL de API
│       │   ├── DashboardScreen.kt       # Lista de notas, buscador, filtros por carpeta/etiqueta/compartidas
│       │   └── NoteEditorScreen.kt      # Editor Markdown, checklist interactivo, imágenes BLOB, compartir y calendario
│       └── theme/
│           ├── Color.kt                 # Paleta de colores oficial de Notiz
│           ├── Theme.kt                 # Configuración MaterialTheme y StatusBar
│           └── Type.kt                  # Jerarquía tipográfica
└── res/
    ├── drawable/                        # Vector drawables del icono adaptativo
    └── mipmap-anydpi-v26/               # Configuración del launcher icon
```

---

## 3. Decisiones de Diseño y Arquitectura

### 3.1. Identidad Visual y Sistema de Diseño
La interfaz implementa fielmente la guía visual de las pantallas de referencia:
- **Paleta cromática (`ui/theme/Color.kt`)**:
  - `TextPrimaryNavy` (`#122440`): Azul marino profundo para títulos, textos principales y toasts de confirmación.
  - `RoyalBlue` (`#36519E`): Azul real para acciones primarias, botones principales, chips activos y FAB.
  - `SkyBlueCard` (`#86BCD8`): Azul cielo para destacar la tarjeta principal en el Dashboard.
  - `IceBlueCard` / `IceBlueSurface` (`#E7F0F7` / `#EEF4F9`): Superficies secundarias, tarjetas de notas, contenedores de formularios y barras de búsqueda.
  - `LimeAccent` (`#DBF462`): Verde lima de alto contraste para acciones clave (ej. botón **"Guardar"** en el editor, insignias de confirmación y recordatorios activos).
  - `OffWhiteBackground` (`#F9FAFC`): Fondo general limpio de la aplicación.

### 3.2. Cliente HTTP Nativo sin Dependencias Pesadas (`NotizApiClient.kt`)
- Se utiliza `HttpURLConnection` + `org.json` ejecutado íntegramente sobre `Dispatchers.IO`.
- **Por qué**: Evita inflar el APK y previene conflictos de versiones con procesadores de anotaciones (KAPT/KSP) o motores de serialización externos, permitiendo además cambiar la URL base (`baseUrl`) en caliente sin reconstruir instancias de Retrofit/OkHttp.
- Soporta construcción manual de `multipart/form-data` para subir imágenes binarias (`POST /api/notes/{id}/attachments`) y descarga de bytes crudos (`GET /api/notes/{id}/attachments/{att_id}`) para renderizarlos con `BitmapFactory`.

### 3.3. Configuración Dinámica de Servidor en Login (`AuthScreen.kt`)
- El usuario puede definir cualquier URL base (ej. `http://10.0.2.2:3000`, `http://localhost:3000`, o la IP LAN `http://192.168.x.x:3000`).
- Incluye chips de selección rápida (**Emulador**, **Localhost**, **LAN**) y un botón **"Probar"** que consulta `GET /api/health`.
- `SessionPreferences.normalizeUrl()` limpia espacios y barras finales (`/`) automáticamente antes de construir las rutas `/api/...`.
- En `AndroidManifest.xml` está habilitado `android:usesCleartextTraffic="true"` para permitir conexiones HTTP locales durante el desarrollo y uso en red local.

### 3.4. Matriz de Permisos y Colaboración (`AGENTS.md` del Backend)
La UI respeta estrictamente las reglas de negocio del backend `notiz-back-api`:
- **Propietario (`isOwner = true`)**: Puede editar título/cuerpo/etiquetas, mover la nota de carpeta (`folder_id`), subir/eliminar adjuntos, eliminar la nota y gestionar usuarios compartidos (`GET/POST/DELETE /api/notes/{id}/shares`).
- **Colaborador con rol `edit` (`isOwner = false, canEdit = true`)**: Puede editar título, cuerpo, etiquetas y adjuntos, pero **no** puede cambiar la carpeta de la nota ni eliminarla (el botón de papelera actúa como *"Salir de nota compartida"* invocando `DELETE /api/notes/{id}/shares/{my_user_id}`).
- **Colaborador con rol `read` (`isOwner = false, canEdit = false`)**: Vista de solo lectura (`readOnly = true` en campos de texto y controles de edición deshabilitados).

---

## 4. Funcionamiento por Pantalla

### 4.1. `MainActivity.kt` y Flujo de Sesión
- Maneja 3 destinos mediante la interfaz sellada `AppDestination`:
  1. `AppDestination.Auth`
  2. `AppDestination.Dashboard`
  3. `AppDestination.Editor(note, initialFolderId)`
- Al iniciar, carga las preferencias en `Dispatchers.IO` (`sessionPrefs.ensureLoaded()`). Si existe un JWT guardado, abre el Dashboard y valida la sesión en segundo plano con `GET /api/auth/me`. Si el token expiró (`401`), limpia la sesión y regresa a `Auth`.

### 4.2. `AuthScreen.kt` (Login y Registro)
- Alterna entre **Iniciar sesión** (`POST /api/auth/login`) y **Crear cuenta** (`POST /api/auth/register`).
- Almacena el token JWT y el objeto `User` en `SessionPreferences`.

### 4.3. `DashboardScreen.kt` (Espacio de Notas y Carpetas)
- Carga en paralelo `GET /api/folders` y `GET /api/notes` aplicando filtros opcionales:
  - `folder_id`: Filtra por carpeta seleccionada.
  - `shared_only=true`: Chip **"Compartidas"** para ver notas que otros usuarios compartieron contigo.
  - `tag`: Filtra por etiqueta (`#tag`).
- Permite crear nuevas carpetas (`POST /api/folders`), renombrarlas (`PUT /api/folders/{id}`) o eliminarlas conservando sus notas (`DELETE /api/folders/{id}`).
- Incluye buscador instantáneo local por título, contenido o etiquetas.

### 4.4. `NoteEditorScreen.kt` (Editor Rico y Herramientas)
- **Barra inferior de formato**:
  - `H`: Inserta encabezados Markdown (`## `).
  - `T`: Inserta bloque de subtexto/énfasis.
  - `Checklist (✓)`: Abre diálogo para agregar una prioridad interactiva (`- [ ] Tarea`).
  - `IMG`: Abre el selector de archivos del sistema (`ActivityResultContracts.GetContent()`) y sube la imagen como BLOB al servidor (`POST /api/notes/{id}/attachments` con límite de 10 MB).
  - `Guardar`: Crea (`POST /api/notes`) o actualiza (`PUT /api/notes/{id}`) la nota y muestra el banner de confirmación en azul marino (`NavyToast`).
- **Checklist interactivo ("Tres prioridades / Tareas")**:
  - Detecta automáticamente cualquier línea del cuerpo con formato `- [ ] ` o `- [x] ` y genera tarjetas interactivas con `Checkbox` que sincronizan su estado directamente con el Markdown de la nota.
- **Hoja para Compartir Nota (`ShareNoteBottomSheet`)**:
  - Busca usuarios por nombre o correo (`GET /api/users/search?q=...`), permite elegir permisos (**Puede editar** / **Solo lectura**) y comparte la nota (`POST /api/notes/{id}/shares`).
- **Hoja de Recordatorio y Calendario (`CalendarReminderBottomSheet`)**:
  - Calendario mensual interactivo y selector de hora. Guarda el recordatorio localmente y ofrece integración directa con el calendario del sistema mediante `Intent(Intent.ACTION_INSERT)` sobre `CalendarContract.Events.CONTENT_URI`.

---

## 5. Notas Críticas de Mantenimiento y Rendimiento (¡Leer antes de modificar!)

### 5.1. Gestión de `WindowInsets`, Teclado (`IME`) y `targetSdk = 36`
> **IMPORTANTE**: En Android 16 (`targetSdk = 36`) con Compose `1.7.0` (`compose-bom:2024.09.00`), **NO** se debe combinar `enableEdgeToEdge()`, `android:windowSoftInputMode="adjustResize"` y `.imePadding()` en el contenedor raíz.
- **Problema histórico**: Esa combinación provoca un bucle de cancelación en `InsetsController` (`SHOW_SOFT_INPUT_BY_INSETS_API` → `onCancelled at PHASE_CLIENT_APPLY_ANIMATION`) y hace que la ventana pierda el foco (`Dropping event (no window focus): KeyEvent`), congelando la entrada de texto.
- **Regla de mantenimiento**:
  1. Mantener `AndroidManifest.xml` **sin** `windowSoftInputMode="adjustResize"`.
  2. No llamar a `enableEdgeToEdge()` en `MainActivity`.
  3. Usar `Scaffold(modifier = Modifier.fillMaxSize())` estándar en las pantallas y aplicar únicamente el `innerPadding` provisto por el `Scaffold`.

### 5.2. Cero Bloqueos en el Hilo Principal (`Main Thread`) al Iniciar
- **No usar `SharedPreferences` síncrono en constructores o en el primer fotograma de composición**: `SessionPreferences` mantiene variables `@Volatile` en memoria y delega la lectura inicial (`ensureLoaded()`) y las escrituras (`scope.launch(Dispatchers.IO)`) fuera del hilo UI.
- **Evitar animaciones infinitas en el arranque**: No colocar `CircularProgressIndicator` indeterminados ni disparar peticiones de red automáticas en el fotograma 0 de `AuthScreen`, ya que compiten con la compilación JIT inicial en emuladores y provocan `Skipped frames! / Davey!`.

### 5.3. Restricciones de Dependencias y Gradle (`app/build.gradle.kts`)
- **Iconos de Material**: El proyecto utiliza únicamente `material-icons-core` (incluido con `androidx.compose.material3:material3`). **No** agregar iconos de `androidx.compose.material:material-icons-extended` a menos que se sincronice explícitamente el proyecto Gradle en Android Studio, utilizando siempre `Icons.Default.*` e `Icons.AutoMirrored.Filled.*`.
- **Versiones de AndroidX compatibles con `compileSdk = 36.1`**:
  - Mantener `androidx.core:core-ktx:1.15.0`, `androidx.lifecycle:lifecycle-runtime-ktx:2.8.7` y `androidx.activity:activity-compose:1.10.1`. No actualizar a versiones que exijan `compileSdk = 37`.

---

## 6. Compilación y Generación de APK

### Desde Android Studio / IntelliJ IDEA
- Usar la acción **Build Project** o ejecutar la configuración **Run 'app'**.

### Desde Línea de Comandos (PowerShell en Windows)
Dado que el proyecto utiliza la distribución de Gradle `9.3.1` cacheada en el perfil del usuario y el JDK 21 de Microsoft, puedes generar el APK instalable ejecutando:

```powershell
$env:JAVA_HOME = "C:\Program Files\Microsoft\jdk-21.0.12.101-hotspot"
$gradleBat = (Get-ChildItem -Path "$env:USERPROFILE\.gradle\wrapper\dists\gradle-9.3.1-bin" -Recurse -Filter "gradle.bat" | Select-Object -First 1).FullName
& $gradleBat :app:assembleDebug --console=plain
```

El archivo APK resultante se genera en:
`app/build/outputs/apk/debug/app-debug.apk`
