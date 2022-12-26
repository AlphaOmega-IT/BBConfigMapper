package me.blvckbytes.bbconfigmapper;

import me.blvckbytes.gpeee.GPEEE;
import me.blvckbytes.gpeee.IExpressionEvaluator;
import me.blvckbytes.gpeee.logging.ILogger;
import me.blvckbytes.gpeee.logging.NullLogger;
import me.blvckbytes.gpeee.parser.expression.AExpression;
import org.jetbrains.annotations.Nullable;
import org.junit.jupiter.api.function.Executable;

import java.io.FileNotFoundException;
import java.io.FileReader;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

public class TestHelper {

  private final IExpressionEvaluator evaluator;
  private final String expressionMarkerSuffix;
  private final ILogger logger;

  public TestHelper() {
    this.expressionMarkerSuffix = "$";
    this.logger = new NullLogger();
    this.evaluator = new GPEEE(logger);
  }

  /**
   * Create a new config instance and load it's contents from a yaml file
   * @param fileName Input file within the resources folder, null to not load at all
   * @return Loaded yaml configuration instance
   */
  public YamlConfig makeConfig(@Nullable String fileName) throws FileNotFoundException {
    YamlConfig config = new YamlConfig(this.evaluator, this.logger, this.expressionMarkerSuffix);

    if (fileName != null)
      config.load(new FileReader("src/test/resources/" + fileName));

    return config;
  }

  /**
   * Assert that a config value is an expression and that it evaluates to the expected value
   * @param expected Expected expression value
   * @param expression Expression to check
   */
  public void assertExpression(Object expected, Object expression) {
    assertTrue(expression instanceof AExpression);
    assertEquals(expected, this.evaluator.evaluateExpression((AExpression) expression, GPEEE.EMPTY_ENVIRONMENT));
  }

  /**
   * Assert that the provided yaml config saves without throwing and that the saved
   * lines equal to the line contents of the provided comparison file
   * @param fileName Comparison file name within the resources/save folder
   * @param config Configuration to save and compare
   */
  public void assertSave(String fileName, YamlConfig config) throws Exception {
    StringWriter writer = new StringWriter();
    assertDoesNotThrow(() -> config.save(writer));
    List<String> fileContents = Files.readAllLines(Paths.get("src/test/resources/save/" + fileName));
    List<String> writerContents = List.of(writer.toString().split("\n"));
    assertLinesMatch(fileContents, writerContents);
  }

  /**
   * Asserts that a given value was present before removal (if existing is true) and
   * that it's absent afterwards.
   * @param path Path to remove
   * @param existing Whether the key actually exists
   * @param config Configuration instance to remove on
   */
  public void assertRemovalInMemory(String path, boolean existing, YamlConfig config) {
    assertTrue(!existing || config.exists(path));
    config.remove(path);
    assertFalse(config.exists(path));
  }

  /**
   * Asserts that a given key's comment lines do not match the lines about to append before
   * calling attach as well as their presence afterwards.
   * @param path Path to attach at
   * @param lines Lines of comments to attach
   * @param self Whether to attach to the key itself or it's value
   * @param config Configuration instance to attach on
   */
  public void assertAttachCommentInMemory(String path, List<String> lines, boolean self, YamlConfig config) {
    assertNotEquals(lines, config.readComment(path, self));
    config.attachComment(path, lines, self);
    assertEquals(lines, config.readComment(path, self));
  }

  /**
   * Asserts that a given key's value does not match the value about to set before
   * calling set and assures the key's value equality with the set value afterwards.
   * @param path Path to set at
   * @param value Value to set
   * @param config Configuration instance to set on
   */
  public void assertSetInMemory(String path, Object value, YamlConfig config) {
    assertNotEquals(config.get(path), value);
    config.set(path, value);
    assertEquals(config.get(path), value);
  }

  /**
   * Creates an ordered map of values by joining every even indexed value as a
   * key with an odd indexed value as a corresponding value
   * @param values Key value pairs
   * @return Ordered map
   */
  public Map<Object, Object> map(Object... values) {
    if (values.length % 2 != 0)
      throw new IllegalStateException("Every key needs to be mapped to a value");

    Map<Object, Object> result = new LinkedHashMap<>();

    for (int i = 0; i < values.length; i += 2)
      result.put(values[i], values[i + 1]);

    return result;
  }

  /**
   * Asserts that an executable throws an exception of a certain type with a specific message
   * @param expectedType Expected exception type
   * @param executable Executable to run
   * @param expectedMessage Expected exception message
   */
  public <T extends Throwable> void assertThrowsWithMsg(Class<T> expectedType, Executable executable, String expectedMessage) {
    T exception = assertThrows(expectedType, executable);
    assertEquals(exception.getMessage(), expectedMessage);
  }
}