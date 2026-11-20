# SeDi project

SeDi == Semantic Diff. How to build and use it is in [`README.md`](README.md). Open items are in
[`todo.md`](todo.md).

## Purpose of the project

When coding in Java with AI I'm always confronted with diffs to review the code
written by the LLM (in fact it is the same with code written by humans).
The problem is that the diffs are usually presented as standard colored
unified diffs. This is far from ideal presentation of diffs because all
the changes have the same level of importance and are mixed together.

Some examples of it would be:

### White space

When reading code one is absolutely not interested in added/removed
spaces, tabs or newlines.

### Imports

When someone starts using a new class it is usually reflected in the import
section. But that's usually visible in the code. So showing the changes in
the imports is just a clutter. Like the diff of imports in following diff
is not very important to show

```patch
-import java.util.LinkedList;
+import java.util.ArrayList;
 import java.util.List;

 public class ListImpl {

-    List<String> l = new LinkedList<>();
+    List<String> l = new ArrayList<>();

 }
```

### Renames and moves

If someone just renames a local variable, field or a method then all the
changes which are the rename or handling the rename is from the semantic
view just empty diff. Here is an example of effectively empty diff :

```diff
 public class Rename {

-    static int j = 1;
+    static int count = 1;

-    static void m1() {
+    static void method() {

-        int l = 0;
+        int sum = 0;

-        for( int k = 0; k < j; k++) {
-            l += k;
+        for(int i = 0; i < count; i++) {
+            sum += i;
         }

+
     }

     public static void main(String ...args) {
-        m1();
+        method();
     }

 }
```

### Order changes

If just the order of members in a class changes the diff is also
effectively empty. Here is an example.

```diff
 public class Order {

-    int a = 1;
     float p = 3.14;
+    int a = 1;
+
+    int m2() {
+        return (int)p;
+    }

     void m1() {
         System.out.println( a * p );
     }

-    int m2() {
-       return (int)p;
-    }
+

 }
```

## Solution

The product does not hide anything. It sorts the changes into levels by their
importance, shows the most important first and lets the reviewer dig deeper if
necessary. The levels, from most to least important:

| Level            | What it contains                                                                                 |
|------------------|--------------------------------------------------------------------------------------------------|
| `API`            | Added, removed, renamed, moved and retyped types and members, and javadoc changes                |
| `IMPLEMENTATION` | Changed method bodies, field initializers and initializer blocks, including comments inside them |
| `COSMETIC`       | Renamed locals and parameters, references to renamed elements, names qualified differently      |
| `IMPORTS`        | Added and removed imports (a different order is not a change)                                    |

Whitespace changes are never reported.

API changes which break the clients of the public API (source or binary) are
badged ⚠, those which may break them ⓘ (see `Compatibility`).

### Ideas not implemented yet

- **Rating changes.** Changes which make the API more strict would be rated
  positive, changes which make it less strict or are incompatible negative.
  A rating could be computed for the whole diff.
- **Refactoring detection**, e.g. extract method, inline, move member.

## Status of the original plan

| Phase                                     | Status                                                         |
|-------------------------------------------|----------------------------------------------------------------|
| 0: infrastructure (JGit, javac, test repo)| Done                                                           |
| 1: categorizing changes, tests            | Done                                                           |
| 2: classpath from Maven                   | Done, multi-module too (plus `--classpath` and `--sourcepath`) |
| 3: watching the folder, picocli, console  | Done, with an interactive console mode                         |
| 4: web server                             | Done (`--web`)                                                 |
| 5: refactoring recognition POC            | Renames and type moves only                                    |
| 6: backward incompatibilities and rating  | Incompatibility badges done; rating not started                |

## Out of scope

This project is purely experimental POC. We do not need take into account
all complexities of real world projects. I.e.:

* No other languages than Java
* Only simple maven projects - no fancy maven configurations (multi-module builds are supported)
* For now no support for Java modules

## Coding rules

- Make sure all public classes and its members always have actual javadoc
- Do small commits with reasonable commit messages
- Do NOT add ANSI colors to the commit messages
- If possible use one agent to write implementation and other agent
  to write the tests.
- Will only be run on Java 25+ so it is fine to use latest Java features.
- Try to make the public interfaces of the types as minimal as possible
- Try to keep things simple
- Do not add any dependencies to the project without explicit approval.

---

# Architecture

## Big picture

```
 Main (CLI) ─┬─► Session (console) ──► View ──► Reporter ──► fleck HTML ──► ConsoleHtmlRenderer ──► Pager
             └─► WebReport (--web) ──► View ──► Reporter ──► fleck HTML ──► fleck WebServer ──► browser
                     │
                     ▼
             DiffAstAnalyzer ──► DiffAnalysis (pure data)
               ├─ GitInspector    (what changed)
               ├─ AstLoader       (javac)
               └─ ApiDiffAnalyzer (hash, pair, classify)
```

| Package                    | Responsibility                                                                  |
|----------------------------|---------------------------------------------------------------------------------|
| `cz.hrebejk.sedi`          | `Main` (CLI), `Session` (console event loop), `WebReport` (browser), `View`     |
| `…sedi.compilation`        | Loading attributed javac ASTs straight from git or the disk                     |
| `…sedi.analysis`           | Hashing, pairing and classifying changes into a `DiffAnalysis`                  |
| `…sedi.report`             | Turning a `DiffAnalysis` into fleck HTML: lines, line numbers, folds, CSS       |
| `…sedi.util`               | Git, Maven, file watching, raw terminal, paging, word diff                      |

`analysis` knows nothing about rendering, and `report` never computes a diff. The only thing passed between them is
the `DiffAnalysis` record. The report is HTML (built with fleck, the author's small UI library in `lib/`). The console
renders it to ANSI text with fleck's `ConsoleHtmlRenderer`; the web page shows it directly.

## Running the application

`Main` is a picocli command. It:

1. Resolves the modules (`SourceModule`: own roots, source roots, an `AstLoader` with the classpath). Without
   `--classpath` and `--sourcepath`, from `pom.xml` with `MavenProject`, which uses the Maven model builder and
   Maven Resolver as libraries, so `mvn` is never started. It walks `<modules>` recursively. A `WorkspaceReader`
   serves the reactor's POMs, so sibling dependencies resolve transitively without being installed. Siblings
   contribute their source roots, never jars.
2. Otherwise there is one module: the `--sourcepath` roots, or `src/main/java` and `src/test/java` if they exist,
   otherwise the project directory (`SourceModule.of`), compiled against `--classpath`, the dependencies of
   `pom.xml`, or only the JDK.
3. Picks a mode:
   - **`sedi <rev>`** (not a terminal, or no `-i`): prints the report and exits.
   - **`sedi`**, **`sedi a..`**, **`sedi -i <rev>`**: `Session`. Watches the directory if the new side is the working
     copy.
   - **`--web`**: `WebReport`, with or without `<rev>`; watches the directory if the new side is the working copy.

`View` is the immutable state of what is shown (only one level, by file, and the `ReportOptions` toggles). Its
`key(char)` returns the next view. The console keys and the web buttons go through the same `View.key`.

`Session` is the console event loop. Two daemon threads feed one `BlockingQueue<Event>`: the key reader (`Terminal`
in raw mode through `stty`, no terminal library) and the `DirectoryWatcher`, which merges file events over a 200 ms
quiet period. All analysis and rendering happen on the main thread. File changes that arrive while a long report is
paged are kept in `pending`. `Pager` shows one page at a time with a `KeyHint` line; a key interrupts it.

`WebReport` runs a fleck `WebServer`. Each browser page has its own `View`; all pages share the last analysis and
are refreshed when it is made again. `sedi.js` (Preact components via fleck) implements folds, expanding elided
lines, the sticky "spy" line showing where the report is scrolled to with javadoc on hover, and keyboard shortcuts
that press the toolbar buttons.

## The phases of the analysis

`DiffAstAnalyzer` is the entry point. `ApiDiffAnalyzer` does most of the work.

### Phase 1: What changed (git)

`GitInspector` (JGit) resolves the two versions as a `Range(oldCommit, newCommit)`; a `null` new commit stands for
the working copy:

- **working copy**: `HEAD` against what is on disk, staged or not. If clean, `HEAD~1..HEAD`.
- **`rev`**: the commit against its first parent.
- **`a..b`**, **`a...b`**: two commits, or the merge base and `b`. An omitted left side means `HEAD`, an omitted
  right side the working copy.

It returns JGit `DiffEntry`s with rename detection (`--rename-score`, 50% by default as in git).

### Phase 2: Compiling both versions (javac)

`AstLoader` runs **one javac task per side and module**, with `-proc:none -Xprefer:source`, dropping all
diagnostics so code that does not compile is still analyzed. A changed file belongs to the `SourceModule` whose own
roots contain it most specifically (else the first module). It is compiled with that module's loader (classpath)
and source roots.

The project's other classes are not passed to javac up front. `SourceFileManager` serves `SOURCE_PATH` from
`SourceFileProvider`s, so javac reads a class's source only when it meets it:

- `SourceFileProvider.gitTree(repo, commit, roots)` reads blobs straight from the git object database.
- `SourceFileProvider.directories(base, roots)` reads the working tree.

Only the changed files are attributed: `AstLoader` calls `analyze(enter())` on `JavacTaskImpl`, which needs
`--add-exports jdk.compiler/com.sun.tools.javac.api=ALL-UNNAMED` (set in `pom.xml` for the compiler, the tests and
the manifest of `sedi.jar`). Without it, it falls back to `analyze()`, which attributes everything javac read.

The result is a `CompilationUnitInfo(compilationUnit, trees, docTrees)` per file, valid until the analyzer is closed.

In the long-running modes one `DiffAstAnalyzer` serves the whole session and caches the old side (units and
`ElementInfo` trees) for as long as the old commit stays the same. Units from different javac tasks can be mixed,
because identity is by element id strings, never by javac objects.

### Phase 3: Hashing, first pass

`ElementHashCalculator`, a `TreePathScanner`, turns each compilation unit into a tree of `ElementInfo`
(compilation unit → types → methods, fields and nested types). Every `ElementInfo` keeps one SHA-256 digest per
`ChangeCategory`:

| Category             | What goes into the hash                                                                 |
|----------------------|-----------------------------------------------------------------------------------------|
| `FULL`               | Everything as written, including local names                                            |
| `IGNORE_LOCAL_NAMES` | Everything except the names of locals and parameters                                    |
| `WITHOUT_RENAMES`    | Like the above, with renamed elements hashed under their old names (second pass only)   |
| `SIGNATURE`          | Only the declaration header (no parameter names, no bodies, no members)                 |
| `BODY`               | Only bodies and initializers, local names ignored, renames undone                       |

Each category delegates to a `HashCalculation` strategy (`HashFull`, `HashIgnoreLocalNames`, `HashSignature`,
`HashBody`, all extending `HashBase`). The scanner raises `onAttribute`, `onLocalVariable` and `onIdentifier`, each
tagged with a `Region` (`SIGNATURE` or `BODY`); each strategy decides what to add to its digest. Every tree adds its
kind, identifying attributes, children and an end marker, so different nesting gives different hashes.
javac-synthesized trees (default constructors, canonical record constructors, …) are skipped.

Besides the digests each element has:

- **`Facet` hashes** (`MODIFIERS`, `ANNOTATIONS`, `THROWS`, `NAME`, `RETURN_TYPE`, `PARAM_TYPES`, `TYPE_PARAMS`,
  `SUPERTYPES`, `COMPONENTS`), for pairing, to say which part of a signature changed, and, with their values, to
  decide compatibility.
- **An element id** (`ElementIds`) from javac's resolved `Element`, such as `p.Type#method(int,java.lang.String)`,
  the same for a declaration and every reference to it.
- **`NodeInfo` trees** for the body and signature, used by `BodyDiff` and `CosmeticDiff`.
- Javadoc lines, source lines, the `SourceText`, and the names javac could not resolve.

### Phase 4: Pairing files git did not pair

`ApiDiffAnalyzer.pairRenames` joins an ADD and a DELETE into a synthetic `RENAME` when the top-level types named
after the files share at least half of their members (kind, name, parameter count).

### Phase 5: Collecting renames across the whole diff

`MemberMatcher.match` pairs members of each old/new container in passes of decreasing confidence:

0. Equal `SIGNATURE` hash: unchanged.
1. Equal `IGNORE_LOCAL_NAMES` hash: same code, same member.
2. Same name, parameter types and type parameters.
3. Same name: a changed overload (same parameter count preferred).
4. Same parameter types, type parameters and return type: a method rename (only if exactly one candidate).
5. Same type, modifiers and annotations: a field rename (only if exactly one candidate).
6. Same type shape: a type rename (only if exactly one candidate).

Leftovers are added or removed. Kinds never mix. Every pair whose ids or names differ is recorded in `Renames`.
**Renames are global to the diff**, because a file can refer to an element renamed in another file.

**Moved types.** Leftover removed and added types anywhere in the diff are paired by `ApiDiffAnalyzer.pairMoves`:
same simple name and kind, at least half the members in common (or no members). This finds e.g. a top-level type
that became nested. A move is recorded in `Renames` like a rename; the new `ElementInfo` knows its origin
(`getMovedFrom`), and its body is diffed against its old file.

### Phase 6: Hashing, second pass

The new side is hashed again with the `Renames`. `WITHOUT_RENAMES`, `BODY`, and `SIGNATURE` for references hash a
renamed element under its old name wherever javac resolved a reference to it. Types and static members are hashed
by the element they refer to, so `java.util.List` vs. `List`, a method renamed with all callers, or a parameter
whose type was renamed don't change these hashes.

### Phase 7: Classifying changes

`ApiDiffAnalyzer.changes` walks every container with `MemberMatcher.matchAll` and produces
`MemberChange(pair, level, publicApi, body)`:

- Added, removed, or signature changed → `API`. Javadoc differs on an unchanged signature → `API`.
- `BODY` hash differs, or `BodyDiff` finds changed comments → `IMPLEMENTATION`. A changed type initializer is an
  `IMPLEMENTATION` change of the type.
- `BodyDiff` finds cosmetic marks, or the signature differs only cosmetically → `COSMETIC`.
- `Visibility.isExposed` sets `publicApi`: public or protected, or an interface member, inside an exposed container.
- `Compatibility` (one per container) sets `incompatibilities` (`Incompatibility`, breaking or risky) for elements
  of the public API: from the changed `Facet`s, their values (`ElementInfo.getFacetValues`, e.g. thrown types,
  supertypes, record components) and the modifiers of the trees. Types are compared as written, without subtype
  checks. Inheritance is asked javac (`CompilationUnitInfo.elements()`/`types()`): a removed member still inherited
  is no removal, a supertype still inherited indirectly is not removed, an added override adds nothing, and the
  public members of a type outside the public API count if a public type in its package inherits them. A changed
  constant is the only incompatibility of an `IMPLEMENTATION` change. A type's own one is also in
  `ApiChanges.incompatibilities`.

One member can appear at several levels. Member renames are shown in the API section but never make a body count as
changed.

**`BodyDiff`** is a tree diff on `NodeInfo`s, not on lines. Children are aligned by LCS of their `BODY` hashes (ties
broken by `FULL`-equal pairs); unaligned ones are paired by kind and diffed recursively. A simple statement is shown
with inline edits if it has at most 2 edited leaves making up at most half its text, otherwise as replaced.
Wrapping and unwrapping (`p` → `!p`, an added `if`/`try` around statements, and the reverse) are recognized.
Comments are compared as text with whitespace collapsed. The result is `BodyChanges`: identity maps from new-version
trees to a sealed `Mark`, plus removed old trees and where to show them.

**`CosmeticDiff`** walks node pairs `BodyDiff` found `BODY`-equal and marks the nodes whose own `FULL` hash differs
as `Renamed` or `Edited`.

**Imports** are compared as sets of `[static ]qualified.Name`.

### Phase 8: The result

```
DiffAnalysis(files: List<FileAnalysis>, renames: Renames)
MemberChange(pair, level, publicApi, body, incompatibilities: Set<Incompatibility>)
FileAnalysis(diff, oldUnit, newUnit, oldRoot, newRoot, oldPrimary, newPrimary,
             class and package names, primaryChanges, rootChanges: ApiChanges, importChanges)
ApiChanges(typeName, exposed, type: ElementPair, changes: List<MemberChange>, nested: List<ApiChanges>,
           incompatibilities: Set<Incompatibility>)
```

`analyze` always analyzes **all** changed Java files, so renames are found everywhere, then keeps only the files the
caller's filter accepts.

## Reporting

`Reporter` turns a `DiffAnalysis` into a fleck `HTML.Tag` (`div.report`) of lines. Two layouts:

- **By category** (`report`, `reportOnly`): `# API changes`, `# Implementation changes`, `# Cosmetic changes`,
  `# Imports`, up to `--level`. An empty section says `NO CHANGES`.
- **By file** (`reportByFile`, key `l`, "All" in the browser): every level, file by file. Files and levels are folds;
  levels other than API start collapsed in the browser.

The printers produce `Line`s made of `Fragment`s, marked up by `Markup` with `Css` classes:

- **`SignatureDiffPrinter`** prints declarations from the source text; changed parts as struck old / added new.
  `plain()` and `cosmetic()` variants for the implementation and cosmetic sections.
- **`AstPrinter`** prints `BodyChanges` as the real source lines. Runs of unchanged lines longer than
  `--body-context` collapse to `… (N lines)` (expandable in the browser).
- **`JavadocPrinter`** shows a word diff (`WordDiff`) for similar lines, otherwise removed then added lines.
- **`LineNumbers.render`** lays out the number column and `+ - ~` markers once its width is known, and builds the
  fold `div`s.
- **`Syntax`** colors code from `SyntaxRoles` (roles from the elements javac resolved, not from text).
- **`ReportStyles`** builds the fleck `StyleSheet` from a palette or a `--theme` properties file;
  `ReportStyles.plain()` uses `~~`/`__` markers instead of colors.

Names javac could not resolve are shown white on red. `Markup.incompatible` appends the ⚠/ⓘ badge (`.breaking`,
`.risky`) with the reasons as its `title` to a declaration or type heading. Renamed or moved names outside the API section are `.renamed`
spans with a `title` of `old → new`.

## Key principles

1. **Rank, don't hide.** Every change is reported at some level. Only whitespace is dropped.
2. **Compare structure, not text.** Whitespace, member order and import order never count as changes.
3. **Several hashes per element.** Which hashes are equal tells what kind of change happened.
4. **Identity comes from javac.** Renames and references are tracked by resolved element ids, never simple names.
5. **Renames are global and undone before comparing.** A rename is shown once, in the API section.
6. **Pairing goes from most to least confident.** Ambiguous candidates are reported as added and removed.
7. **Analysis and reporting are separate.** `DiffAnalysis` is the only interface between them.
8. **Sources are read lazily, where they are.** No temporary copies; the working copy is what is on disk.
9. **Show the code as it is written.** Signatures and bodies are printed from the source text.
10. **Broken code still works.** Diagnostics are ignored, unresolved names are marked.

## Tests

JUnit 4. `GitTestHelper` creates throwaway repositories with an initial commit and working-copy edits; `TestSources`
holds sample sources for each kind of change. `ChangeClassificationTest`, `MemberMatcherTest`, `RenamesTest`,
`MovedTypesTest`, `ReporterTest` and `CosmeticReportTest` show the expected behavior best.

## Known weaknesses

- **Re-analysis redoes the new side.** Only the old side is cached.
- **`WITHOUT_RENAMES`** is computed but not read by the main code; rename undoing is special-cased in
  `ElementHashCalculator` (`undoesRenames()`, `undoesRenamedReferences()`) instead of living in the strategies.
- **Two hashing passes** of the new side.
- **Greedy pairing.** Passes 4–6 give up on several candidates; moves are found only when the simple name stays.
- **`pairRenames`** uses a fixed 50% threshold independent of `--rename-score`.
- **Local and anonymous classes** are not modeled as elements (see `todo.md`).
- **Imports** are a set difference; `LinkedList` → `ArrayList` is not paired (see `todo.md`).
- **Javadoc** is compared as text lines although `DocTrees` is loaded.
- **Compatibility** has no subtype checks (a narrower return type is breaking too) and does not tell checked from
  unchecked exceptions. It looks at inheritance through javac's `Element`s kept in `ElementInfo`, so those, like
  the trees, are valid only while the `DiffAstAnalyzer` is open.
- **Multi-module**: one javac task per module and side. A sibling dependency of a module is a placeholder (its
  directory) in the resolver. A changed `pom.xml` is not re-read while watching.
- **Diagnostics are thrown away**, so the user does not learn why names are red.
- **Large classes**: `Reporter` (~1100 lines), `AstPrinter` (~1000), `BodyDiff`, `SignatureDiffPrinter`,
  `ElementHashCalculator` (~650–700). `FileAnalysis` has 14 components.
- **Identity maps keyed by javac `Tree`s** tie `BodyChanges` to live javac objects, valid only while the
  `DiffAstAnalyzer` is open.
- **Stale names in javadoc**: `HashContext`, `ElementInfo` and `ElementHashCalculator` still mention
  `ReducedAstNode`, `ShaContext` and `MemberShaCalculator`.
