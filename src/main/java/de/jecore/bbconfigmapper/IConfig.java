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

import org.jetbrains.annotations.Nullable;

import java.util.List;

public interface IConfig {

  /**
   * Get a value by it's path
   * @param path Path to identify the value
   */
  @Nullable Object get(
		final @Nullable String path
	);

  /**
   * Set a value by it's path
   * @param path Path to identify the value
   */
  void set(
		final @Nullable String path,
		final @Nullable Object value
	);

  /**
   * Remove a key and all of it's children by it's path
   * @param path Path to identify the key
   */
  void remove(
		final @Nullable String path
	);

  /**
   * Check whether a given path exists within the configuration file
   * @param path Path to identify the value
   */
  boolean exists(
		final @Nullable String path
	);

  /**
   * Attach a comment to a specific path
   * @param path Path to attach to
   * @param lines Lines of text in the comment
   * @param self Whether to attach to the key itself or to it's value
   */
  void attachComment(
		final @Nullable String path,
		final List<String> lines,
		final boolean self
	);

  /**
   * Read a specific path's attached comment, if available
   * @param path Path to read from
   * @param self Whether to read from the key itself or from it's value
   * @return A list of the comment's lines, null if the path doesn't exist
   *         or there's no comment attached to it yet
   */
  @Nullable List<String> readComment(
		final @Nullable String path,
		final boolean self
	);

}