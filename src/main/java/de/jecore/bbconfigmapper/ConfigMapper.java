/*
 * MIT License
 *
 * Copyright (c) 2023 BlvckBytes
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in all
 * copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 */

package de.jecore.bbconfigmapper;

import de.jecore.bbconfigmapper.logging.DebugLogSource;
import de.jecore.bbconfigmapper.sections.CSAlways;
import de.jecore.bbconfigmapper.sections.CSIgnore;
import de.jecore.bbconfigmapper.sections.CSInlined;
import de.jecore.bbconfigmapper.sections.IConfigSection;
import me.blvckbytes.gpeee.GPEEE;
import me.blvckbytes.gpeee.IExpressionEvaluator;
import me.blvckbytes.gpeee.Tuple;
import org.jetbrains.annotations.Nullable;

import java.lang.reflect.*;
import java.util.*;
import java.util.logging.Level;
import java.util.logging.Logger;

public class ConfigMapper implements IConfigMapper {

  private final IConfig config;

  private final Logger logger;
  private final IExpressionEvaluator evaluator;
  private final @Nullable IValueConverterRegistry converterRegistry;

  /**
   * Create a new config reader on a {@link IConfig}
   * @param config Configuration to read from
   * @param logger Logger to use for logging events
   * @param evaluator Expression evaluator instance to use when parsing expressions
   * @param converterRegistry Optional registry of custom value converters
   */
  public ConfigMapper(
    final IConfig config,
    final Logger logger,
    final IExpressionEvaluator evaluator,
    final @Nullable IValueConverterRegistry converterRegistry
  ) {
    this.config = config;
    this.logger = logger;
    this.evaluator = evaluator;
    this.converterRegistry = converterRegistry;
  }

  @Override
  public IConfig getConfig() {
    return this.config;
  }

  @Override
  public <T extends IConfigSection> T mapSection(
		final @Nullable String root,
		final Class<T> type
	) throws Exception {
    logger.log(Level.FINEST, () -> DebugLogSource.MAPPER + "At the entry point of mapping path=" + root + " to type=" + type);
    return mapSectionSub(root, null, type);
  }

  /**
   * Recursive, parameterized subroutine for creating an empty config section and then assigning values
   * to it's mapped fields automatically, based on their names and types by making use of
   * {@link #resolveFieldValue}. Fields of type object will be decided at
   * runtime, null values may get a default value assigned and incompatible values are tried to be
   * converted before invoking the field setter. If a value still is null after all calls, the field
   * remains unchanged.
   * @param root Root node of this section (null means config root)
   * @param source Alternative value source (map instead of config lookup)
   * @param type Class of the config section to instantiate
   * @return Instantiated class with mapped fields
   */
  private <T extends IConfigSection> T mapSectionSub(
		final @Nullable String root,
		final @Nullable Map<?, ?> source,
		final Class<T> type
	) throws Exception {
      logger.log(Level.FINEST, () -> DebugLogSource.MAPPER + "At the subroutine of mapping path=" + root + " to type=" + type + " using source=" + source);
      T instance = findDefaultConstructor(type).newInstance();

      Tuple<List<Field>, Iterator<Field>> fields = findApplicableFields(type);

      while (fields.b.hasNext()) {
        Field f = fields.b.next();
        String fName = f.getName();

        try {
          Class<?> fieldType = f.getType();

          Class<?> finalFieldType = fieldType;
          logger.log(Level.FINEST, () -> DebugLogSource.MAPPER + "Processing field=" + fName + " of type=" + finalFieldType);

          // Object fields trigger a call to runtime decide their type based on previous fields
          if (fieldType == Object.class) {
            Class<?> decidedType = instance.runtimeDecide(fName);

            if (decidedType == null)
              throw new MappingError("Requesting plain objects is disallowed");

            logger.log(Level.FINEST, () -> DebugLogSource.MAPPER + "Called runtimeDecide on field=" + fName + ", yielded type=" + decidedType);

            fieldType = decidedType;
          }

          FValueConverter converter = null;
          if (this.converterRegistry != null) {
            Class<?> requiredType = this.converterRegistry.getRequiredTypeFor(fieldType);
            converter = this.converterRegistry.getConverterFor(fieldType);

            if (requiredType != null && converter != null) {
              logger.log(Level.FINEST, () -> DebugLogSource.MAPPER + "Using custom converter for type=" + finalFieldType);

              fieldType = requiredType;
            }
          }

          Object value = resolveFieldValue(root, source, f, fieldType);

          if (converter != null)
            value = converter.apply(value, evaluator);

          // Couldn't resolve a non-null value, try to ask for a default value
          if (value == null)
            value = instance.defaultFor(f);

          // Only set if the value isn't null, as the default constructor
          // might have already assigned some default value earlier
          if (value == null)
            continue;

          f.set(instance, value);
        } catch (MappingError error) {
          IllegalStateException exception = new IllegalStateException(error.getMessage() + " (at path '" + joinPaths(root, fName) + "')");
          exception.addSuppressed(error);
          throw exception;
        }
      }

      // This instance won't have any more changes applied to it, call with the list of affected fields
      instance.afterParsing(fields.a);

      return instance;
  }

  /**
   * Find all fields of a class which automated mapping applies to, including inherited fields
   * @param type Class to look through
   * @return A tuple containing the unsorted list as well as an iterator of fields in
   *         the order that fields of type Object come after known types
   */
  private Tuple<List<Field>, Iterator<Field>> findApplicableFields(
		final Class<?> type
	) {
    List<Field> affectedFields = new ArrayList<>();

    // Walk the class' hierarchy
    Class<?> c = type;
    while (c != Object.class) {
      for (Field f : c.getDeclaredFields()) {
        if (Modifier.isStatic(f.getModifiers()))
          continue;

        if (f.isAnnotationPresent(CSIgnore.class))
          continue;

        if (f.getType() == type)
          throw new IllegalStateException("Sections cannot use self-referencing fields (" + type + ", " + f.getName() + ")");

        f.setAccessible(true);
        affectedFields.add(f);
      }
      c = c.getSuperclass();
    }

    Iterator<Field> fieldI = affectedFields.stream()
      .sorted((a, b) -> {
        if (a.getType() == Object.class && b.getType() == Object.class)
          return 0;

        // Objects are "greater", so they'll be last when sorting ASC
        return a.getType() == Object.class ? 1 : -1;
      }).iterator();

    return new Tuple<>(affectedFields, fieldI);
  }

  /**
   * Resolve a path by either looking it up in the config itself or by resolving it
   * from a previous config response which occurred in the form of a map
   * @param path Path to resolve
   * @param source Map to resolve from instead of querying the config, optional
   * @return Resolved value, null if either the value was null or if it wasn't available
   */
  private @Nullable Object resolvePath(
		String path,
		@Nullable Map<?, ?> source
	) {
    // No object to look in specified, retrieve this path from the config
    if (source == null) {
      logger.log(Level.FINEST, () -> DebugLogSource.MAPPER + "No resolving source provided, looking up in config");
      return config.get(path);
    }

    logger.log(Level.FINEST, () -> DebugLogSource.MAPPER + "Resolving source provided, walking map");

    int dotIndex = path.indexOf('.');

    while (! path.isEmpty()) {
      String key = dotIndex < 0 ? path : path.substring(0, dotIndex);

      if (key.isBlank())
        throw new MappingError("Cannot resolve a blank key");

      path = dotIndex < 0 ? "" : path.substring(dotIndex + 1);
      dotIndex = path.indexOf('.');

      Object value = source.get(key);

      // Last iteration, respond with the current value
      if (path.isEmpty()) {
        logger.log(Level.FINEST, () -> DebugLogSource.MAPPER + "Walk ended, returning value=" + value);
        return value;
      }

      // Reached a dead end and not yet at the last iteration
      if (!(value instanceof Map)) {
        logger.log(Level.FINEST, () -> DebugLogSource.MAPPER + "Path part key=" + key + " wasn't a map, returning null");
        return null;
      }

      // Swap out the current map reference to navigate forwards
      source = (Map<?, ?>) value;
    }

    // Path was blank, which means root
    return source;
  }

  /**
   * Tries to convert the input object to the specified type, by either stringifying,
   * wrapping the value as an {@link IEvaluable} or by parsing a {@link IConfigSection}
   * if the input is of type map and returning null otherwise. Unsupported types throw.
   * @param input Input object to convert
   * @param type Type to convert to
   */
  private @Nullable Object convertType(
		@Nullable Object input,
		Class<?> type
	) throws Exception {

    Class<?> finalType = type;
    logger.log(Level.FINEST, () -> DebugLogSource.MAPPER + "Trying to convert a value to type: " + finalType);

    if (input == null) {
      logger.log(Level.FINEST, () -> DebugLogSource.MAPPER + "Is null, returning null");
      return null;
    }

    FValueConverter converter = null;
    if (this.converterRegistry != null) {
      Class<?> requiredType = this.converterRegistry.getRequiredTypeFor(type);
      converter = this.converterRegistry.getConverterFor(type);

      if (requiredType != null && converter != null) {
        logger.log(Level.FINEST, () -> DebugLogSource.MAPPER + "Using custom converter for type=" + finalType);

        type = requiredType;
      }
    }

    // Requested plain object
    if (type == Object.class) {
      if (converter != null)
        input = converter.apply(input, this.evaluator);

      return input;
    }

    if (IConfigSection.class.isAssignableFrom(type)) {
      logger.log(Level.FINEST, () -> DebugLogSource.MAPPER + "Parsing value as config-section");

      if (!(input instanceof Map<?, ?>)) {
        logger.log(Level.FINEST, () -> DebugLogSource.MAPPER + "Value was null, falling back on empty section");
        input = new HashMap<>();
      }

      Object value = mapSectionSub(null, (Map<?, ?>) input, type.asSubclass(IConfigSection.class));

      if (converter != null)
        value = converter.apply(value, evaluator);

      return value;
    }

    logger.log(Level.FINEST, () -> DebugLogSource.MAPPER + "Wrapping value in evaluable");

    IEvaluable evaluable = new ConfigValue(input, this.evaluator);

    if (type == IEvaluable.class) {
      logger.log(Level.FINEST, () -> DebugLogSource.MAPPER + "Returning evaluable");
      return evaluable;
    }

    if (type == String.class)
      return evaluable.<String>asScalar(ScalarType.STRING, GPEEE.EMPTY_ENVIRONMENT);

    if (type == int.class || type == Integer.class)
      return evaluable.<Long>asScalar(ScalarType.LONG, GPEEE.EMPTY_ENVIRONMENT).intValue();

    if (type == long.class || type == Long.class)
      return evaluable.<Long>asScalar(ScalarType.LONG, GPEEE.EMPTY_ENVIRONMENT);

    if (type == double.class || type == Double.class)
      return evaluable.<Double>asScalar(ScalarType.DOUBLE, GPEEE.EMPTY_ENVIRONMENT);

    if (type == float.class || type == Float.class)
      return evaluable.<Double>asScalar(ScalarType.DOUBLE, GPEEE.EMPTY_ENVIRONMENT).floatValue();

    if (type == boolean.class || type == Boolean.class)
      return evaluable.<Boolean>asScalar(ScalarType.BOOLEAN, GPEEE.EMPTY_ENVIRONMENT);

    throw new MappingError("Unsupported type specified: " + type);
  }

  /**
   * Handles resolving a field of type map based on a previously looked up value
   * @param f Map field which has to be assigned to
   * @param value Previously looked up value
   * @return Value to assign to the field
   */
  private Object handleResolveMapField(
		final Field f,
		final Object value
	) throws Exception {
    logger.log(Level.FINEST, () -> DebugLogSource.MAPPER + "Resolving map field");

    List<Class<?>> genericTypes = getGenericTypes(f);
  //assert genericTypes != null && genericTypes.size() == 2;

    Map<Object, Object> result = new HashMap<>();

    if (!(value instanceof Map<?, ?> mapValue)) {
      logger.log(Level.FINEST, () -> DebugLogSource.MAPPER + "Not a map, returning empty map");
      return result;
    }

    logger.log(Level.FINEST, () -> DebugLogSource.MAPPER + "Mapping values individually");

    for (
			final Map.Entry<?, ?> entry : mapValue.entrySet()
		) {
      final Object resultKey;

      try {
        resultKey = this.convertType(entry.getKey(), genericTypes.get(0));
      } catch (final MappingError error) {
        throw new MappingError(error.getMessage() + " (at the key of a map)");
      }

      Object resultValue;
      try {
        resultValue = this.convertType(entry.getValue(), genericTypes.get(1));
      } catch (MappingError error) {

        throw new MappingError(error.getMessage() + " (at value for key=" + resultKey + " of a map)");
      }

      result.put(resultKey, resultValue);
    }

    return result;
  }

  /**
   * Handles resolving a field of type list based on a previously looked up value
   * @param f List field which has to be assigned to
   * @param value Previously looked up value
   * @return Value to assign to the field
   */
  private Object handleResolveListField(
		final Field f,
		final Object value
	) throws Exception {
    logger.log(Level.FINEST, () -> DebugLogSource.MAPPER + "Resolving list field");

    List<Class<?>> genericTypes = getGenericTypes(f);
  //assert genericTypes != null && genericTypes.size() == 1;

    List<Object> result = new ArrayList<>();

    if (!(value instanceof final List<?> list)) {
      logger.log(Level.FINEST, () -> DebugLogSource.MAPPER + "Not a list, returning empty list");
      return result;
    }

		for (int i = 0; i < list.size(); i++) {
      Object itemValue;
      try {
        itemValue = convertType(list.get(i), genericTypes.get(0));
      } catch (final MappingError error) {
        throw new MappingError(error.getMessage() + " (at index " + i + " of a list)");
      }

      result.add(itemValue);
    }

    return result;
  }

  /**
   * Handles resolving a field of type array based on a previously looked up value
   * @param f List field which has to be assigned to
   * @param value Previously looked up value
   * @return Value to assign to the field
   */
  private Object handleResolveArrayField(
		final Field f,
		final Object value
	) throws Exception {
    logger.log(Level.FINEST, () -> DebugLogSource.MAPPER + "Resolving array field");

    Class<?> arrayType = f.getType().getComponentType();

    if (!(value instanceof final List<?> list)) {
      logger.log(Level.FINEST, () -> DebugLogSource.MAPPER + "Not a list, returning empty array");
      return Array.newInstance(arrayType, 0);
    }

		Object array = Array.newInstance(arrayType, list.size());

    for (int i = 0; i < list.size(); i++) {
      Object itemValue;
      try {
        itemValue = convertType(list.get(i), arrayType);
      } catch (MappingError error) {
        throw new MappingError(error.getMessage() + " (at index " + i + " of an array)");
      }

      Array.set(array, i, itemValue);
    }

    return array;
  }

  /**
   * Tries to resolve a field's value based on it's type, it's annotations, it's name and
   * the source (either a path or a source map).
   * @param root Root node of this section (null means config root)
   * @param source Map to resolve from instead of querying the config, optional
   * @param f Field which has to be assigned to
   * @return Value to be assigned to the field
   */
  private @Nullable Object resolveFieldValue(
		final @Nullable String root,
		final @Nullable Map<?, ?> source,
		final Field f,
		final Class<?> type
	) throws Exception {
    String path = f.isAnnotationPresent(CSInlined.class) ? root : joinPaths(root, f.getName());
    boolean always = f.isAnnotationPresent(CSAlways.class) || f.getDeclaringClass().isAnnotationPresent(CSAlways.class);

    logger.log(Level.FINEST, () -> DebugLogSource.MAPPER + "Resolving value for field=" + f.getName() + " at path=" + path + " using source=" + source);

    final Object value = this.resolvePath(path, source);

    // It's not marked as always and the current path doesn't exist: return null
    if (!always && value == null) {
      logger.log(Level.FINEST, () -> DebugLogSource.MAPPER + "Returning null for absent path");
      return null;
    }

    if (IConfigSection.class.isAssignableFrom(type)) {
      logger.log(Level.FINEST, () -> DebugLogSource.MAPPER + "Type is of another section");
      return mapSectionSub(path, source, type.asSubclass(IConfigSection.class));
    }

    logger.log(Level.FINEST, () -> DebugLogSource.MAPPER + "Resolving path value as plain object");

    // Requested plain object
    if (type == Object.class)
      return value;

    logger.log(Level.FINEST, () -> DebugLogSource.MAPPER + "Resolved value=" + value);

    if (Map.class.isAssignableFrom(type))
      return handleResolveMapField(f, value);

    if (List.class.isAssignableFrom(type))
      return handleResolveListField(f, value);

    if (type.isArray())
      return handleResolveArrayField(f, value);

    return convertType(value, type);
  }

  /**
   * Join two config paths and account for all possible cases
   * @param a Path A (or null/empty)
   * @param b Path B (or null/empty)
   * @return Path A joined with path B
   */
  private String joinPaths(
		final @Nullable String a,
		final @Nullable String b
	) {
    if (a == null || a.isBlank())
      return b;

    if (b == null || b.isBlank())
      return a;

    if (a.endsWith(".") && b.startsWith("."))
      return a + b.substring(1);

    if (a.endsWith(".") || b.startsWith("."))
      return a + b;

    return a + "." + b;
  }

  /**
   * Find the default constructor of a class (no parameters required to instantiate it)
   * or throw a runtime exception otherwise.
   * @param type Type of the target class
   * @return Default constructor
   */
  private<T> Constructor<T> findDefaultConstructor(
		final Class<T> type
	) {
    try {
      Constructor<T> ctor = type.getDeclaredConstructor();
      ctor.setAccessible(true);
      return ctor;
    } catch (NoSuchMethodException e) {
      throw new IllegalStateException("Please specify an empty default constructor on " + type);
    }
  }

  /**
   * Get a list of generic types a field's type declares
   * @param f Target field
   * @return List of generic fields, null if the field's type is not generic
   */
  private @Nullable List<Class<?>> getGenericTypes(
		final Field f
	) {
    Type genericType = f.getGenericType();

    if (!(genericType instanceof ParameterizedType parameterizedType))
      return null;

    Type[] types = parameterizedType.getActualTypeArguments();
    List<Class<?>> result = new ArrayList<>();

    for (Type type : types)
      result.add(unwrapType(type));

    return result;
  }

  /**
   * Attempts to unwrap a given type to it's raw type class
   * @param type Type to unwrap
   * @return Unwrapped type
   */
  private Class<?> unwrapType(
		final Type type
	) {
    if (type instanceof Class<?> clazzType)
      return clazzType;

    if (type instanceof ParameterizedType parameterizedType)
      return this.unwrapType(parameterizedType.getRawType());

    throw new MappingError("Cannot unwrap type of class=" + type.getClass());
  }
}