/*
 * Odyssey — a Minecraft navigation plugin.
 * Copyright (c) 2026 whimxiqal.
 *
 * Licensed under the MIT License. See the LICENSE file in the project root for full text.
 */

package net.whimxiqal.odyssey.plugin.message;

import java.util.Locale;
import java.util.MissingResourceException;
import java.util.ResourceBundle;
import net.kyori.adventure.audience.Audience;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.TextComponent;
import net.whimxiqal.odyssey.OdysseyLogger;

/**
 * Renders and sends {@link Message}s as Adventure {@link Component}s, localized per recipient.
 *
 * <p>Templates live in {@code messages[_locale].properties} bundles. Parameters are referenced with
 * one-indexed braces ({@code {1}}, {@code {2}}, …) and rendered in the
 * {@link OdysseyColors#SECONDARY secondary} color; the surrounding literal text takes the message's
 * {@link MessageCategory} base color. Every line is prefixed with Odyssey's {@code [✦]} badge unless
 * the prefix is disabled in config.
 *
 * <p>This helper is platform-neutral (it targets Adventure {@link Audience}s), so every platform
 * plugin — Paper, Sponge — shares one message pipeline. Logger diagnostics here are developer-facing
 * and never localized.
 */
public final class Messages {

  private static final String BUNDLE_BASE = "net.whimxiqal.odyssey.plugin.messages";
  private static final Object[] NO_ARGS = new Object[0];

  private static final Component PREFIX = Component.text()
      .append(Component.text("[", OdysseyColors.PREFIX_FRAME))
      .append(Component.text("✦", OdysseyColors.PRIMARY))
      .append(Component.text("] ", OdysseyColors.PREFIX_FRAME))
      .build();

  private final OdysseyLogger logger;
  private final Locale defaultLocale;
  private volatile boolean showPrefix;

  /**
   * Creates a message renderer.
   *
   * @param defaultLocale the locale for console/system messages
   * @param showPrefix whether to prepend the {@code [✦]} badge
   * @param logger the logger for missing-key diagnostics
   */
  public Messages(Locale defaultLocale, boolean showPrefix, OdysseyLogger logger) {
    this.defaultLocale = defaultLocale;
    this.showPrefix = showPrefix;
    this.logger = logger;
  }

  /**
   * Returns the default (console/system) locale.
   *
   * @return the default locale
   */
  public Locale defaultLocale() {
    return defaultLocale;
  }

  /**
   * Sets whether the prefix badge is shown; call after a config reload.
   *
   * @param showPrefix the new value
   */
  public void setShowPrefix(boolean showPrefix) {
    this.showPrefix = showPrefix;
  }

  /**
   * Drops cached translation bundles so edited message files are re-read.
   */
  public void reload() {
    ResourceBundle.clearCache(Messages.class.getClassLoader());
  }

  /**
   * Renders a parameterless message.
   *
   * @param locale the recipient's locale
   * @param message the message
   * @return the component
   */
  public Component render(Locale locale, Message0 message) {
    return format(message, locale, NO_ARGS);
  }

  /**
   * Renders a one-parameter message.
   *
   * @param locale the recipient's locale
   * @param message the message
   * @param arg1 the first parameter
   * @return the component
   */
  public Component render(Locale locale, Message1 message, Object arg1) {
    return format(message, locale, new Object[] {arg1});
  }

  /**
   * Renders a two-parameter message.
   *
   * @param locale the recipient's locale
   * @param message the message
   * @param arg1 the first parameter
   * @param arg2 the second parameter
   * @return the component
   */
  public Component render(Locale locale, Message2 message, Object arg1, Object arg2) {
    return format(message, locale, new Object[] {arg1, arg2});
  }

  /**
   * Renders a three-parameter message.
   *
   * @param locale the recipient's locale
   * @param message the message
   * @param arg1 the first parameter
   * @param arg2 the second parameter
   * @param arg3 the third parameter
   * @return the component
   */
  public Component render(Locale locale, Message3 message, Object arg1, Object arg2, Object arg3) {
    return format(message, locale, new Object[] {arg1, arg2, arg3});
  }

  /**
   * Sends a parameterless message to an audience.
   *
   * @param audience the recipient
   * @param locale the recipient's locale
   * @param message the message
   */
  public void send(Audience audience, Locale locale, Message0 message) {
    audience.sendMessage(render(locale, message));
  }

  /**
   * Sends a one-parameter message to an audience.
   *
   * @param audience the recipient
   * @param locale the recipient's locale
   * @param message the message
   * @param arg1 the first parameter
   */
  public void send(Audience audience, Locale locale, Message1 message, Object arg1) {
    audience.sendMessage(render(locale, message, arg1));
  }

  /**
   * Sends a two-parameter message to an audience.
   *
   * @param audience the recipient
   * @param locale the recipient's locale
   * @param message the message
   * @param arg1 the first parameter
   * @param arg2 the second parameter
   */
  public void send(Audience audience, Locale locale, Message2 message, Object arg1, Object arg2) {
    audience.sendMessage(render(locale, message, arg1, arg2));
  }

  /**
   * Sends a three-parameter message to an audience.
   *
   * @param audience the recipient
   * @param locale the recipient's locale
   * @param message the message
   * @param arg1 the first parameter
   * @param arg2 the second parameter
   * @param arg3 the third parameter
   */
  public void send(Audience audience, Locale locale, Message3 message, Object arg1, Object arg2, Object arg3) {
    audience.sendMessage(render(locale, message, arg1, arg2, arg3));
  }

  private Component format(Message message, Locale locale, Object[] args) {
    String template = template(message.key(), locale);
    TextComponent.Builder builder = Component.text();
    if (showPrefix) {
      builder.append(PREFIX);
    }
    appendTemplate(builder, template, message.category(), args);
    return builder.build();
  }

  private void appendTemplate(
      TextComponent.Builder builder, String template, MessageCategory category, Object[] args) {
    StringBuilder literal = new StringBuilder();
    int index = 0;
    int length = template.length();
    while (index < length) {
      char c = template.charAt(index);
      if (c == '{') {
        int close = template.indexOf('}', index + 1);
        if (close > index) {
          int paramIndex = parseIndex(template.substring(index + 1, close));
          if (paramIndex >= 0 && paramIndex < args.length) {
            if (!literal.isEmpty()) {
              builder.append(Component.text(literal.toString(), category.baseColor()));
              literal.setLength(0);
            }
            builder.append(param(args[paramIndex]));
            index = close + 1;
            continue;
          }
        }
      }
      literal.append(c);
      index++;
    }
    if (!literal.isEmpty()) {
      builder.append(Component.text(literal.toString(), category.baseColor()));
    }
  }

  private static Component param(Object arg) {
    if (arg instanceof Component component) {
      // Wrap so an uncolored parameter inherits the secondary color, but a pre-styled one keeps its own.
      return Component.empty().color(OdysseyColors.SECONDARY).append(component);
    }
    return Component.text(String.valueOf(arg), OdysseyColors.SECONDARY);
  }

  /** Parses a brace body as a one-indexed parameter number, or {@code -1} if it is not a number. */
  private static int parseIndex(String body) {
    try {
      return Integer.parseInt(body.trim());
    } catch (NumberFormatException e) {
      return -1;
    }
  }

  private String template(String key, Locale locale) {
    try {
      return ResourceBundle.getBundle(BUNDLE_BASE, locale, Messages.class.getClassLoader()).getString(key);
    } catch (MissingResourceException e) {
      logger.warn("Missing message for key '{}' (locale {}); showing the key.", key, locale);
      return key;
    }
  }
}
