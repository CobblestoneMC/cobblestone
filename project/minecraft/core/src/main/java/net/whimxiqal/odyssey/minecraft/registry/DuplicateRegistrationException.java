/*
 * Odyssey — a Minecraft navigation plugin.
 * Copyright (c) 2026 whimxiqal.
 *
 * Licensed under the MIT License. See the LICENSE file in the project root for full text.
 */

package net.whimxiqal.odyssey.minecraft.registry;

import java.io.Serial;

/** Thrown when a caller tries registering multiple services under the same ID. */
public class DuplicateRegistrationException extends RuntimeException {

  @Serial private static final long serialVersionUID = 1L;

  public DuplicateRegistrationException(String id) {
    super("A value was already registered for owner " + id);
  }
}
