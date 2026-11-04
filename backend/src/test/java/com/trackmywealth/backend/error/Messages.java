package com.trackmywealth.backend.error;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.Properties;

/** The EN and DE message bundles as the application reads them (UTF-8, from the classpath). */
final class Messages {

  static final Properties EN = load("messages.properties");
  static final Properties DE = load("messages_de.properties");

  private Messages() {}

  private static Properties load(String name) {
    Properties properties = new Properties();
    try (InputStream in = Messages.class.getClassLoader().getResourceAsStream(name)) {
      if (in == null) {
        throw new IllegalStateException(name + " is not on the classpath");
      }
      properties.load(new InputStreamReader(in, StandardCharsets.UTF_8));
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
    return properties;
  }
}
