<!-- This file is rendered by https://github.com/BlvckBytes/readme_helper -->

# BBConfigMapper

![build](https://github.com/BlvckBytes/BBConfigMapper/actions/workflows/build.yml/badge.svg)
[![coverage](https://codecov.io/gh/BlvckBytes/BBConfigMapper/branch/main/graph/badge.svg?token=SDSITT1P18)](https://codecov.io/gh/BlvckBytes/BBConfigMapper)

```xml
  <dependency>
    <groupId>de.alphaomega-it.bbconfigmapper</groupId>
    <artifactId>BBConfigMapper</artifactId>
    <version>1.0</version>
  </dependency>
```

An object mapper for key-based configurations making use of [GPEEE](https://github.com/BlvckBytes/GPEEE).

## Table of Contents
- [Merging](#merging)
- [Expression Evaluation](#expression-evaluation)
  - [Same Key Marking](#same-key-marking)
  - [Parent Key Marking](#parent-key-marking)
- [Value Interpretation](#value-interpretation)
- [Comments](#comments)
- [Key Extension](#key-extension)

## Merging

Mapping values can be merged by making use of the merge key (`<<`). This feature only makes sense in combination with
yaml anchors, to extend one section by another one. Missing keys are extended deeply, while scalar values are - if they're
declared above the merge key - overridden, otherwise ignored.

An example of merging would be the following:

```yaml
sectionA: &sectionA
  title: 'Welcome to section a'
  color: green
  width: 100
  settings:
    opacity: .5
    animated: false

sectionB:
  <<: *sectionA
  title: 'Welcome to section b'
  settings:
    animated: true
```

Section B "inherits" `color` as well as `width`, while it's `title` is being overridden. This inheritance works deeply, which
is why the `settings` section of B can override just the `animated` flag, while still inheriting the `opacity`. This way, a
template can be made use of while still being able to customize certain properties.

## Expression Evaluation

If a value should be parsed into a *Program Expression* instead of being unwrapped into a primitive, either **it's** key or a
**parent** key needs to be marked with the trailing *expression marker*. The value of this marker is up to the user of this library,
but usually ends up being a dollar sign (`$`).

### Same Key Marking

A single value is being marked as an expression:

```yaml
myKey$: '5 + 3 - 20 * .4'
```

### Parent Key Marking

If a parent key is marked, all children will be parsed as expressions, without having to be marked separately again.

```yaml
myParentKey$:
  a: '5 + 3 - 20 * .4'
  b: 'my_variable & ", hello world!"'
```

This is also true for non-scalar values like lists:

```yaml
myListKey$:
  - '5 + 3 - 20 * .4'
  - 'my_variable & ", hello world!"'
```

## Value Interpretation

Values are interpreted as the type that the caller requires by making use of `GPEEE`s [IValueInterpreter](https://github.com/BlvckBytes/GPEEE/blob/main/src/main/java/me/blvckbytes/gpeee/interpreter/IValueInterpreter.java). Types
can be either the available scalars, or lists of scalars, or maps with scalar keys/values. Always check which interpretation happens at the documentation
of the actual implementation which makes use of this library. If required, raw objects (unwrapped YAML nodes) can also be fetched from a configuration.

## Comments

Due to the use of a relatively recent version of snakeyaml, comments are parsed into the AST as well and thus persist
not only in their content, but also their exact positioning. The header comment, as it's often added for decorative purposes,
is detected separately and is being defined as the first n lines of block comments without a newline, separated by a newline
from the rest of the file. It's persisted separately, in order to allow for simpler key extension without messing up the header's
position.

## Key Extension

A *YamlConfig* supports the extension of missing keys from another instance, which is a way to migrate existing configuration files
to a newer version by adding new key-value pairs. Existing sections are extended, missing sections are added. Keys are never deleted,
as that could possibly delete still needed configuration information for the user.
