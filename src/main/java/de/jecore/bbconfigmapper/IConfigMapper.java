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

import de.jecore.bbconfigmapper.sections.IConfigSection;
import org.jetbrains.annotations.Nullable;

public interface IConfigMapper {

  /**
   * Creates an empty instance of the provided type by invoking the default-constructor
   * and then traverses it's fields to assign them one after the other with available
   * configuration values while making use of the decision methods within {@link IConfigSection}
   * @param root Root node of this section (null means config root)
   * @param type Type of the class to map
   * @return Mapped instance of specified type
   */
  <T extends IConfigSection> T mapSection(@Nullable String root, Class<T> type) throws Exception;

  /**
   * Get the underlying configuration instance
   */
  IConfig getConfig();

}
