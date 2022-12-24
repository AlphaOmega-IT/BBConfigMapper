package me.blvckbytes.bbconfigmapper.sections;

import org.jetbrains.annotations.Nullable;

import java.lang.reflect.Field;
import java.util.List;

public interface IConfigSection {

  /**
   * Called to decide the type of Object fields at runtime,
   * based on previously parsed values of that instance, as
   * it's patched one field at a time. Decideable fields are
   * always read last, so that they have access to other,
   * known type fields in order to decide properly.
   * @param field Target field in question
   * @return Decided type, Object.class means skip
   */
  Class<?> runtimeDecide(String field);

  /**
   * Called when a field wasn't found within the config and a default could be set
   * @param type Target field's type
   * @param field Target field name
   * @return Value to use as a default
   */
  @Nullable Object defaultFor(Class<?> type, String field);

  /**
   * Called when parsing of the section is completed
   * and no more changes will be applied
   */
  void afterParsing(List<Field> fields) throws Exception;

}
