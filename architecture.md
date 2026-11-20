# SeDi architecture

SeDi (Semantic Diff) shows the changes of Java sources the way a reviewer wants to read them. A plain unified diff
gives every changed line the same weight. SeDi compiles both versions of the changed files with javac and compares
their attributed syntax trees instead of their text. It then sorts every change into one of four levels, from most to
least important:

| Level            | What it contains                                                                                   |
|------------------|----------------------------------------------------------------------------------------------------|
| `API`            | Added, removed, renamed and retyped types and members, and javadoc changes                          |
| `IMPLEMENTATION` | Changed method bodies, field initializers and initializer blocks, including comments inside them   |
| `COSMETIC`       | Renamed locals and parameters, references to renamed elements, names qualified differently        |
| `IMPORTS`        | Added and removed imports (a different order is not a change)                                      |

Whitespace changes are never reported. Nothing else is hidden: a reviewer starts with the API and goes down the
levels as far as they need to.

The original vision, the planned phases and the coding rules are in [`claude.md`](claude.md). Open items are in
[`todo.md`](todo.md).

---

## 1. Big picture

```
 CLI (Main) ──► Session ──► DiffAstAnalyzer ──► DiffAnalysis ──► Reporter ──► Pager ──► terminal
   │              ▲           │                 (pure data)       │
   │              │           ├─ GitInspector       (what changed)│
   │   DirectoryWatcher       ├─ AstLoader          (javac)       ├─ SignatureDiffPrinter
   │   key reader thread      └─ ApiDiffAnalyzer    (hash, pair,  ├─ AstPrinter
   │                                                 classify)    ├─ JavadocPrinter
   └─ MavenClasspath                                              └─ LineNumbers, Theme
```

The code is split into four packages with one-way dependencies:

| Package                    | Responsibility                                                              |
|----------------------------|-----------------------------------------------------------------------------|
| `cz.hrebejk.sedi`          | `Main` (CLI) and `Session` (the running, interactive application)           |
| `…sedi.compilation`        | Loading attributed javac ASTs straight from git or the disk                  |
| `…sedi.analysis`           | Hashing, pairing and classifying changes into a `DiffAnalysis`              |
| `…sedi.report`             | Turning a `DiffAnalysis` into colored, numbered text lines                  |
| `…sedi.util`               | Git, Maven, file watching, raw terminal and paging                          |

`analysis` knows nothing about rendering, and `report` never computes a diff. The only thing passed between them is
the `DiffAnalysis` record. This split was made on purpose (commit `8e6fece`, "Splitting analysis from reporting") so a
different front end, such as the planned web UI, can reuse the analysis unchanged.

---

## 2. Running the application

`Main` is a picocli command. It:

1. Resolves the project classpath from `pom.xml` with `MavenClasspath`. This uses the Maven model builder and Maven
   Resolver as libraries, so `mvn` is never started. Without a `pom.xml` only the JDK is on the classpath.
2. Builds a `Reporter` from the options: `--level`, `--body-context`, `--javadoc-context`,
   `--no-line-numbers`, `--color` and `--theme`.
3. Picks a mode:
   - **`sedi <rev>`**: analyzes a commit or a range (`a..b`, `a...b`), prints the report and exits.
   - **`sedi`**: analyzes the working copy, then watches the directory and shows the report again after every
     change.
   - **`sedi -i <rev>`**: like the first mode, but stays open for the interactive keys.

`Session` is the event loop of the interactive modes. Two daemon threads feed one `BlockingQueue<Event>`:

- the **key reader**, using `Terminal` in raw mode through `stty`, so no terminal library is needed;
- the **`DirectoryWatcher`**, which merges file events into batches over a 200 ms quiet period.

All analysis and rendering happen on the main thread. The keys are `a`/`i`/`c`/`m` to show one level, `d` to cycle
the javadoc context, `g` to toggle grouping, `h` for help and `q` to quit. File changes that arrive while a long
report is being paged are kept in `pending` and handled after the paging. The `Pager` shows one page at a time.
Pressing a hot key in the middle of a long report interrupts it.

---

## 3. The phases of the analysis

`DiffAstAnalyzer` is the entry point of the analysis. `ApiDiffAnalyzer` does most of the work.

### Phase 1: What changed (git)

`GitInspector` (JGit) resolves the two versions to compare as a `Range(oldCommit, newCommit)`:

- **working copy**: `HEAD` against what is on disk, staged or not. If the working copy is clean, it falls back to
  `HEAD~1..HEAD`. A `null` new commit stands for the working copy.
- **`rev`**: the commit against its first parent.
- **`a..b`** and **`a...b`**: two commits, or the merge base and `b`.

It returns JGit `DiffEntry`s with rename detection. The similarity threshold is `--rename-score`, 50% by default as
in git.

### Phase 2: Compiling both versions (javac)

`AstLoader` runs **one javac task per side**: all old versions in one task, all new versions in another. It uses
`-proc:none -Xprefer:source` and drops all diagnostics, so code that does not compile is still analyzed.

The project's other classes are not passed to javac up front. `SourceFileManager` serves `SOURCE_PATH` from
`SourceFileProvider`s, so javac reads a class's source only when it meets that class:

- `SourceFileProvider.gitTree(repo, commit, roots)` reads blobs straight from the git object database. Nothing is
  copied to a temporary directory.
- `SourceFileProvider.directories(base, roots)` reads the working tree. This is what is on disk, which is what builds.

Only the changed files are attributed. javac also queues every class it reads from the source path for attribution,
so `JavacTask.analyze()` would attribute their bodies too, and transitively everything they refer to. `AstLoader`
calls `analyze(enter())` on javac's own `JavacTaskImpl` instead, which needs `--add-exports
jdk.compiler/com.sun.tools.javac.api=ALL-UNNAMED` (set in `pom.xml` for the compiler, the tests and the manifest of
`sedi.jar`). Without the export, it falls back to `analyze()`.

The source roots are `src/main/java` and `src/test/java` if they exist, otherwise the repository root. The result is
a `CompilationUnitInfo(compilationUnit, trees, docTrees)` per file. These stay valid until the analyzer is closed.

In the interactive modes, one `DiffAstAnalyzer` serves the whole session. It caches the old versions, both their
`CompilationUnitInfo`s and their `ElementInfo` trees, for as long as the old commit stays the same. A later analysis
compiles only the old versions of files that were not changed before, in a small extra javac task. Units from
different tasks can be mixed, because identity is by element id strings, never by javac objects. When the old commit
changes (after a commit, or when a clean working copy falls back to `HEAD~1..HEAD`), the cache is dropped.

### Phase 3: Hashing, first pass

`ElementHashCalculator`, a `TreePathScanner`, turns each compilation unit into a tree of `ElementInfo`:
compilation unit → types → methods, fields and nested types.

Every `ElementInfo` keeps **one SHA-256 digest per `ChangeCategory`**. Each category is a different way of looking
at the same code:

| Category             | What goes into the hash                                                                    |
|----------------------|---------------------------------------------------------------------------------------------|
| `FULL`               | Everything as written, including local names                                                |
| `IGNORE_LOCAL_NAMES` | Everything except the names of locals and parameters                                        |
| `WITHOUT_RENAMES`    | Like the above, with renamed elements hashed under their old names (second pass only)       |
| `SIGNATURE`          | Only the declaration header (no parameter names, no bodies, no members)                     |
| `BODY`               | Only bodies and initializers, local names ignored, renames undone                          |

Each category delegates to a `HashCalculation` strategy (`HashFull`, `HashIgnoreLocalNames`, `HashSignature`,
`HashBody`, all extending `HashBase`). The scanner raises three kinds of events, `onAttribute`, `onLocalVariable` and
`onIdentifier`, each tagged with a `Region` (`SIGNATURE` or `BODY`). Each strategy decides what to add to its own
digest. `onChildCompleted` decides whether a member's hash is folded into its parent's hash: `FULL` does this,
`SIGNATURE` and `BODY` do not.

The scanner records the structure, not only the tokens:

- Every tree adds its **kind**, its identifying attributes, its children and an **end marker**, so different nesting
  gives different hashes.
- Absent optional children add `NULL`, and lists add their length.
- javac-synthesized trees are skipped: default constructors, canonical record constructors, implicit enum-constant
  types and record component fields.

Besides the per-category digests, each element has:

- **`Facet` hashes**: `MODIFIERS`, `ANNOTATIONS`, `THROWS`, `NAME`, `RETURN_TYPE`, `PARAM_TYPES`, `TYPE_PARAMS`.
  They are used for pairing and to say which part of a signature changed.
- **An element id** (`ElementIds`) taken from javac's resolved `Element`, such as `p.Type#method(int,java.lang.String)`.
  It is computed the same way for a declaration and every reference to it, so renames are tracked by identity, not by
  simple name.
- **`NodeInfo` trees** for the body and the signature parts. Each node has its category hash and its `FULL` hash, and
  both have an "own" variant that leaves out the children. `BodyDiff` and `CosmeticDiff` work on these trees.
- The javadoc lines, source lines, the `SourceText`, and the set of names javac could not resolve.

### Phase 4: Pairing files git did not pair

`ApiDiffAnalyzer.pairRenames` handles an ADD and a DELETE that git did not see as similar enough. It joins them into
a synthetic `RENAME` entry when the top-level types named after the files share at least half of their members,
compared by kind, name and parameter count.

### Phase 5: Collecting renames across the whole diff

`MemberMatcher.match` pairs the members of each old/new container in passes of decreasing confidence:

0. Equal `SIGNATURE` hash: the member is unchanged.
1. Equal `IGNORE_LOCAL_NAMES` hash: the same code, so the same member, whatever else changed.
2. Same name, parameter types and type parameters: modifiers, annotations, throws or return type changed.
3. Same name: a changed overload. Candidates with the same parameter count are preferred.
4. Same parameter types, type parameters and return type: a method rename. Only taken when exactly one candidate
   matches.
5. Same type, modifiers and annotations: a field rename. Only taken when exactly one candidate matches.
6. Same "type shape" (kind, modifiers and sorted member signatures): a type rename. Only taken when exactly one
   candidate matches.

Whatever is left over is added or removed. Kinds never mix, and constructors pair only with constructors. Every pair
whose ids or names differ is recorded in `Renames`, and nested types are visited recursively. **Renames are global to
the diff**, because a file can refer to an element renamed in another file.

**Moved types.** The types left over as removed or added in any container of any file, nested ones included, are then
paired across the whole diff by `ApiDiffAnalyzer.pairMoves`. A pair must have the same simple name and declaration kind,
and at least half of the members in common by kind, name and parameter count, or no members at all. Higher
similarity goes first, and a type moves together with the types nested in it. This finds a top level type that became
a nested one, like `styles.Color` → `css.CssValues.Color`, and the other way around. A move is recorded in `Renames`
like a rename, so references to the moved type become cosmetic. In the classification, the moved type is a pair of its
old version, from wherever it was, and its new version. Its body is diffed against the file of its old version. The new
`ElementInfo` knows where it came from (`getMovedFrom`). The removed type at the old place is dropped, and an added type
with types moved into it gets the `ApiChanges` of those moves.

### Phase 6: Hashing, second pass

The new side of each file is hashed again with the `Renames` (`new ElementHashCalculator(trees, renames)`). The
categories that undo renames (`WITHOUT_RENAMES`, `BODY`, and `SIGNATURE` for references) hash a renamed element under
its old name, wherever javac resolved a reference to it. Types and static members are hashed by the element they refer
to, not by how the name is written. This makes these changes invisible to those hashes:

- `java.util.List` and `List`, or `Math.max` and a statically imported `max`;
- a method renamed together with all its callers;
- a parameter whose type was renamed.

### Phase 7: Classifying changes

`ApiDiffAnalyzer.changes` walks every container recursively with `MemberMatcher.matchAll` and produces a
`MemberChange(pair, level, publicApi, body)` for every change:

- **Added, removed, or signature changed** → `API`.
- **Javadoc differs** on an unchanged signature → `API`. The javadoc is part of the API.
- **`BODY` hash differs**, or `BodyDiff` finds changed comments → `IMPLEMENTATION`.
- **`BodyDiff` finds cosmetic marks**, or the signature differs only cosmetically (renamed parameter, renamed type
  referred to, different qualification) → `COSMETIC`.
- A changed type initializer is an `IMPLEMENTATION` change of the type itself.
- `Visibility.isExposed` sets `publicApi`: public or protected, or an interface member, inside an exposed container.

One member can appear in several levels. For example, a renamed member whose body was also rewritten gets an `API`
line and an `IMPLEMENTATION` line. Member renames are always shown in the API section, but they never make a body
count as changed.

**Body diff (`BodyDiff`)**. This is a tree diff on `NodeInfo`s. It does not diff text lines.

- The children of two paired nodes are aligned by the longest common subsequence of their `BODY` hashes. Ties are
  broken by the most `FULL`-equal pairs.
- Unaligned children between the anchors are paired by kind and diffed recursively. Children that cannot be paired
  are added or removed.
- A simple statement is a unit. It is shown with inline edits only if it has at most 2 edited leaves and they make up
  at most half of its text. Otherwise it is shown as replaced.
- Wrapping is recognized: `p` → `!p` or `f(p, 1)` (`Wrapped`), `!p` → `p` (`Unwrapped`), an `if` or `try` added
  around existing statements (`WrappedStatements`), and the reverse (`UnwrappedStatements`).
- Comments inside the body are compared as text with whitespace collapsed. A removed comment is shown next to the
  code it was next to.
- The result is a `BodyChanges` record: identity maps from new-version trees to a sealed `Mark` (`Changed`, `Edited`,
  `Added`, `Replaced`, `Wrapped`, `Unwrapped`, `WrappedStatements`, `UnwrappedStatements`, `Kept`, `Renamed`), plus
  the removed old trees and where to show them.

**Cosmetic diff (`CosmeticDiff`)**. It takes the node pairs `BodyDiff` found equal by `BODY` hash. These have the same
structure, so it walks them side by side and compares their `FULL` hashes. A node whose own `FULL` hash differs is
the change. It is marked `Renamed` (with the old name), or `Edited` if the name is a node of its own.

**Imports** are compared as sets of `[static ]qualified.Name`.

### Phase 8: The result

```
DiffAnalysis(files: List<FileAnalysis>, renames: Renames)
FileAnalysis(diff, oldUnit, newUnit, oldRoot, newRoot, oldPrimary, newPrimary,
             class and package names, primaryChanges, rootChanges: ApiChanges, importChanges)
ApiChanges(typeName, exposed, type: ElementPair, changes: List<MemberChange>, nested: List<ApiChanges>)
```

`analyze` always analyzes **all** changed Java files, so renames are found everywhere. It then keeps only the files
accepted by the caller's filter.

---

## 4. Reporting

`Reporter` turns a `DiffAnalysis` into fleck HTML, a `div.report` of lines with line numbers, styled by `ReportStyles`. There are two layouts:

- **By category** (`report`, the default): one `# API changes`, `# Implementation changes`, `# Cosmetic changes`,
  `# Imports` section per level up to `--level`. An empty section says `NO CHANGES`. The API section lists new
  top-level types with their public API, then removed types, renamed types, and changed types, with their members in
  a tree. `reportOnly` shows one of the sections.
- **By file** (`reportByFile`, the key `l`, All in the browser): every level whatever `--level` says, file by file,
  under each file the levels with changes as `# API`, `# Implementation`, `# Cosmetic`, `# Imports`. The files and
  their levels are folds the browser collapses; the levels other than the API start collapsed.

The printers:

- **`SignatureDiffPrinter`** prints one declaration per line from the **source text**, not from javac's pretty
  printer (commit `64ddc4f`). Changed parts are shown as `~~old~~ __new__`. Parameters are aligned by type when their
  number changes. Its `cosmetic()` variant shows only renames and qualification changes.
- **`AstPrinter`** prints `BodyChanges` as the real source lines, keeping their spacing, braces and comments. It
  prints only the changed lines and the statements that enclose them. Runs of unchanged lines longer than
  `--body-context` collapse to `… (N lines)`. Removed code is struck through in front of its replacement, and added
  code is underlined.
- **`JavadocPrinter`** shows a word diff for similar lines, and blocks of fully changed lines as all removed lines
  followed by all added lines. The context is set by `--javadoc-context` or the `d` key.
- **`LineNumbers`** adds a left column with the original line number and a `+`, `-` or `~` marker. Printers embed the
  number in each line, and the column is laid out last, once its width is known.
- **`Theme`** maps a `Role` (keyword, type, strike, added, …) to a true-color style. It can be loaded from
  `~/.config/sedi/theme.properties` or from `--theme`. `Theme.PLAIN` uses `~~` and `__` markers instead of colors.
  Syntax coloring comes from `analysis.SyntaxRoles`: names get their roles (type, method, field, constant) from the
  elements javac resolved them to, literals (numbers, strings, characters, booleans, `null`) from their trees, and only
  comments and reserved keywords are found in the text. The names of declared types, methods and fields are bold
  `declaration`s; their usages are not.

Names javac could not resolve are shown white on red, so code that does not compile is visible but does not break
the analysis.

Renames outside the API section show only the new name. In bodies printed with the implementation changes (the
cosmetic marks `BodyDiff` copies into `BodyChanges.renamed`) and in the signature heads of the implementation and
cosmetic sections (`SignatureDiffPrinter.plain()` and `cosmetic()`), a renamed or moved name is a `.renamed` span. It
has a muted yellow background and a `title` of `old → new`, which the web page shows on hover. A moved type shows
in the API section where it now is, with its old qualified name struck through. In the whole source of an added type
it is collapsed to its first line and a note saying where it moved from. A deleted file whose types all moved is not
listed.

---

## 5. Key principles

1. **Rank, don't hide.** Every change is reported at some level. Only whitespace is dropped.
2. **Compare structure, not text.** Whitespace, member order and import order never count as changes, because
   attributed trees are compared, not lines.
3. **Several hashes per element.** Each `ChangeCategory` hash ignores something different. Which hashes are equal
   tells what kind of change happened: same `IGNORE_LOCAL_NAMES` means only local names changed; same `BODY` but
   different `FULL` means a cosmetic change.
4. **Identity comes from javac.** Renames and references are tracked by resolved element ids, never by simple names,
   so an unrelated element with the same name is not affected.
5. **Renames are global and are undone before comparing.** A rename is shown once, in the API section, and makes
   every place that only follows the rename cosmetic.
6. **Pairing goes from most to least confident.** Ambiguous rename candidates are reported as added and removed
   rather than guessed.
7. **Analysis and reporting are separate.** `DiffAnalysis` is the only interface between them.
8. **Sources are read lazily, where they are.** javac reads git blobs or disk files on demand, with no temporary
   copies. The working copy means what is on disk, staged or not.
9. **Show the code as it is written.** Signatures and bodies are printed from the source text, with its spacing,
   comments and line numbers.
10. **Broken code still works.** Diagnostics are ignored, and unresolved names are marked instead of failing.
11. **Small and self-contained.** Java 25, few dependencies (JGit, picocli, Maven Resolver), `stty` instead of a
    terminal library, the smallest possible public API, and javadoc on everything public (see `claude.md`).

---

## 6. Key classes

| Class                                 | Role                                                                                              |
|---------------------------------------|---------------------------------------------------------------------------------------------------|
| `Main`                                | CLI options, mode selection, theme and classpath setup                                            |
| `Session`                             | Event loop: keys, file changes, re-analysis, view state (`only`, grouping, javadoc)               |
| `GitInspector`                        | Range resolution, `DiffEntry`s, rename detection                                                  |
| `MavenClasspath`                      | Effective POM and dependency resolution without `mvn`                                             |
| `AstLoader`                           | One javac task per side; returns `CompilationUnitInfo`s                                           |
| `SourceFileProvider` / `SourceLayers` | Lazy sources from a git tree or a directory                                                       |
| `SourceFileManager` / `SourceFile`    | javac `JavaFileManager` that serves those sources                                                 |
| `DiffAstAnalyzer`                     | Orchestrates phases 1–2, calls `ApiDiffAnalyzer`                                                  |
| `ApiDiffAnalyzer`                     | Phases 4–8: file rename pairing, rename collection, rehashing, classification                     |
| `ElementHashCalculator`               | AST scanner that builds `ElementInfo` trees and feeds the digests                                 |
| `ElementInfo` / `NodeInfo`            | Hashed element tree / hashed body and signature node tree                                         |
| `ChangeCategory` + `Hash*`            | The kinds of hash and their strategies                                                            |
| `Facet`                               | Parts of a signature, hashed separately                                                           |
| `ElementIds` / `Renames`              | Element identity, and the old names of renamed elements by new id                                 |
| `MemberMatcher`                       | Multi-pass pairing of old and new members                                                         |
| `BodyDiff` / `CosmeticDiff`           | Tree diff of bodies, and cosmetic marks inside equal subtrees                                     |
| `BodyChanges`                         | Diff result as sealed `Mark`s on new-version trees                                                |
| `ChangeLevel` / `MemberChange`        | Importance levels and one classified change                                                       |
| `DiffAnalysis` / `FileAnalysis` / `ApiChanges` | The analysis result handed to reporting                                                  |
| `Reporter` / `ReportOptions`          | Sections, grouping, ordering and options                                                          |
| `SignatureDiffPrinter` / `AstPrinter` / `JavadocPrinter` | Rendering of signatures, bodies and javadoc                                    |
| `LineNumbers` / `Theme`               | Number column and markers; colors and styles                                                      |
| `Pager` / `KeyHint` / `Terminal` / `DirectoryWatcher` | Terminal user interface                                                           |

Tests are JUnit 4. `GitTestHelper` creates throwaway repositories with an initial commit and working-copy edits, and
`TestSources` holds sample sources for each kind of change. `ChangeClassificationTest`, `MemberMatcherTest`,
`RenamesTest`, `ReporterTest` and `CosmeticReportTest` show the expected behavior best.

---

## 7. How it evolved

| Dates          | Milestone                                                                                                |
|----------------|----------------------------------------------------------------------------------------------------------|
| Oct 12–18      | Plan in `claude.md`; first AST loading and per-member SHAs                                               |
| Oct 24         | Categories became an enum with `HashCalculation` strategies; `SIGNATURE` and facets; picocli `Main`; Maven Resolver classpath; directory watching |
| Oct 25         | `MemberMatcher` passes; top-level type renames via git; `Renames` and the `WITHOUT_RENAMES` second pass; lazy git sources for javac |
| Oct 26–30      | Package split (`compilation`, `analysis`, `report`, `util`); analysis separated from reporting; four `ChangeLevel`s and `BODY` hash; themes |
| Oct 31         | Markdown-like sections; added, removed, renamed and changed blocks; javadoc diffs; interactive mode       |
| Nov 1–2        | Type renames hidden in API and impl; `--rename-score`; enum fixes; AST-based signature printing; parameter renames cosmetic; wrap and unwrap detection |
| Nov 4–7        | Qualified vs. simple names are cosmetic; unresolved names in red; line numbers everywhere; signatures and bodies printed from source; tree-like rendering; dedicated `CosmeticDiff` |

---

## 8. What could be improved

### Analysis

- **Re-analysis still redoes the new side.** The old side is cached (see Phase 2), but every file change compiles
  and hashes all the changed new files again. Recompiling only the files that changed since the last analysis would
  need the unchanged new units to stay consistent with the recompiled ones, which separate javac tasks don't
  guarantee for the new side. Old-side javac tasks also stay in memory until the old commit changes.
- **Unused or leaky categories.** `WITHOUT_RENAMES` is computed but nothing in the main code reads it. Rename undoing
  is special-cased in `ElementHashCalculator` through `undoesRenames()` and `undoesRenamedReferences()` instead of
  living in the strategies. Either the strategy interface should carry this, or the category should be removed.
- **Two hashing passes.** The new side is hashed once to find renames and again to undo them. Hashing the old side
  with "new names" in a single pass after matching, or computing the rename-sensitive digests lazily, would halve the
  hashing cost.
- **Pairing heuristics are local and greedy.**
  - Nested types are paired only by name, so a renamed inner type is shown as removed and added (open in
    `todo.md`). Moves between containers are found only when the simple name stays the same.
  - Passes 4–6 give up when there are several candidates.
  - A global score-based assignment, such as Hungarian matching on facet similarity, would handle "rename + small
    signature change" and swapped names better.
- **File-rename pairing** (`pairRenames`) uses a fixed 50% member-overlap threshold that is independent of
  `--rename-score`.
- **Local and anonymous classes** are not modeled as elements (open in `todo.md`).
- **Refactoring detection** (extract method, inline, move member) is not implemented yet, and neither are the
  `claude.md` phases 5 and 6: backward-compatibility badges and API change rating. The facet hashes and `publicApi`
  flag are already enough to start on the badges.
- **Import pairing** is a set difference. Pairing a removed and an added import by package and name similarity would
  make `LinkedList` → `ArrayList` read as one change (open in `todo.md`).
- **Javadoc** is compared as text lines. `DocTrees` is already loaded and could give a structured diff of
  `@param`/`@return`/`@throws`.
- **Project model.** Source roots are hard-coded (`src/main/java`, `src/test/java`). Multi-module builds, parent
  POMs outside `relativePath`, generated sources, JPMS and non-Java files are out of scope. Reading the roots from the
  effective POM would be a cheap first step.
- **Diagnostics are thrown away.** Showing a short "N compile errors" note would tell the user why names are red.

### Code structure

- **Large classes.** `ElementHashCalculator` (650 lines), `BodyDiff` (620), `AstPrinter` (810), `Reporter` (680)
  and `SignatureDiffPrinter` (510) mix several concerns. `ElementInfo` holds digests, facets, javadoc, lines, source,
  unresolved names and the node stack used while scanning. Splitting the scan-time builder from the immutable result
  would make the model easier to follow and safe to cache.
- **Wide records.** `FileAnalysis` has 14 components, several of them derived (class and package names, primary
  type). Grouping them into an old-side and a new-side record would simplify call sites.
- **String-based rendering pipeline.** Printers return `String`s with ANSI codes and embedded line-number markers,
  which `LineNumbers` parses back. A small structured line model (number, marker, styled spans) would remove the
  re-parsing, make tests independent of styling, and could also drive the planned web UI (phase 4).
- **Identity maps keyed by javac `Tree`s** are used throughout `BodyChanges` and the printers. This works, but it
  ties the result to live javac objects, which stay valid only while the `DiffAstAnalyzer` is open. Copying what
  reporting needs out of the trees would let the analysis outlive the compiler.
- **Stale names in javadoc.** `HashContext`, `ElementInfo` and `ElementHashCalculator` still mention
  `ReducedAstNode`, `ShaContext` and `MemberShaCalculator`. `HashContext.updateDigest` is no longer needed.

### User experience

- Expanding and collapsing body context interactively (`→`/`←`, open in `todo.md`).
- An option to ignore all renames, including in signatures, for a pure "did the behavior change" view.
- A web front end that reuses `DiffAnalysis` (phase 4 of the plan).
- A machine-readable output, such as JSON, for use in CI or by other tools.
