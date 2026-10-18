# Design improvements

Reviewed on 2026-10-18 against the current `src/main/java` tree. This is a design review of SpacePixels as an application. It does not change behaviour, and it does not replace [HIGH_LEVEL_DESIGN.md](HIGH_LEVEL_DESIGN.md). That file describes the system as it is. This file says where the design is clear, where it has drifted, and what to straighten next.

[REFACTOR.md](REFACTOR.md) (2026-09-24) is about splitting large classes. Several of those splits already happened. The line counts there are out of date: `MainApplicationPanel` is well past the 967 lines recorded then, because sky catalogue, plate-solution correction and the workflow strip were added in place. Size is a symptom. The findings below are about responsibilities.

## What is already in good shape

The split with JTransient is the right one. SpacePixels imports, prepares, shows and reports. JTransient detects. The public API (`SpacePixelsPipelineApi`, a request, a result, a progress listener) is a clean boundary, and the batch command uses it.

Three newer GUI pieces are the pattern to copy:

- **Settings.** `SettingsNavigator`, `SettingRow` and `DetectionConfigurationPanel` build pages out of rows. A row knows its value, its changed marker and its reset. Dependent rows hide themselves. Expert sections are marked and filtered. The panel does not invent a new layout for each setting.
- **Viewers.** Blink, Preview and Manual Transient Inspection share `FrameView`, `StretchControls`, `ViewerSupport` and `SequenceViewerFrame`. Keys live in one place. Stretch controls detach when a window closes, so a disposed viewer does not keep the session alive.
- **Report sections.** `DetectionReportGenerator` asks a section writer for each part of the HTML. A new section is a class, not another thousand lines in the generator.

The long jobs (import, convert, stretch, solve, blink, detect, auto-tune) already follow one shape: a `Runnable` in `tasks` posts a started event, progress, then a finished event. Subscribers on the Swing side hop to the event-dispatch thread with `EventQueue.invokeLater` before touching components. That rule is followed in `MainApplicationPanel` and `DetectionConfigurationPanel`.

## How the GUI is wired today

```
ApplicationWindow
  owns the Guava EventBus, the four tabs, and the ImageProcessing for the open folder
  MainApplicationPanel     workflow buttons, file table, status, progress dialog
  ConfigurationPanel       solver and site
  StretchPanel             display stretch
  DetectionConfigurationPanel   settings and the Overview
  BlinkFrame               separate window, same bus
```

A button listener builds a task and starts `new Thread(task)`. The task calls `ImageProcessing` or a service and posts events. Guava delivers those events on the **worker thread**, not on the Swing thread. Every subscriber that touches a component must move to the event-dispatch thread itself. There is no bus wrapper that does this.

Keyboard shortcuts (`Ctrl+D`, `Ctrl+B`, `Ctrl+P`, `Ctrl+T`) are `AbstractAction`s that call `button.doClick()`. The buttons themselves are anonymous `addActionListener` blocks. Viewers use `InputMap` / `ActionMap` directly. The main window does not have a shared action object that both a button and a menu item would use.

Progress is one modal `ProcessingProgressDialog`. `EngineProgressUpdateEvent` is the only progress message, shared by import, detection, tuning, stretch and conversion. Started/finished pairs then open the dialog, lock the buttons, and unlock them again. Failure is a `JOptionPane` inside the finished subscriber.

Two newer jobs do not use this shape. Fetch Sky Catalogue and the plate-solution check are a raw thread inside `MainApplicationPanel`, with progress written straight to a label. Batch stretch is posted from an inline thread in the panel even though `BatchStretchTask` exists and does the same posts.

## Findings

### 1. One event means several different jobs

`DetectionStartedEvent` and `DetectionFinishedEvent` are posted by the full detection, the iterative detection, Manual Transient Inspection and the star-mask stack. The finished event carries `isQuickDetection`, a nullable report file, a nullable image and unused star/streak counts. Inspection and the mask stack pass `isQuickDetection = true` and a null report so the main panel will not offer to open a report. The main panel still treats the event as "object detection completed": it closes the progress dialog, unlocks the workflow and sets that status text.

`EngineProgressUpdateEvent` has the same problem in a milder form. Any job can move the same dialog. Two jobs at once (a detection and a sky fetch, or a stretch and an import) share one dialog and one status label with no owner.

This is the main reason the bus is hard to extend. A new background job either reuses a finished event whose fields do not describe it, or skips the bus, as the sky catalogue did.

### 2. The main panel is the controller, the view and the session

`MainApplicationPanel` builds the workflow strip, owns the file table, starts the threads, subscribes to every lifecycle event, locks and unlocks controls, confirms memory, writes a plate solution into a FITS header, and runs the sky-catalogue job. `ApplicationWindow` is a thin shell around it, but the panel reaches back through the window for the bus, `ImageProcessing`, the stretch panel and the settings panel.

The enable/disable rules (`disableControlsProcessing`, `disableControlsBlinking`, `disableControlsSolving`, and the matching enable methods) are the real workflow state. They are not a state value. Each new button has to be added to several of them, and a shortcut checks `uiLocked` separately. A missed call leaves a button clickable during a run.

### 3. Actions are not first-class

Swing's intended pattern is an `Action`: one object holds the name, the enabled flag and the work, and a button, a menu item and a key binding all use it. Here the work lives in the listener, the enabled flag lives on the button, and the key binding clicks the button if it is enabled and `uiLocked` is false. The table popup copies the same three checks again (`blinkItem.setEnabled(blinkButton.isEnabled())`).

`ViewerSupport` already does this the other way, with named actions on the root pane. The main workflow is the place that would benefit, because every command there has a button, a shortcut or a menu item, and an enabled rule.

### 4. Notifications have no single path

A finished job reports itself in three ways, chosen inside the subscriber: the status label, the progress dialog, and a `JOptionPane`. Wording and buttons differ per job ("Import the newly created mono directory now?" lives in the convert subscriber; the WCS write question lives in the solve subscriber). There is no small type for "the job ended, here is the message and the optional next step".

The status label is also used for progress that is not a dialog (blink frame counts, sky-catalogue stages). That is fine. The inconsistency is that some failures are dialogs, some are only the label, and the sky-catalogue failure is a field (`skyFetchProblem`) shown in the workflow status.

### 5. Session state is reached from wherever the caller is

After import, the open folder is `ApplicationWindow.imagePreProcessing`. The last track overlay and the last variable-star overlay are fields on `ImageProcessing`. The sky catalogue and the plate correction are `SkyCatalogueModel.shared()`, a process-wide holder. Detection settings are the live `DetectionConfig` in the settings panel, also saved as JSON. Display stretch and a few window choices are Java preferences. The GUI reads each of these from a different place.

`SkyCatalogueModel.shared()` is the one that will hurt first. A second window, a test, or a headless call in the same JVM sees the catalogue of whichever folder was fetched last. The API path does not use it. The viewers do.

### 6. Threading is ad hoc

Every job is `new Thread(...).start()`, with no shared pool and no common cancellation. Blink uses an `AtomicBoolean`. The sky job uses `Thread.interrupt`. Detection uses the progress dialog's cancel flag. A second click is prevented by disabling buttons, not by the job knowing it should not start.

`ImageProcessing` and `IterativeDetectionTask` call `SwingUtilities.invokeAndWait` from a worker to show a dialog. Guava then delivers events on that same worker. A subscriber that also waits for the worker, or a modal dialog opened while the worker holds a lock, can stall the session. The progress dialog's `setVisible(true)` is itself modal; the comment in `onDetectionStarted` depends on that. It works, and it is brittle.

### 7. The API and the GUI do not share a workflow

`DefaultSpacePixelsPipelineApi` prepares input, optionally tunes, runs the pipeline and writes the report, and reports progress through `SpacePixelsProgressListener`. The GUI does the same steps through tasks and `ImageProcessing`, with the bus as its listener. The two paths already share `DetectionInputPreparation`, `StandardDetectionPipelineService` and `AutoTunerRunner`. They do not share the decision of what a run is.

That is acceptable. The GUI has questions the API must not ask (write this header? open this report? blink anyway?). The drift to avoid is a third copy of those steps inside a panel, which is what the inline batch-stretch thread is.

### 8. `util` is still a leftover drawer

Import, WCS, stretch, blink data, auto-tune storage and the report all sit in `eu.startales.spacepixels.util`. The services extracted in 2026 (`PlateSolveService`, `DetectionInputPreparation`, the two pipeline services) are the right grain. `ImageProcessing` is still the object the GUI asks for all of them, and it still opens Swing dialogs itself (conversion, exposure groups, decompression). A processing type that shows a dialog cannot be called from the API without a window.

The reporting package is large and mostly cohesive. Leave it. New report behaviour belongs in a section writer, not in the GUI.

### 9. Settings are ahead of the workflow

The settings panel is the clearest design in the GUI: pages, rows, one apply path, hidden settings left in the config. The workflow strip above the file table is still assembled control by control. Both are Swing. Only one of them will stay understandable as buttons are added.

`DetectionConfigurationPanel` still subscribes to import and auto-tune itself, and it still starts auto-tune. That is reasonable today. It should not also grow sky-catalogue or detection-run logic. Those belong with the workflow.

## Proposed shape

Keep Swing, Guava and the current packages. Do not introduce a UI framework. Change the seams the next feature would otherwise cut through.

### A small job type

One type, posted on the bus, instead of a new started/finished pair per feature:

- name of the job (import, solve, detect, tune, blink, stretch, convert, sky catalogue)
- phase: started, progress, finished
- percent and message
- success and error text
- an optional result (report file, imported files, solve result)

The worker posts it. One subscriber on the main panel owns the progress dialog, the status label and the button lock. A second subscriber handles only the result it cares about (the settings overview handles tune results; the file table handles solve results). Inspection and the star-mask stack stop pretending to be a detection.

Progress from a worker still arrives on the worker thread. The subscriber that touches Swing stays responsible for `invokeLater`, until a single decorator on the bus does that hop in one place. Do that decorator when the job type is introduced, so it is not a second change later.

### Actions for the workflow commands

Each command in the strip (import, convert, stretch, solve, blink, preview, inspect, tune, detect) becomes an `Action` with a name and `setEnabled`. The button, the table menu and the shortcut all use that action. `uiLocked` becomes "every workflow action is disabled except Stop", set in one method. `ViewerSupport` already shows the key-binding half of this.

### Jobs that are not tasks yet

Move the sky-catalogue thread and the inline batch-stretch thread into `tasks`, posting the same job type. `BatchStretchTask` already exists; the panel should start it instead of copying it. The sky job keeps its cancel-by-interrupt behaviour inside the task.

### One session object

A session value, owned by `ApplicationWindow` and replaced on import:

- the open folder and the `FitsFileInformation` list
- the `ImageProcessing` (or the services under it)
- the sky catalogue and the plate correction, instead of `SkyCatalogueModel.shared()`
- the last overlays the viewers draw

Viewers take the session they are opened with. A test builds a session without a window. The API keeps its own request and does not see this object.

### `ImageProcessing` stops showing windows

Dialogs for "this folder mixes exposures" and "convert these frames" are GUI questions. `ImageProcessing` should return the fact (mixed exposures, signed pixels, the largest group). The panel decides whether to ask. The API already answers those by refusing or by the request's preparation mode. After this, a service method is safe to call from both.

### What not to do

- Do not replace the EventBus with property-change listeners, a message queue, or a reactive library. The bus is fine. The messages are fuzzy.
- Do not split `CreativeTributeRenderer` or the section writers because they are long. They have one job.
- Do not rewrite `DetectionConfigurationPanel` into a generated form. Extract persistence only when a change needs it; [REFACTOR.md](REFACTOR.md) already says that.
- Do not make the GUI call `SpacePixelsPipelineApi` for a button click. The API is the headless contract. Share the services underneath, not the entry point.

## Suggested order

Each step should leave the screens behaving as they do now.

1. **Document the EDT rule next to the bus** in `HIGH_LEVEL_DESIGN.md` when that file is next touched: events are delivered on the posting thread, and Swing updates happen in the subscriber. No code.
2. **One job event, and a single progress subscriber.** Start with detection, inspection and the star-mask stack, which already share `DetectionFinishedEvent`. Leave blink's richer events until the dialog and the status label have one owner.
3. **Route batch stretch through `BatchStretchTask`.** Delete the inline copy. Small, and it removes a second way to post the same events.
4. **Workflow actions and one lock method.** Buttons, popup and shortcuts share an `Action`. No change to what the commands do.
5. **Sky catalogue as a task** posting the same job event. This is the feature that most recently bypassed the pattern.
6. **A session object, and dialogs out of `ImageProcessing`.** Do this when the next import or viewer change needs it. Doing it alone touches every panel.

Steps 2 and 4 are the ones that make the design visible in the GUI code. The others stop the next feature from adding a third style.
