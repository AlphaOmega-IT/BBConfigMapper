package me.blvckbytes.bbconfigmapper;

import me.blvckbytes.bbconfigmapper.logging.DebugLogSource;
import me.blvckbytes.gpeee.IExpressionEvaluator;
import me.blvckbytes.gpeee.Tuple;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.yaml.snakeyaml.DumperOptions;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.comments.CommentLine;
import org.yaml.snakeyaml.comments.CommentType;
import org.yaml.snakeyaml.constructor.Constructor;
import org.yaml.snakeyaml.nodes.*;
import org.yaml.snakeyaml.representer.Representer;

import java.io.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Handles YAML configuration operations and allows overwriting parts of a new file if keys are added.
 */
public class YamlConfig implements IConfig {
  
  /**
   * Initializes the YAML parser and sets up options.
   */
  private static final Yaml YAML;
  private static final DumperOptions DUMPER_OPTIONS;
  
  private final @Nullable IExpressionEvaluator evaluator;
  private final Logger logger;
  private final @Nullable String expressionMarkerSuffix;
  private final Map<MappingNode, Map<String, @Nullable NodeTuple>> locateKeyCache;
  private final List<MergedNodeTuple> mergedTuples;
  
  private MappingNode rootNode;
  private String header;
  
  static {
    LoaderOptions loaderOptions = new LoaderOptions();
    loaderOptions.setProcessComments(true);
    loaderOptions.setAllowDuplicateKeys(true);
    
    DUMPER_OPTIONS = new DumperOptions();
    DUMPER_OPTIONS.setProcessComments(true);
    DUMPER_OPTIONS.setAllowUnicode(true);
    DUMPER_OPTIONS.setDefaultFlowStyle(DumperOptions.FlowStyle.BLOCK);
    DUMPER_OPTIONS.setPrettyFlow(true);
    DUMPER_OPTIONS.setAnchorGenerator(Node::getAnchor);
    
    YAML = new Yaml(new Constructor(loaderOptions), new Representer(DUMPER_OPTIONS), DUMPER_OPTIONS, loaderOptions);
  }
  
  /**
   * Constructs a new YamlConfig instance.
   * @param evaluator The expression evaluator
   * @param logger The logger
   * @param expressionMarkerSuffix The suffix for expressions
   */
  public YamlConfig(
      final @Nullable IExpressionEvaluator evaluator,
      final Logger logger,
      final @Nullable String expressionMarkerSuffix
  ) {
    this.evaluator = evaluator;
    this.logger = logger;
    this.expressionMarkerSuffix = expressionMarkerSuffix;
    this.locateKeyCache = new HashMap<>();
    this.mergedTuples = new ArrayList<>();
  }
  
  /**
   * Loads the YAML configuration from a reader.
   * @param reader The reader containing YAML data
   */
  public void load(Reader reader) {
    Iterator<Node> nodes = YAML.composeAll(reader).iterator();

    Node root = nodes.hasNext() ? nodes.next() : createNewMappingNode(null);

    if (nodes.hasNext())
      throw new IllegalStateException("Encountered multiple nodes");

    if (!(root instanceof MappingNode))
      throw new IllegalStateException("The top level of a config has to be a map.");

    logger.log(Level.FINEST, () -> DebugLogSource.YAML + "Successfully loaded the YAML root node using the provided reader");

    // Swap out root node and execute standard loading routines
    this.rootNode = (MappingNode) root;
    this.mergedTuples.clear();
    extractHeader();
    processMergeKeys(this.rootNode);
    this.locateKeyCache.clear();
  }

  private void processMergeKeys(MappingNode node) {
    forAllMappingsRecursively(node, (currentContainer, currentKey, currentValue) -> {
      if (currentKey.getTag() == Tag.MERGE)
        mergeNodes(currentContainer, currentValue);
    });
  }

  private void mergeNodes(MappingNode destination, MappingNode source) {
    for (NodeTuple sourceTuple : source.getValue()) {
      Node sourceKey = sourceTuple.getKeyNode();
      Node sourceValue = sourceTuple.getValueNode();

      // Non-scalar keys are ignored in the merging process
      if (!(sourceKey instanceof ScalarNode))
        continue;

      // The merge source itself contains another merge key and needs to be processed first
      if (sourceKey.getTag() == Tag.MERGE) {
        if (!(sourceValue instanceof MappingNode))
          throw new IllegalStateException("Cannot merge a non-mapping node into another node");

        mergeNodes(destination, (MappingNode) sourceValue);
        continue;
      }

      String sourceKeyString = ((ScalarNode) sourceKey).getValue();
      boolean valueAbsent = true;

      // Check against all keys in the destination map
      List<NodeTuple> destinationTuples = destination.getValue();

      int destinationTuplesSize = destinationTuples.size();
      for (int i = 0; i < destinationTuplesSize; i++) {
        NodeTuple destinationTuple = destinationTuples.get(i);
        Node destinationKey = destinationTuple.getKeyNode();
        Node destinationValue = destinationTuple.getValueNode();

        if (!(destinationKey instanceof ScalarNode))
          continue;

        String destinationKeyString = ((ScalarNode) destinationKey).getValue();
        if (!sourceKeyString.equals(destinationKeyString))
          continue;

        valueAbsent = false;

        // Override scalar values
        if (!(destinationValue instanceof MappingNode)) {
          int destinationPointer = destinationValue.getStartMark().getPointer();
          int sourcePointer = sourceValue.getStartMark().getPointer();

          // Only override if the key to be overridden is above the source
          // Keys which are added afterwards have a higher priority and thus persist
          if (destinationPointer < sourcePointer)
            destinationTuples.set(i, new NodeTuple(destinationKey, sourceValue));

          break;
        }

        mergeNodes((MappingNode) destinationValue, (MappingNode) sourceValue);
      }

      if (valueAbsent) {
        NodeTuple tuple = new NodeTuple(sourceKey, sourceValue);
        MergedNodeTuple mergedTuple = new MergedNodeTuple(
          tuple,
          () -> destinationTuples.add(tuple),
          () -> destinationTuples.remove(tuple)
        );

        mergedTuples.add(mergedTuple);

        // Initially call the add routine
        mergedTuple.addRoutine.run();
      }
    }
  }

  /**
   * Extract the header comment from the first key's first node tuple by taking as many
   * block comment lines as possible until a blank line occurs. If no blank line is to be
   * found, nothing will be extracted, as the comment is considered to be attached to the key.
   */
  private void extractHeader() {
    List<NodeTuple> rootTuples = this.rootNode.getValue();

    if (rootTuples.isEmpty()) {
      this.header = "";
      return;
    }

    Node firstKey = rootTuples.getFirst().getKeyNode();
    List<CommentLine> firstKeyBlockComments = firstKey.getBlockComments();

    if (firstKeyBlockComments == null) {
      this.header = "";
      return;
    }

    List<CommentLine> untouchedBlockComments = new ArrayList<>(firstKeyBlockComments);

    StringBuilder headerBuilder = new StringBuilder();
    boolean foundBlankLine = false;
    boolean foundBlockLine = false;

    while (! firstKeyBlockComments.isEmpty()) {
      CommentLine firstLine = firstKeyBlockComments.removeFirst();
      CommentType type = firstLine.getCommentType();
      String value = firstLine.getValue();

      // Collect as many block comment lines as possible
      if (type == CommentType.BLOCK) {
        headerBuilder.append("#").append(value).append('\n');
        foundBlockLine = true;
        continue;
      }

      // Stop at (including) the first blank line
      if (foundBlockLine && firstLine.getCommentType() == CommentType.BLANK_LINE) {
        headerBuilder.append('\n');
        foundBlankLine = true;
        break;
      }

      // Unwanted comment type, if this is reached, state will be restored also
      break;
    }

    // Didn't find a blank line, stop and roll back to vanilla state
    if (!foundBlankLine) {
      firstKey.setBlockComments(untouchedBlockComments);
      headerBuilder.setLength(0);
    }

    this.header = headerBuilder.toString();
  }

  public void save(Writer writer) throws IOException {
    logger.log(Level.FINEST, () -> DebugLogSource.YAML + "Serializing the YAML root node to the provided writer");

    if (this.rootNode == null || this.rootNode.getValue().isEmpty()) {
      writer.write("");
      return;
    }

    writer.write(this.header);
    executeWhileMergedTuplesAbsent(() -> YAML.serialize(this.rootNode, writer));
  }

  private void executeWhileMergedTuplesAbsent(Runnable executable) {
    synchronized (this.mergedTuples) {
			// If the remove routine yielded false, the element has no longer been in the list
			// This means that somebody else removed it, and it thus should also not be added back later on
			// Thus, remove it from the merged tuples list
			this.mergedTuples.removeIf(mergedTuple -> ! mergedTuple.removeRoutine.get());

      executable.run();

      for (MergedNodeTuple mergedTuple : this.mergedTuples)
        mergedTuple.addRoutine.run();
    }
  }
  
  /**
   * Extends keys which the provided config contains but are absent on this instance
   * by copying over the values those keys hold
   * @param other Config to extend from
   * @return Number of updated keys
   */
  public int extendMissingKeys(YamlConfig other) {
    if (other.rootNode == null) {
      throw new IllegalStateException("Other config has not yet been loaded");
    }
    
    AtomicInteger updatedKeys = new AtomicInteger();
    
    return forEachKeyPathRecursively(other.rootNode, null, (tuple, pathOfTuple, indexOfTuple) -> {
      if (this.exists(pathOfTuple)) {
        // Key already exists, no need to extend
        return false;
      }
      
      MappingNode container = locateContainerNode(pathOfTuple, true).a;
      List<NodeTuple> containerTuples = container.getValue();
      String key = ((ScalarNode) tuple.getKeyNode()).getValue();
      
      // Check if the key already exists in the container
      boolean keyExists = false;
      for (NodeTuple nodeTuple : containerTuples) {
        if (nodeTuple.getKeyNode() instanceof ScalarNode &&
            ((ScalarNode) nodeTuple.getKeyNode()).getValue().equals(key)) {
          keyExists = true;
          break;
        }
      }
      
      if (!keyExists) {
        // The new key is at an index which doesn't yet exist, add to the end of the tuple list
        if (indexOfTuple >= containerTuples.size()) {
          containerTuples.add(tuple);
        } else {
          // Insert the new tuple at the right index
          containerTuples.add(indexOfTuple, tuple);
        }
        
        invalidateLocateKeyCacheFor(container, key);
        updatedKeys.getAndIncrement();
      }
      
      return true;
    });
  }

  private int forEachKeyPathRecursively(MappingNode node, @Nullable String parentPath, FExtensionCandidateHandler handler) {
    int updatedKeys = 0;

    List<NodeTuple> nodeTuples = node.getValue();
    for (int tupleIndex = 0; tupleIndex < nodeTuples.size(); tupleIndex++) {
      NodeTuple tuple = nodeTuples.get(tupleIndex);
      Node valueNode = tuple.getValueNode();
      Node keyNode = tuple.getKeyNode();

      if (keyNode instanceof ScalarNode) {
        String keyString = ((ScalarNode) keyNode).getValue();
        String keyPath = parentPath != null ? parentPath + "." + keyString : keyString;

        // Take the whole node from the other config in order to also carry over comments, formatting, etc
        boolean didConsumerUpdate = handler.apply(tuple, keyPath, tupleIndex);

        if (didConsumerUpdate)
          ++updatedKeys;

        // No need to visit the children of the current key, as they've already
        // been taken care of by copying their parent
        if (didConsumerUpdate)
          continue;

        if (valueNode instanceof MappingNode) {
          String nextKeyParentPath = parentPath == null ? keyString : parentPath + "." + keyString;
          updatedKeys += forEachKeyPathRecursively((MappingNode) valueNode, nextKeyParentPath, handler);
        }
      }
    }

    return updatedKeys;
  }

  @Override
  public @Nullable Object get(@Nullable String path) {
    logger.log(Level.FINEST, () -> DebugLogSource.YAML + "Object at path=" + path + " has been requested");

    Tuple<@Nullable Node, Boolean> target = locateNode(path, false, false);
    Object value = target.a == null ? null : unwrapNode(target.a, target.b);

    logger.log(Level.FINEST, () -> DebugLogSource.YAML + "Returning content of path=" + path + " with value=" + value);

    return value;
  }

  @Override
  public void set(@Nullable String path, @Nullable Object value) {
    logger.log(Level.FINEST, () -> DebugLogSource.YAML + "An update of value=" + value + " at path=" + path + " has been requested");

    Node wrappedValue = wrapValue(value);

    if (path == null) {
      if (!(wrappedValue instanceof MappingNode))
        throw new IllegalArgumentException("Cannot exchange the root-node for a non-map node");

      logger.log(Level.FINEST, () -> DebugLogSource.YAML + "Swapped out the root node");

      rootNode = (MappingNode) wrappedValue;
      extractHeader();
      return;
    }

    updatePathValue(path, wrappedValue, true);
  }

  @Override
  public void remove(@Nullable String path) {
    logger.log(Level.FINEST, () -> DebugLogSource.YAML + "The removal of path=" + path + " has been requested");

    if (path == null) {
      logger.log(Level.FINEST, () -> DebugLogSource.YAML + "Reset the root node");

      rootNode = createNewMappingNode(null);
      return;
    }

    updatePathValue(path, null, false);
  }

  private MappingNode createNewMappingNode(@Nullable List<NodeTuple> items) {
    if (items == null)
      items = new ArrayList<>();
    return new MappingNode(Tag.MAP, true, items, null, null, DUMPER_OPTIONS.getDefaultFlowStyle());
  }

  @Override
  public boolean exists(@Nullable String path) {
    logger.log(Level.FINEST, () -> DebugLogSource.YAML + "An existence check of path=" + path + " has been requested");

    // For a key to exist, it's path has to exist within the
    // config, even if it points at a null value
    boolean exists = locateNode(path, true, false).a != null;

    logger.log(Level.FINEST, () -> DebugLogSource.YAML + "Returning existence value for path=" + path + " of exists=" + exists);

    return exists;
  }

  @Override
  public void attachComment(@Nullable String path, List<String> lines, boolean self) {
    logger.log(Level.FINEST, () -> DebugLogSource.YAML + "Attaching a comment to path=" + path + " (self=" + self + ") of lines=" + lines + " has been requested");

    Node target = locateNode(path, self, false).a;

    if (target == null)
      throw new IllegalStateException("Cannot attach a comment to a non-existing path");

    List<CommentLine> comments = new ArrayList<>();

    for (String line : lines) {
      CommentType type = line.isBlank() ? CommentType.BLANK_LINE : CommentType.BLOCK;
      comments.add(new CommentLine(null, null, line, type));
    }

    target.setBlockComments(comments);
  }

  @Override
  public @Nullable List<String> readComment(@Nullable String path, boolean self) {
    logger.log(Level.FINEST, () -> DebugLogSource.YAML + "Reading the comment at path=" + path + " (self=" + self + ") has been requested");

    Node target = locateNode(path, self, false).a;

    if (target == null)
      return null;

    List<String> comments = new ArrayList<>();
    List<CommentLine> targetComments = target.getBlockComments();

    for (CommentLine comment : targetComments) {
      if (comment.getCommentType() == CommentType.BLANK_LINE) {
        comments.add("\n");
        continue;
      }

      comments.add(comment.getValue());
    }

    logger.log(Level.FINEST, () -> DebugLogSource.YAML + "Returning comments for path=" + path + " comments=" + comments);

    return comments;
  }

  private Tuple<MappingNode, String> locateContainerNode(String keyPath, boolean forceCreateMappings) {
    int lastDotIndex = keyPath.lastIndexOf('.');
    String keyPart;
    MappingNode container;

    // No dot in the path, the container is root and the key-part is just the path value
    if (lastDotIndex < 0) {
      container = rootNode;
      keyPart = keyPath;
    }

    // Get the value after the last dot as a substring and use that as the key-part
    // Look up the container by the path provided before the last dot (in force mapping creation mode)
    else {
      keyPart = keyPath.substring(lastDotIndex + 1);
      container = (MappingNode) locateNode(keyPath.substring(0, lastDotIndex), false, forceCreateMappings).a;
    }

    if (container == null || keyPart.isBlank())
      throw new IllegalArgumentException("Invalid path specified: " + keyPath);

    if (!keyPath.endsWith(keyPart))
      throw new IllegalStateException("Could not locate the containing node for path: " + keyPath);

    return new Tuple<>(container, keyPart);
  }

  /**
   * Update the value at the key a given path points to within the tree
   * @param keyPath Path to change the value at
   * @param value New value node, leave null to just remove this node
   */
  private void updatePathValue(String keyPath, @Nullable Node value, boolean forceCreateMappings) {
    Tuple<MappingNode, String> containerAndKeyPart = locateContainerNode(keyPath, forceCreateMappings);
    MappingNode container = containerAndKeyPart.a;
    String keyPart = containerAndKeyPart.b;

    // Check if there's an existing tuple
    NodeTuple existingTuple = locateKey(container, keyPart);
    Node existingKey = null;
    int existingIndex = -1;

    invalidateLocateKeyCacheFor(container, keyPart);

    // Remove an existing tuple from the map
    if (existingTuple != null) {
      existingKey = existingTuple.getKeyNode();
      existingIndex = container.getValue().indexOf(existingTuple);
      container.getValue().remove(existingIndex);

      // If the just removed tuple held a mapping node as it#s value, invalidate
      // all children mappings within that tuple recursively
      Node valueNode = existingTuple.getValueNode();
      if (valueNode instanceof MappingNode) {
        forAllMappingsRecursively((MappingNode) valueNode, (currentContainer, currentKey, currentValue) -> {
          this.invalidateLocateKeyCacheFor(currentValue, currentKey.getValue());
        });
      }
    }

    // Create a new tuple for this value, if provided
    if (value != null) {
      NodeTuple newTuple = createNewTuple(existingKey, keyPart, value);

      // Preserve it's index within the list of tuples
      if (existingIndex >= 0)
        container.getValue().add(existingIndex, newTuple);
      else
        container.getValue().add(newTuple);
    }
  }

  /**
   * Invalidate a {@link #locateKey(MappingNode, String)} request cache entry for
   * a specific node targeting a specific key lookup
   * @param node Target node
   * @param key Target key
   */
  private void invalidateLocateKeyCacheFor(MappingNode node, String key) {
    Map<String, @Nullable NodeTuple> containerCache = this.locateKeyCache.get(node);

    if (containerCache != null) {
      containerCache.remove(key);

      // If there's an expression marker suffix used with this configuration
      if (expressionMarkerSuffix != null) {

        // It's appended, also remove the cache entry for the non-suffixed version
        if (key.endsWith(expressionMarkerSuffix))
          containerCache.remove(key.substring(0, key.length() - 1));

        // It's not appended, also remove the cache entry for the suffixed version
        else
          containerCache.remove(key + expressionMarkerSuffix);
      }
    }
  }

  /**
   * Call the consumer on all instances of a {@link MappingNode} within the recursive children of
   * the provided parent mapping node if that child is registered using a string key
   * @param node Parent mapping node
   * @param consumer Mapping node consumer
   */
  private void forAllMappingsRecursively(MappingNode node, FMappingNodeConsumer consumer) {
    List<NodeTuple> tupleList = node.getValue();
    int currentTupleIndex = 0;

    // NOTE: It's important to iterate by indices here, as mappings are sometimes extended
    // while iterating, and those mappings should also show up in the iteration later on
    while (currentTupleIndex < tupleList.size()) {
      NodeTuple currentTuple = tupleList.get(currentTupleIndex);

      Node valueNode = currentTuple.getValueNode();
      Node keyNode = currentTuple.getKeyNode();

      if (valueNode instanceof MappingNode && keyNode instanceof ScalarNode) {
        forAllMappingsRecursively((MappingNode) valueNode, consumer);

        // Call after expanding, to iterate depth-first
        consumer.accept(node, (ScalarNode) keyNode, (MappingNode) valueNode);
      }

      ++currentTupleIndex;
    }
  }

  /**
   * Create a new tuple by mapping either an existing key node or a new
   * {@link ScalarNode} key from a string to a node value
   * @param keyNode Key node to use
   * @param key Key string to use to create a new key node
   * @param value Node value to assign to this key
   * @return Node tuple containing the key-value pair
   */
  private NodeTuple createNewTuple(@Nullable Node keyNode, @Nullable String key, Node value) {
    if (keyNode == null) {
    //assert key != null;
      keyNode = new ScalarNode(Tag.STR, key, null, null, DumperOptions.ScalarStyle.PLAIN);
    }

    return new NodeTuple(keyNode, value);
  }

  /**
   * Locates a target node by it's identifying path
   * @param path Path to search for, null means root
   * @param self Whether to locate the containing key or the value (self means the key)
   * @return A tuple of the target node or null if the target node didn't exist
   *         as well as a boolean marking whether this path was marked for expressions
   */
  private @NotNull Tuple<@Nullable Node, Boolean> locateNode(@Nullable String path, boolean self, boolean forceCreateMappings) {
    if (path == null)
      return new Tuple<>(rootNode, false);

    // Keys should never contain any whitespace
    path = path.trim();

    if (path.isBlank())
      throw new IllegalArgumentException("Invalid path specified: " + path);

    Node node = rootNode;
    boolean markedForExpressions = false;

    int endIndex = path.indexOf('.'), beginIndex = 0;

    while (true) {

      // No next dot available, go until the end of the path string
      if (endIndex < 0)
        endIndex = path.length();

      // Substring between the current begin (inclusive) and the dot position (exclusive)
      String pathPart = path.substring(beginIndex, endIndex);

      // Not a mapping node, cannot look up a path-part, the key has to be invalid
      if (!(node instanceof final MappingNode mapping))
        return new Tuple<>(null, markedForExpressions);

      NodeTuple keyValueTuple = locateKey(mapping, pathPart);
      boolean markedAlready = expressionMarkerSuffix != null && pathPart.endsWith(expressionMarkerSuffix);

      // The k-v tuple could not be located and isn't marked for expressions already
      // Try to append the expression marker and check for a match again
      if (keyValueTuple == null && !markedAlready) {
        keyValueTuple = locateKey(mapping, pathPart + expressionMarkerSuffix);
        markedAlready = true;
      }

      // There was a tuple available and it carried the expression marker
      if (keyValueTuple != null && markedAlready)
        markedForExpressions = true;

      // Target tuple could not be located or is of wrong value type, create a
      // new tuple of value type mapping and set it within the tree
      if (forceCreateMappings && (keyValueTuple == null || !(keyValueTuple.getValueNode() instanceof MappingNode))) {
        // Try to reuse already present key nodes
        Node tupleKey = keyValueTuple == null ? null : keyValueTuple.getKeyNode();

        keyValueTuple = createNewTuple(tupleKey, pathPart, createNewMappingNode(null));
        mapping.getValue().add(keyValueTuple);

        // Invalidate the (null) cache for this newly added tuple
        invalidateLocateKeyCacheFor(mapping, pathPart);
      }

      // Current path-part does not exist
      if (keyValueTuple == null)
        return new Tuple<>(null, markedForExpressions);

      // On the last iteration and the key itself has been requested
      if (endIndex == path.length() && self)
        node = keyValueTuple.getKeyNode();
      else
        node = keyValueTuple.getValueNode();

      // Just processed until the end of the path string, exit
      if (endIndex == path.length())
        break;

      // Go to the next key
      beginIndex = endIndex + 1;
      endIndex = path.indexOf('.', beginIndex);

      if (node == null)
        break;
    }

    return new Tuple<>(node, markedForExpressions);
  }

  /**
   * Locates a target key's containing node tuple within a mapping node's list of tuples
   * @param node Node to search in
   * @param key Target key
   * @return Target tuple if found, null on absent key
   */
  private @Nullable NodeTuple locateKey(MappingNode node, String key) {
    Map<String, @Nullable NodeTuple> nodeCache = locateKeyCache.computeIfAbsent(node, k -> new HashMap<>());

    // Check cache before going through linear search
    if (nodeCache.containsKey(key))
      return nodeCache.get(key);

    // Loop all mappings of this key
    List<NodeTuple> entries = node.getValue();
    for (NodeTuple entry : entries) {
      Node keyNode = entry.getKeyNode();

      // Not a scalar key
      if (!(keyNode instanceof ScalarNode))
        continue;

      // Key mismatch
      if (!((ScalarNode) keyNode).getValue().equalsIgnoreCase(key))
        continue;

      // Merge keys should never be retrievable and thus be "hidden"
      if (entry.getKeyNode().getTag() == Tag.MERGE)
        continue;

      // Remember this call's yielded entry
      nodeCache.put(key, entry);
      return entry;
    }

    // Also remember failed lookups
    nodeCache.put(key, null);
    return null;
  }

  /**
   * Unwraps any given node by unwrapping scalar values first, then - if applicable - collecting them
   * into maps or lists, as by the node's tag. Null tags will result in null values.
   * @param node Node to unwrap
   * @param markedForExpressions Whether expressions should be parsed
   * @return Unwrapped node as a Java value
   */
  private @Nullable Object unwrapNode(Node node, boolean markedForExpressions) {
    if (node instanceof ScalarNode)
      return unwrapScalarNode((ScalarNode) node, markedForExpressions);

    if (node instanceof SequenceNode) {
      List<Object> values = new ArrayList<>();

      for (Node item : ((SequenceNode) node).getValue())
        values.add(unwrapNode(item, markedForExpressions));

      return values;
    }

    if (node instanceof MappingNode) {
      Map<Object, Object> values = new LinkedHashMap<>();

      for (NodeTuple item : ((MappingNode) node).getValue()) {
        boolean isItemMarkedForExpressions = markedForExpressions;

        // Expressions within keys are - of course - not supported
        Object key = unwrapNode(item.getKeyNode(), false);

        // If the key is a string, it might hold an attached marker which needs to be stripped off
        if (key instanceof final String keyS) {

          // Strip of trailing marker, also mark for expressions (if not marked already)
          if (expressionMarkerSuffix != null && keyS.endsWith(expressionMarkerSuffix)) {
            key = keyS.substring(0, keyS.length() - 1);
            isItemMarkedForExpressions = true;
          }
        }

        values.put(key, unwrapNode(item.getValueNode(), isItemMarkedForExpressions));
      }

      return values;
    }

    throw new IllegalStateException("Encountered unknown node type >" + node.getType().getName() + "<");
  }

  /**
   * Tries to wrap an object in a yaml tree node, throws an {@link IllegalArgumentException}
   * for value types which cannot be depicted as a yaml value directly
   * @param value Value to wrap
   * @return Wrapped value
   */
  private Node wrapValue(@Nullable Object value) {
    Node node = wrapScalarNode(value);

    if (node != null)
      return node;

    if (value instanceof Collection) {
      List<Node> values = new ArrayList<>();
      node = new SequenceNode(Tag.SEQ, true, values, null, null, DUMPER_OPTIONS.getDefaultFlowStyle());

      for (Object item : (Collection<?>) value)
        values.add(wrapValue(item));

      return node;
    }

    if (value instanceof Map) {
      List<NodeTuple> tuples = new ArrayList<>();
      node = createNewMappingNode(tuples);

      for (Map.Entry<?, ?> entry : ((Map<?, ?>) value).entrySet()) {
        Node keyNode = createScalarNode(String.valueOf(entry.getKey()), Tag.STR);
        tuples.add(new NodeTuple(keyNode, wrapValue(entry.getValue())));
      }

      return node;
    }

    throw new IllegalArgumentException("Cannot store a value of type " + value.getClass());
  }

  /**
   * Unwraps a {@link ScalarNode} to a java type
   * @param node Node to unwrap
   * @param markedForExpressions Whether expressions should be parsed
   * @return Unwrapped java value
   */
  private @Nullable Object unwrapScalarNode(ScalarNode node, boolean markedForExpressions) {
    Tag tag = node.getTag();

    if (tag == Tag.NULL)
      return null;

    // If a node is marked for expression either itself or by a parent node, it
    // will be parsed as such, no matter it's tag, as it's a user-choice
    if (evaluator != null && markedForExpressions)
      return evaluator.optimizeExpression(evaluator.parseString(node.getValue()));

    if (tag == Tag.STR)
      return node.getValue();

    if (tag == Tag.BOOL)
      return node.getValue().equalsIgnoreCase("true");

    if (tag == Tag.INT)
      return Long.parseLong(node.getValue());

    if (tag == Tag.FLOAT)
      return Double.parseDouble(node.getValue());

    throw new IllegalStateException("Encountered unknown scalar node type >" + tag + "<");
  }

  /**
   * Wraps a Java object into a suited containing {@link ScalarNode}, if possible
   * @param value Value to wrap
   * @return Wrapped value or null if this isn't a scalar value
   */
  private @Nullable Node wrapScalarNode(@Nullable Object value) {
    String stringValue = String.valueOf(value);

    if (value == null)
      return createScalarNode(stringValue, Tag.NULL);

    if (value instanceof Boolean)
      return createScalarNode(stringValue, Tag.BOOL);

    if (value instanceof Long || value instanceof Integer || value instanceof Short || value instanceof Byte)
      return createScalarNode(stringValue, Tag.INT);

    if (value instanceof Double || value instanceof Float)
      return createScalarNode(stringValue, Tag.FLOAT);

    if (value instanceof String)
      return createScalarNode(stringValue, Tag.STR);

    // Unknown scalar type
    return null;
  }

  /**
   * Create a new, parameterized scalar node
   * @param value String value (node content)
   * @param tag Type tag
   * @return Created scalar node
   */
  private ScalarNode createScalarNode(String value, Tag tag) {
    return new ScalarNode(tag, value, null, null, DumperOptions.ScalarStyle.PLAIN);
  }
}