# Bean Copy Companion

Generates a real, compiling object-copy method between two Java or
Kotlin classes. Select exactly two class files in the Project view,
right-click → **Generate Bean Copy Method**, pick the copy direction,
and get a new copier file with fields matched by name and type.

## Why it exists

The closest existing competitor in this space, **Simple Object Copy**
(32,992 downloads, PAID, JetBrains Marketplace id 18151), has real,
verbatim user complaints:

- `java.lang.NullPointerException: Cannot invoke "com.intellij.psi.PsiMethod.getProject()" because "psiMethod" is null` — a real crash, with stack trace, from a real user.
- *"不支持kotlin。。。"* — "Doesn't support Kotlin..."
- *"直接报错不能用，真是xswl，就这还收费"* — "Straight up errors, unusable, and this still costs money"
- Abandoned since 2022-05 (no release in 4+ years), no bugtracker link on the listing.

62% of its reviews are ≤3 stars. The positive reviews confirm the core
function is genuinely wanted (one user describes saving a full day of
work migrating off a hand-rolled `beancopy` utility) — the complaints
are about *how* it's built, not *whether* the feature is useful.

## Why built this way

- **Real Kotlin support, not an afterthought.** Fields are read through
  [`PsiClass.getFields()`](https://plugins.jetbrains.com/docs/intellij/), which works identically for a Java class and a
  Kotlin light class — `data class` properties, `val`/`var`, all
  resolve through the exact same code path Java fields do. Kotlin's
  own getter/setter naming quirk for `is`-prefixed Boolean properties
  (`var isActive: Boolean` compiles to `isActive()`/`setActive(v)`, not
  `getIsActive()`/`setIsActive(v)`) is checked for explicitly, not
  assumed away.
- **Never a crash, never silently wrong.** Every generated file is
  parsed against a throwaway, never-persisted in-memory PSI copy and
  checked for syntax errors *before* anything touches disk — the same
  discipline already proven in this catalog's Test Scaffold Companion
  and Refactor Simulator. A field this plugin can't safely map (type
  mismatch, no matching name, no way to read or write it) becomes an
  honest `// TODO(bean-copy): ...` comment in the generated file, never
  a guess and never a crash.
- **Correct Kotlin-vs-Java call syntax, not just "compiles once".** A
  Kotlin property has no callable `getFoo()`/`setFoo(v)` symbol from
  Kotlin-to-Kotlin call sites — only from Java, or from Kotlin calling
  into a *Java* class. This plugin tracks the language of the source
  class, the target class, and the generated file independently, and
  picks property syntax (`x.foo`, `x.foo = v`) or method-call syntax
  (`x.getFoo()`, `x.setFoo(v)`) accordingly. Getting this wrong is
  exactly the class of bug that produces code which looks right but
  doesn't compile.
- **Generated language always matches the target class's language.**
  A Java target gets a Java copier; a Kotlin target gets a Kotlin
  extension function. Handing a pure-Java project a `.kt` file it has
  no toolchain configured for would repeat the same "doesn't build"
  complaint this plugin exists to fix.
- **Beyond feature parity with the competitor:**
  - Java 17+ `record` targets (canonical constructor, detected via the
    same generic "constructor with the most parameters" logic used for
    Kotlin `data class` — no record-specific code needed).
  - Lombok `@Builder` targets, detected from the plain annotation text
    — no Lombok/Kotlin compiler dependency required, and it degrades
    gracefully (falls back to setters/constructor) if the Lombok IDE
    plugin isn't installed and the generated builder class isn't
    visible yet.
  - Primitive/boxed type equivalence (`int` ↔ `Integer`), so a Java
    entity's `int id` copies cleanly into a Kotlin DTO's boxed `Int?`.
- **100% local.** No network call, no account, no telemetry. All PSI
  analysis runs off the EDT; only the final file write touches it, and
  only after validation already passed.

## Usage

1. In the Project view, select exactly two class files (Java or
   Kotlin, any combination).
2. Right-click → **Generate Bean Copy Method**.
3. Pick the copy direction in the dialog that appears.
4. A new `<Source>To<Target>Copier.java`/`.kt` file is created next to
   the target class. Any field the plugin couldn't confidently map is
   left as a `// TODO(bean-copy): ...` comment instead of guessed at.

## Known limitations (v0.1.0)

- Only fields with a real backing field are considered — a Kotlin
  computed property (`val foo get() = ...`) has no backing field and
  is not read as a source value.
- Constructor arguments are always positional, never Kotlin named
  arguments — simpler and unambiguous across both languages, at the
  cost of slightly less readable generated code for long constructors.
- Placeholder values for an unmapped constructor parameter (`null`,
  `0`, `false`, `""`) are only guaranteed syntactically valid, not
  guaranteed to type-check — e.g. `null` against a non-nullable Kotlin
  type. The adjacent `TODO` comment is there specifically to make that
  visible, same documented scope as this catalog's other generators.

## Enterprise / Team Licensing

Need enterprise features, custom rules, or team licensing? Contact us
at **gaphunterlabs@gmail.com**.

## Development

```
./gradlew test           # unit tests
./gradlew buildPlugin    # generates build/distributions/*.zip
./gradlew verifyPlugin   # checks compatibility against real IDEs
```

## License

Apache-2.0. See `LICENSE`.
