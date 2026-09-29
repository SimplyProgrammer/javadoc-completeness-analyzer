# Javadoc Completeness Analyzer

A small standalone Java CLI for the project-specific Javadoc completeness metric.<br>
Mostly vibecoded with ChatGPT for presentation purposes.

## Metric implemented

### Classes
3 equally weighted requirements:
1. non-empty Javadoc description
2. `@since`
3. `@author`

### Methods
Requirements:
1. one `@param` for every declared parameter
2. `@return` for non-void methods
3. `@since`

Constructors are intentionally not included because the assignment's metric is
defined for methods/classes.

### Project-specific return fallback

By default, a non-empty method Javadoc description satisfies the return
requirement if an explicit `@return` tag is absent. This matches the requested
"Does something" fallback rule.

Use `--strict-return` if you later decide that only an explicit `@return`
should count.

This fallback is deliberately a project metric rule, not a statement about
what standard Javadoc considers equivalent to `@return`.

## Multimodule/nested projects

The analyzer recursively discovers `.java` files.
It does not require a Maven reactor build and therefore also works with nested
projects.

It excludes:
- `.git`, `.idea`, `.settings`
- `target`, `build`, `out`, `bin`
- `node_modules`
- test source trees by default

The nearest ancestor containing `pom.xml` is recorded as the module in the CSV.
If no POM is found, the module is `.`.

## Build

Requires JDK 17+ and Maven.

```bash
mvn clean package
```

This creates an executable shaded JAR in `target/`.

## Run

```bash
java -jar target/javadoc-completeness-analyzer-1.0.0.jar /path/to/SerialX \
  --output javadoc-completeness.csv
```

Useful options:

```text
--include-tests
--strict-return
--include-overridden
--output <file>
--help
```

## CSV

The CSV contains one row per class/method and includes:
- module
- source file
- line
- element
- required items
- achieved items
- description
- number of matching @param tags
- @return
- @since
- @author
- whether @return was explicit or supplied by the description fallback
- percentage

The overall project percentage is weighted by documentation requirements, not
by simply averaging class/method percentages. This avoids a zero-parameter
method and a 10-parameter method having the same weight.

## Example

For:

```java
/**
 * Does something.
 *
 * @param foo ...
 * @param bar ...
 * @return ...
 * @since 1.3
 */
public int calculate(int foo, int bar) { ... }
```

the method receives 4/4 = 100%.

For:

```java
/**
 * Does something.
 */
public int calculate(int foo, int bar) { ... }
```

with the default fallback it receives 1/4 = 25%:
- foo @param: missing
- bar @param: missing
- @return: satisfied by description fallback
- @since: missing

With `--strict-return`, the same method receives 0/4.

