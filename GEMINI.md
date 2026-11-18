# GEMINI.md - Project AI Guidelines & Architecture Map

## 1. Project Identity
- **Name**: `MB-CAL` (`com.example.calculator`)
- **Description**: Engineering CAS & ЕГЭ math calculator for Android.
- **Tech Stack**: Kotlin + Android XML Views + Custom 2D Canvas (`CasioDisplayView`).

## 2. Language Policy
- **User-Facing Strings**: All UI strings, button labels, hints, error messages, dialogs, and step-by-step math explanations MUST be strictly in Russian.
- **Code & Internal Reasoning**: Keep code identifiers (classes, methods, variables), code comments, and LLM internal reasoning concise and in standard English.

## 3. Project Architecture Map
> Do **NOT** run `find_files(query=*)` when file locations are already documented here.

### Core Canvas & Navigation
- `app/src/main/java/CasioDisplayView.kt` — Custom 2D Natural Display canvas (*Note: Located directly under `java/`, NOT `java/com/example/calculator/`, but uses `package com.example.calculator`*).
- `app/src/main/java/com/example/calculator/MainActivity.kt` — Mode Hub navigation, main CAS keypad, live preview, theme switching, and mode callbacks.
- `app/src/main/java/com/example/calculator/CasEngine.kt` — Symbolic CAS, rational `Fraction`, AST `Node`, polynomial/cubic solver, Norm 1 formatter.
- `app/src/main/java/com/example/calculator/ThemeManager.kt` — Centralized Dark (`#0A0E17` screen, `#1E293B` panel, `#F8FAFC` text) & Light (`#F1F5F9` screen, `#FFFFFF` panel, `#0F172A` text) color palettes.

### Mode Engines, Managers & Views (`app/src/main/java/com/example/calculator/`)
- **Trig Solver**: `TrigSolverEngine.kt` & `TrigSolverManager.kt` + `UnitCircleView.kt` + `app/src/main/res/layout/layout_trig_solver.xml`
- **3D Section**: `Section3DEngine.kt` & `Section3DManager.kt` + `Section3DView.kt` + `app/src/main/res/layout/layout_section_3d.xml`
- **Unit Converter**: `UnitConverterEngine.kt` & `UnitConverterManager.kt` + `app/src/main/res/layout/layout_unit_converter.xml`
- **Equation System Solver**: `EquationSystemSolver.kt` & `SystemSolverManager.kt` + `app/src/main/res/layout/layout_system_solver.xml`
- **Graph Mode**: `GraphModeManager.kt` & `GraphView.kt` & `DesmosPlotCompiler.kt` + `app/src/main/res/layout/layout_graph_mode.xml`
- **Matrix Mode**: `MatrixModeManager.kt` + `app/src/main/res/layout/layout_matrix_mode.xml`
- **History & Constants**: `HistoryBottomSheet.kt`, `HistoryRepository.kt`, `ConstantsBottomSheet.kt`

### Unit Tests (`app/src/test/java/com/example/calculator/`)
- `ExampleUnitTest.kt`
- `TrigSolverTest.kt`
- `Section3DEngineTest.kt`
- `UnitConverterTest.kt`
- `DesmosPlotCompilerTest.kt`

## 4. Token & Context Efficiency Rules
- **Targeted Code Reading**: For large files (`CasEngine.kt`, `TrigSolverEngine.kt`, `CasioDisplayView.kt`, `activity_main.xml`), **ALWAYS** use targeted `grep` for method/ID anchors and read specific line ranges (`startLine`..`endLine`) rather than reading the entire file.
- **Modular Architecture**: Keep new calculation modes modular by creating dedicated `*Engine.kt`, `*Manager.kt`, and `layout_*.xml` files.

## 5. UI & Theme Conventions
- **Dual Theme Support**: Every mode must support both Dark and Light themes via `ThemeManager` constants and implement `refreshTheme()`.
- **Keypad Styling**: Custom keypad rows must use `46dp` height, `4dp` corner radius, `2dp` margins, and `android:baselineAligned="false"`.

## 6. Fast Verification & Windows Shell Rules
- **PowerShell Commands**: On Windows PowerShell, chain multiple shell commands using `;` (never `&&`).
- **No GUI Overhead**: NEVER invoke `take_screenshot()`, `uiautomator dump`, or slow `adb shell input tap` sequences unless explicitly requested by the user.
- **Automated Unit Testing**: Verify all logic changes using `.\gradlew testDebugUnitTest`.
- **Compilation Verification**: Verify build/compilation using `.\gradlew assembleDebug`.
- **ADB Deployment Check**:
  - Check `adb devices` before running ADB commands.
  - If `emulator-5554` is online, install via `adb -s emulator-5554 install -r app/build/outputs/apk/debug/app-debug.apk`, launch `com.example.calculator/.MainActivity`, and verify zero runtime errors via `adb -s emulator-5554 logcat -d AndroidRuntime:E *:S`.
  - If `emulator-5554` is not running, skip ADB installation cleanly without hanging or waiting.
