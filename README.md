# SeDi – Semantic Diff for Java

> **A demonstrator only.** SeDi was created entirely with AI, under the direction of the author, to try out an
> idea: reviewing Java changes by what they mean rather than by which lines they touch. It is not a product, and
> it is not maintained as one. Ideally, this kind of functionality would be built into IDEs and code review tools,
> where the compiler model of the project is already available.

A plain unified diff gives every changed line the same weight. SeDi compiles both versions of the changed files with
javac, compares their attributed syntax trees instead of their text, and sorts every change into one of four levels,
from most to least important:

| Level              | What it contains                                                                               |
|--------------------|------------------------------------------------------------------------------------------------|
| **API**            | Added, removed, renamed, moved and retyped types and members, and javadoc changes              |
| **Implementation** | Changed method bodies, field initializers and initializer blocks, including comments inside them |
| **Cosmetic**       | Renamed locals and parameters, references to renamed elements, names qualified differently    |
| **Imports**        | Added and removed imports (a different order is not a change)                                  |

Whitespace changes and changes of member order are never reported. Nothing else is hidden: you start with the API
and go down the levels as far as you need to. A method renamed together with all its callers is one line in the API
section; the call sites show up only as cosmetic changes. Code that does not compile is still analyzed, and the
names javac cannot resolve are shown in red.

API changes of the public API that break existing clients (they no longer compile, or no longer link when already
compiled) are marked with ⚠: e.g. a removed or renamed member, reduced visibility, a changed parameter, return or
field type, an added checked exception, `final` or `abstract` added, an abstract method added to an interface. Changes
that only *may* break clients are marked with ⓘ: a method added to a class clients can extend, a default method or
an enum constant added, an exception removed from `throws`, a changed constant (clients keep the inlined old value).
Types are compared as written, without subtype checks, so e.g. a narrower return type is ⚠ too.

## Building

You need Java 25 or newer and Maven.

```
mvn package
java -jar target/sedi.jar --help
```

`target/sedi.jar` contains all the dependencies. The examples below write `sedi` for `java -jar target/sedi.jar`.

## The project to inspect

SeDi works on the project in the current directory, or in the one given with `--dir`. The project must be a git
repository. To compile the changed files, SeDi needs the project's classpath and source roots:

- **Classpath.** If the project has a `pom.xml`, its dependencies are resolved from it (without requiring/starting `mvn`).
  Otherwise, or to override it, use `--classpath`. Without either, only the JDK is on the classpath. Code that does
  not compile is still analyzed; unresolved names are just shown in red.
- **Source roots.** By default these are `src/main/java` and `src/test/java` if they exist, otherwise the project
  directory. Use `--sourcepath` for other layouts.

For example, for a project without Maven:

```
sedi --classpath lib/a.jar:lib/b.jar --sourcepath src:test
```

Both options take entries separated by the platform path separator (`:` or `;`) and may be repeated. The source
roots must be within the project directory.

## What is compared

| Command        | Compares                                                                                       |
|----------------|------------------------------------------------------------------------------------------------|
| `sedi`         | `HEAD` with the working copy (what is on disk, staged or not); if it is clean, `HEAD~1` with `HEAD` |
| `sedi <rev>`   | the commit (SHA, tag, branch, `HEAD~2`, …) with its first parent                                |
| `sedi a..b`    | commit `a` with commit `b`                                                                      |
| `sedi a...b`   | the merge base of `a` and `b` with `b`                                                          |
| `sedi a..`     | commit `a` with the working copy                                                                |

**Watching the workspace.** Whenever the new side is the working copy (`sedi` with no revision, or `sedi a..`),
SeDi keeps running, watches the project directory and shows the diff again each time a Java file changes. You can
leave it open next to your editor, or next to an AI agent that is editing the code, and see the semantic diff
update as you go. When you give concrete revisions (`sedi <rev>`, `sedi a..b`), there is nothing to watch: the
console prints the report and exits (unless you use `-i`), and the web page shows a fixed report.

## The web GUI

```
sedi --web            # the working copy, updated as you edit
sedi --web HEAD~3..   # HEAD~3 compared with the working copy, also updated as you edit
sedi --web v1.0..v1.1 # a fixed range
```

SeDi starts a local web server, prints its URL and opens it in your default browser. Use `--port <n>` to choose
the port (by default any free port is used). The server runs until you stop it with Ctrl+C. Every open page is
updated when the working copy changes.

The toolbar at the top of the page:

| Button          | Key | What it does                                                                                  |
|-----------------|-----|-----------------------------------------------------------------------------------------------|
| All             | `l` | All the changes by file; under each file its API, implementation, cosmetic and import changes |
| API             | `a` | Only the API changes                                                                          |
| 🔒 / 🔓         | `p` | All the API changes, or only the public API (public and protected types and members, interface members) |
| Implementation  | `i` | Only the implementation changes                                                               |
| + / −           | `+` `-` | Show or hide the whole source of added / removed types and members with the implementation changes; the badge says how many there are |
| Cosmetic        | `c` | Only the cosmetic changes                                                                     |
| Imports         | `m` | Only the changed imports                                                                      |
| Javadoc         | `d` | Javadoc shown: none, 3 lines around the changes, or whole                                     |
| Tests           | `t` | Show or hide the changes of the test sources (files under `src/test/`)                        |
| ?               | `h` | Help; a click on the toolbar brings the report back                                           |

The counts next to the buttons tell how many changes each level has. In the report:

- A click on the title of a file or of a category collapses or expands it (▾ collapses, ▸ expands).
- A click on a `… (N lines)` note shows the hidden unchanged lines.
- While you scroll, a line under the toolbar shows which file and element you are in; a click on it scrolls back to
  the start of that element. Resting the mouse on an element there shows its javadoc.
- Hovering a renamed name shows `old → new`.
- Hovering ⚠ or ⓘ lists how the change breaks or may break the clients of the API.
- Line markers: `+` added, `-` removed, `~` changed, `→` moved or renamed file.

Each page keeps its own view, so you can have several tabs open on different levels.

## The console (CLI)

```
sedi                  # the working copy: shows the diff, then watches and shows it again on every change
sedi HEAD~1           # prints the diff of the last commit and exits
sedi -i HEAD~1        # the same, but stays open for the keys below
sedi -l impl main..   # all levels down to implementation, main compared with the working copy, watching
```

Without `-i` and with a concrete revision, the report is printed to standard output and SeDi exits, so it can be
piped (`--color always` keeps the colors). In a terminal, the long-running modes show the report one page at a time
with a hint of the keys on the last line:

| Key     | What it does                                                                            |
|---------|-----------------------------------------------------------------------------------------|
| `a`     | Only the API changes                                                                    |
| `p`     | API changes: all, or only of the public and protected types and members                 |
| `i`     | Only the implementation changes                                                         |
| `+`/`-` | Show or hide the whole source of the added / removed types and members                  |
| `c`     | Only the cosmetic changes                                                               |
| `m`     | Only the changed imports                                                                |
| `d`     | Javadoc: none, 3 lines around changes, whole                                            |
| `t`     | Show or hide the changes of the tests                                                   |
| `l`     | All the changes by file and category                                                    |
| `h`     | Help; `Esc` goes back to the report                                                     |
| `q`     | Quit                                                                                    |
| other   | Next page of a long report                                                              |

Changes of files that arrive while you are reading a long report are shown once you have paged through it.

## Options

| Option                      | Meaning                                                                                   |
|-----------------------------|-------------------------------------------------------------------------------------------|
| `-d`, `--dir <dir>`         | Project directory (default: the current directory)                                        |
| `-w`, `--web`               | Show the diff in the browser                                                              |
| `--port <port>`             | Port of the web server (default: any free port)                                           |
| `-i`, `--interactive`       | Keep running after the diff of `<rev>` is shown and react to keys                         |
| `-l`, `--level <level>`     | Least important level to show: `api`, `impl`, `cosmetic` or `imports` (default: `api`); with `--web` the level shown at first |
| `-b`, `--body-context <n>`  | Most unchanged lines shown in a row inside a method body; `-1` shows the whole body       |
| `--javadoc-context <n>`     | Unchanged javadoc lines around a javadoc change; `-1` whole javadoc, `-2` none (default)  |
| `--show-deletions`          | Show the whole source of removed types and members with the implementation changes        |
| `--hide-additions`          | Do not show the whole source of added types and members                                   |
| `--hide-tests`              | Do not show the changes of the files under `src/test/`                                    |
| `--rename-score <percent>`  | Least similarity for git to pair a deleted and an added file as a rename (default: 50)    |
| `-cp`, `--classpath <path>` | Jars and class directories to compile against, instead of the dependencies of `pom.xml`   |
| `--sourcepath <path>`       | Source roots (default: `src/main/java` and `src/test/java` if present, else `--dir`)     |
| `--color <when>`            | `auto`, `always` or `never` (default: `auto`)                                             |
| `--no-line-numbers`         | Do not show the line numbers                                                              |
| `--theme <file>`            | Colors (default: `~/.config/sedi/theme.properties` if it exists); see `--help` for the roles |
| `--info`                    | Print the resolved class path                                                             |

## Demo

This repository has an orphan branch, `sedi-demo`. It holds a small separate Maven project with no history in
common with SeDi. Each commit shows one kind of change: names written differently (fully qualified names replaced
by static imports), local renames with one implementation change, and type renames. To try it, check it out in a separate
worktree and step through the commits:

```
git worktree add ../sedi-demo sedi-demo
cd ../sedi-demo
sedi -i HEAD~2        # one commit at a time; or sedi --web HEAD~2
sedi --web HEAD~4..   # everything since the first commit, updated as you edit
```

## Limitations

Java only; simple Maven projects (no multi-module builds, no JPMS); local and anonymous classes are not tracked as
elements; non-Java files are ignored.

## License

MIT, see [LICENSE](LICENSE). Third-party components are listed in [THIRD-PARTY-NOTICES.md](THIRD-PARTY-NOTICES.md).
