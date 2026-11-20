# Third-party notices

sedi itself is licensed under the MIT License (see [LICENSE](LICENSE)). It uses
and, in the `sedi.jar` built by `mvn package`, redistributes the third-party
components listed below. Each remains under its own license; the MIT License of
sedi does not apply to them.

## fleck

`lib/org/maraboo/fleck/` contains the jar of fleck, a small web UI library by
the author of sedi that is not published separately yet. It is
Copyright (c) 2026 Petr Hrebejk and licensed under the MIT License, on the same
terms as sedi (see [LICENSE](LICENSE)). It bundles Preact, listed below under
its own license.

## Fonts

Bundled in `src/main/resources/cz/hrebejk/sedi/web/fonts/`.

| Font | Copyright | License | License text |
|---|---|---|---|
| [JetBrains Mono](https://github.com/JetBrains/JetBrainsMono) (Regular, Bold) | Copyright 2020 The JetBrains Mono Project Authors | SIL Open Font License 1.1 | `fonts/JetBrainsMono-OFL.txt` |
| [Roboto](https://github.com/googlefonts/roboto) (latin 400, 500, 700) | Copyright Google LLC | Apache License 2.0 | `fonts/Roboto-LICENSE.txt` |

## JavaScript

Bundled in the `fleck` library that sedi depends on.

| Component | Copyright | License |
|---|---|---|
| [Preact](https://preactjs.com) and `preact/hooks` | Copyright (c) 2015-present Jason Miller | MIT |

## Java libraries

Runtime dependencies, including transitive ones, as resolved by Maven.

| Library | Version | License |
|---|---|---|
| [Eclipse JGit](https://www.eclipse.org/jgit/) (`org.eclipse.jgit`) | 7.8.0.202609011348-r | Eclipse Distribution License 1.0 (BSD-3-Clause) |
| [JavaEWAH](https://github.com/lemire/javaewah) | 1.2.3 | Apache License 2.0 |
| [picocli](https://picocli.info) | 4.7.7 | Apache License 2.0 |
| [Apache Maven](https://maven.apache.org) (`maven-resolver-provider`, `maven-model`, `maven-model-builder`, `maven-artifact`, `maven-builder-support`, `maven-repository-metadata`) | 3.9.6 | Apache License 2.0 |
| [Apache Maven Resolver](https://maven.apache.org/resolver/) (`api`, `spi`, `util`, `impl`, `named-locks`, `connector-basic`, `transport-file`, `transport-http`) | 1.9.18 | Apache License 2.0 |
| [Plexus Utils](https://github.com/codehaus-plexus/plexus-utils) | 3.5.1 | Apache License 2.0 |
| [Plexus Interpolation](https://github.com/codehaus-plexus/plexus-interpolation) | 1.26 | Apache License 2.0 |
| [Eclipse Sisu Inject](https://eclipse.dev/sisu/) (`org.eclipse.sisu.inject`) | 0.9.0.M2 | Eclipse Public License 1.0 |
| [javax.inject](https://github.com/javax-inject/javax-inject) | 1 | Apache License 2.0 |
| [Apache HttpComponents](https://hc.apache.org) (`httpclient`, `httpcore`) | 4.5.14, 4.4.16 | Apache License 2.0 |
| [Apache Commons Codec](https://commons.apache.org/proper/commons-codec/) | 1.22.1 | Apache License 2.0 |
| [Apache Commons Lang](https://commons.apache.org/proper/commons-lang/) | 3.12.0 | Apache License 2.0 |
| [SLF4J](https://www.slf4j.org) (`slf4j-api`, `slf4j-nop`) | 2.0.18 | MIT |
| SLF4J `jcl-over-slf4j` | 1.7.36 | Apache License 2.0 |

Test-only dependency, not redistributed: [JUnit](https://junit.org/junit4/) 4.13.2, Eclipse Public License 1.0.

The NOTICE files of the Apache-licensed libraries are merged into
`META-INF/NOTICE` in `sedi.jar`.

## License texts

- Apache License 2.0: https://www.apache.org/licenses/LICENSE-2.0
- SIL Open Font License 1.1: https://openfontlicense.org
- Eclipse Public License 1.0: https://www.eclipse.org/legal/epl-v10.html
- Eclipse Distribution License 1.0: https://www.eclipse.org/org/documents/edl-v10.php
- MIT License: https://opensource.org/license/mit
