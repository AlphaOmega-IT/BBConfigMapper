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

package me.blvckbytes.bbconfigmapper;

import me.blvckbytes.gpeee.IExpressionEvaluator;
import me.blvckbytes.gpeee.interpreter.IEvaluationEnvironment;
import me.blvckbytes.gpeee.parser.expression.AExpression;
import org.jetbrains.annotations.Nullable;

import java.util.*;

public class ConfigValue implements IEvaluable {

  protected final @Nullable Object value;
  private final @Nullable IExpressionEvaluator evaluator;

  public ConfigValue(
		final @Nullable Object value,
		final @Nullable IExpressionEvaluator evaluator
	) {
    this.value = value;
    this.evaluator = evaluator;
  }

  @Override
  @SuppressWarnings("unchecked")
  public <T> T asScalar(
		final ScalarType type,
		final IEvaluationEnvironment env
	) {
    return (T) this.interpret(value, type.getType(), null, env);
  }

  @Override
  @SuppressWarnings("unchecked")
  public <T> List<T> asList(
		final ScalarType type,
		final IEvaluationEnvironment env
	) {
    return (List<T>) this.interpret(value, List.class, new ScalarType[] { type }, env);
  }

  @Override
  @SuppressWarnings("unchecked")
  public <T> Set<T> asSet(
		final ScalarType type,
		final IEvaluationEnvironment env
	) {
    return (Set<T>) this.interpret(value, Set.class, new ScalarType[] { type }, env);
  }

  @Override
  public Object asRawObject(
		final IEvaluationEnvironment env
	) {
    if (value instanceof AExpression aExpressionValue && this.evaluator != null)
      return this.evaluator.evaluateExpression(aExpressionValue, env);
    return value;
  }

  @Override
  @SuppressWarnings("unchecked")
  public <T, U> Map<T, U> asMap(
		final ScalarType key,
		final ScalarType value,
		final IEvaluationEnvironment env
	) {
    return (Map<T, U>) this.interpret(value, Map.class, new ScalarType[] { key, value }, env);
  }

  /**
   * Interpret a nullable input value as a scalar configuration value by
   * evaluating applicable expressions first and then using the value
   * interpreter to interpret the result as the required data type
   * @param input Nullable value input
   * @param type Required scalar type
   * @param env Environment used to interpret and evaluate with
   * @return Guaranteed non-Null value of the requested type
   */
  @SuppressWarnings("unchecked")
  protected <T> T interpretScalar(
		@Nullable Object input,
		final ScalarType type,
		final IEvaluationEnvironment env
	) {
    Class<?> typeClass = type.getType();

    if (typeClass.isInstance(input))
      return (T) input;

    // The input is an expression which needs to be evaluated first
    if (input instanceof AExpression aExpressionInput && this.evaluator != null)
      input = this.evaluator.evaluateExpression(aExpressionInput, env);

    return (T) type.getInterpreter().apply(input, env);
  }

  // FIXME: The following method could really use some attention...

  /**
   * Interpret a nullable input value as a scalar, list or map configuration
   * value by making use of {@link #interpretScalar} to either convert to a scalar
   * value or to convert list items / map values to the requested scalar type before responding.
   * @param input Nullable value input
   * @param type Required result type
   * @param genericTypes Scalar types of a list/map (null for scalar requests)
   * @param env Environment used to interpret and evaluate with
   * @return Guaranteed non-null value of the requested type
   */
  @SuppressWarnings("unchecked")
  private<T> T interpret(
		@Nullable Object input,
		final Class<T> type,
		final @Nullable ScalarType[] genericTypes,
		final IEvaluationEnvironment env) {

    if (input instanceof AExpression aExpressionInput && this.evaluator != null)
      input = this.evaluator.evaluateExpression(aExpressionInput, env);

    if (type == List.class || type == Set.class) {

      if (genericTypes == null || genericTypes.length < 1 || genericTypes[0] == null)
        throw new IllegalStateException("Cannot require a List without specifying a generic type");

      Collection<?> items;

      // Turn a scalar value into a list, if applicable
      if (!(input instanceof Collection<?> collection))
        items = Collections.singletonList(interpretScalar(input, genericTypes[0], env));
      else
        items = collection;

      Collection<Object> results;

      if (type == List.class)
        results = new ArrayList<>();
      else
        results = new HashSet<>();

      // Interpret each item as the requested generic type
      for (Object item : items) {

        // FIXME: This seems hella repetitive to #interpretScalar

        // Expression result collections are flattened into the return result collection, if applicable
        if (item instanceof AExpression aExpressionItem && this.evaluator != null) {
          Object result = this.evaluator.evaluateExpression(aExpressionItem, env);

          if (result instanceof Collection<?> collectionResult) {
            for (final Object subItem : collectionResult)
              results.add(genericTypes[0].getInterpreter().apply(subItem, env));

            continue;
          }

          results.add(genericTypes[0].getInterpreter().apply(result, env));
          continue;
        }

        results.add(this.interpretScalar(item, genericTypes[0], env));
      }

      return (T) results;
    }

    if (type == Map.class) {

      if (genericTypes == null || genericTypes.length < 2 || genericTypes[0] == null || genericTypes[1] == null) {
        throw new IllegalStateException("Cannot require a Map without specifying generic types");
      }

      // Null will just be the empty map
      if (input == null)
        return (T) new HashMap<>();

      if (!(input instanceof Map<?, ?> mapInput))
        throw new IllegalStateException("Cannot transform type " + input.getClass().getName() + " into a map");

			Map<Object, Object> results = new HashMap<>();

      // Interpret each value as the requested generic type
      for (final Map.Entry<?, ?> entry : mapInput.entrySet())
        results.put(interpretScalar(entry.getKey(), genericTypes[0], env), interpretScalar(entry.getValue(), genericTypes[1], env));

      return (T) results;
    }

    final ScalarType scalarType = ScalarType.fromClass(type);
    if (scalarType == null)
      throw new IllegalStateException("Unknown scalar type provided: " + type);

    return this.interpretScalar(input, scalarType, env);
  }

  @Override
  public String toString() {
    return "ConfigValue{" +
      "value=" + value +
      '}';
  }
}