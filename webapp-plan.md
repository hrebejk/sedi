# Experiment: embedded web UI (`sedi -w`)

## Context
Today SeDi only renders to the terminal: `Reporter` and its printers produce strings with ANSI codes, and line numbers are
encoded with sentinel chars that `LineNumbers.render` parses back. We want a browser view started with `-w`. It should:
- show the same report;
- keep watching the directory and refresh by itself;
- be easier to steer: a file tree on the left, a control bar for level, grouping, javadoc and context, and expandable skipped lines.

Decisions agreed with the user:
- The server is the **JDK's built-in `com.sun.net.httpserver.HttpServer`**, so no new Maven dependency.
- **REST JSON** carries the data. **Server-Sent Events** (`/api/events`) push "re-analyzed" notifications.
- The UI uses **Preact + htm**, copied into the jar's resources. No build step and no CDN.
- The report becomes **structured lines** (number, marker, fold, styled spans). Both the terminal and the browser render
  them. The browser does not convert ANSI.

## 1. Structured report lines (`cz.hrebejk.sedi.report`)

To keep the change small, the printers keep building strings. What changes is the styling: a colored `Theme` no longer
writes ANSI codes. It writes **role markup**: private-use sentinels `<role ordinal>` to open a role and ``
to close it. That matches the codebase's existing sentinels (`LineNumbers.MARK`, `UNNUMBERED`). One parser then turns
the final strings into typed lines. Rewriting every printer to build typed spans directly can come later.

- **`Span(String text, List<Theme.Role> roles)`** and **`ReportLine(Kind kind, long number, char marker, int fold,
  List<Span> spans)`** are new public records.
  - `Kind` is one of `CODE` (numbered), `OUTSIDE` (headings), `PLAIN`, `FOLD` (the "… (N lines)" note) and `SEPARATOR`.
  - `fold` is the id of the folded group the line belongs to, and `0` for none.
  - `Role` becomes public so the web can name the CSS classes.
- **`Theme`** (`report/Theme.java`):
  - `style()`, `strike()`, `added()` and the rest emit markup when the theme is colored. `PLAIN` is unchanged: no roles,
    with `~~` and `__` markers, so the existing PLAIN-based tests keep passing.
  - `code()` skips the markup sentinels instead of ANSI escapes, and keeps its rule of not re-coloring text inside
    STRIKE, ERROR and COMMENT.
  - New `String ansi(List<Span>)` renders spans to escape codes. The innermost role sets fg and bg, and bold, underline
    and strike are OR'ed. This replaces `Style.apply` and the nested-escape patching.
  - New `String css()` writes `.r-<role>{…}` rules from the same styles, so `--theme` colors the browser too.
- **`LineNumbers`**:
  - New `List<ReportLine> parse(List<String>)` reads the `MARK`/`UNNUMBERED` sentinels and the role markup.
  - `render(...)` becomes "parse, then format for the terminal". Its output stays as today (number column, marker,
    ADDED and REMOVED line styling).
  - It also gets a fold sentinel: `number(line, marker, fold, text)`.
- **`AstPrinter.unchanged()`** (around line 615): when `ReportOptions.folds()` is on, a run longer than the context
  produces a `FOLD` entry ("… (N lines)") with a new fold id, followed by all of the lines tagged with that id. When it
  is off (the terminal), the output is unchanged.
- **`ReportOptions`**: add `boolean folds` (default `false`) and `withFolds`, `withBodyContext` and `withLineNumbers`,
  following the existing `with*` methods.
- **`Reporter`**:
  - New public `List<ReportLine> lines(DiffAnalysis)` and `linesOnly(DiffAnalysis, ChangeLevel)`.
  - `report()` and `reportOnly()` call them and format for the terminal, so `Session` and `Main` don't change.
  - `separator()` becomes a `SEPARATOR` line.

## 2. Web package (`cz.hrebejk.sedi.web`, new)

- **`WebServer`** (public, `AutoCloseable`):
  - Binds `127.0.0.1:<port>` and uses a virtual-thread executor, because SSE connections stay open.
  - Routes:
    - `GET /`, `/app.js`, `/app.css`, `/vendor/preact-htm.js` serve files from `src/main/resources/web/`.
    - `GET /api/theme.css` returns `theme.css()`.
    - `GET /api/state` returns `{version, source, files:[{path, oldPath, change, package, name, counts:{api,impl,cosmetic,imports}}]}`.
      The counts are computed by walking `FileAnalysis.rootChanges()` and `primaryChanges()` (`MemberChange.level()`,
      recursing into `ApiChanges.nested()`) plus `importChanges()`.
    - `GET /api/report?level=&only=&group=&javadoc=&body=&numbers=&file=…` builds a `Reporter` from the parameters with
      `folds=true`. It filters with `new DiffAnalysis(files subset, renames)` and returns
      `{version, lines:[{k, n, m, f, s:[[text, "r-keyword r-strike"], …]}]}`.
    - `GET /api/events` is the SSE stream. It sends `data: {"version":N}` after every re-analysis, plus a comment line
      every 15 s as a heartbeat.
  - Rendering runs under one lock, because the javac trees aren't guaranteed to be thread-safe.
- **`WebSession`** owns the current `(version, DiffAnalysis)` snapshot.
  - It runs the analysis once at start.
  - In working-copy mode it reuses `DirectoryWatcher`, with the same `.java` filter and batch semantics as `Session`.
    It re-analyzes on a single thread, bumps the version and notifies the SSE clients.
  - For `<rev>` it analyzes once and keeps serving.
  - It reuses the analysis lambdas from `Main` through a small functional interface, like `Session.Analysis`.
- **`Json`** is a tiny writer for `Map`/`List`/`String`/`Number`/`Boolean`/`null`. Only output is needed, so no
  library.
- **`Browser.open(URI)`** uses `java.awt.Desktop` when it's supported, otherwise runs `xdg-open` or `open`. It always
  prints the URL too.

## 3. `Main`

- Add `-w/--web` and `--port <n>`. The default port is `0`, which picks a free port, and the URL is printed.
- When `-w` is given:
  - resolve the classpath and theme as now (colored theme always);
  - start `WebSession` and `WebServer`, then open the browser;
  - print `Serving http://127.0.0.1:N — Ctrl+C to stop`;
  - block until the process is stopped. No raw terminal and no `Session`.
- `-l/-g/-b/--javadoc-context/--no-line-numbers` set the UI's initial state, passed as query defaults through
  `/api/state`.

## 4. Front end (`src/main/resources/web/`)

- **`index.html`** loads `vendor/preact-htm.js` (the standalone Preact + htm + hooks bundle, from npm `htm/preact`),
  `app.css`, `api/theme.css` and `app.js` (an ES module).
- **`app.js`** contains Preact components:
  - **Control bar**: a level selector (api / impl / cosmetic / imports) with "only this level" vs "up to"; grouping
    (category / type); javadoc (none / 3 / whole); body context (number input); line numbers on or off. The state is
    kept in the URL hash, so reloads keep it.
  - **Tree** on the left: package → file, with change icons (`+ - ~ →`) and per-level count badges. Clicking a file
    filters the report to it; "All files" clears the filter.
  - **Report**: monospace lines. Spans get role classes. FOLD lines are clickable and show or hide the lines with that
    fold id, all on the client. There is also "expand all" and "collapse all".
  - **Live**: an `EventSource('/api/events')` refetches `/api/state` and `/api/report` when the version changes, and
    keeps the scroll position and expanded folds when it can.
- **`app.css`**: a dark background that matches the terminal palette, a flex layout, a sticky control bar and a
  scrollable tree.

## Files
- New: `web/WebServer.java`, `WebSession.java`, `Json.java`, `Browser.java`; `report/ReportLine.java`, `Span.java`;
  `src/main/resources/web/{index.html,app.js,app.css,vendor/preact-htm.js}`.
- Modified: `Main.java`, `report/{Theme,LineNumbers,AstPrinter,ReportOptions,Reporter}.java`.
- Docs: a `-w` section in `architecture.md` (the report-lines model and the web package), and `todo.md` ("Webapp?").

## Verification
- Unit tests (JUnit 4, alongside the existing ones):
  - `ThemeTest`: markup to `ansi()` gives the right escapes for nested roles, and `css()` writes one rule per role.
  - `LineNumbersTest` (new): `parse` gives number, marker, fold and spans, and `render` output is unchanged.
  - `AstPrinterTest`: with folds on, a long unchanged run gives a FOLD line plus tagged lines. With folds off, output
    is as before.
  - `JsonTest`: escaping, nesting and numbers.
  - `WebServerTest`: build a repo with `GitTestHelper`, start the server on port 0, check `/api/state` and
    `/api/report` with `java.net.http.HttpClient`, then edit a file and check that `/api/events` delivers a new
    version.
- Run `mvn test`. The existing ReporterTest, CosmeticReportTest and SignatureDiffPrinterTest must pass unchanged,
  because PLAIN output is untouched.
- Manual check:
  1. Run `sedi -w` in this repo with uncommitted changes. The browser should open and show the report.
  2. Switch levels and grouping, filter by a file, and expand a fold.
  3. Edit a Java file. The page should update by itself.
  4. Run `sedi -w HEAD~3..HEAD` and check that it serves that range.
  5. Run plain `sedi` and check that the terminal output looks the same as before.
