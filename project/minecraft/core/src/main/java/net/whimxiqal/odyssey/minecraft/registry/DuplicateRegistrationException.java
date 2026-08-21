package net.whimxiqal.odyssey.minecraft.registry;

/**
 * Thrown when a caller tries registering multiple services under the same ID.
 */
public class DuplicateRegistrationException extends RuntimeException {

  public DuplicateRegistrationException(String id) {
    super("A value was already registered for owner " + id);
  }

}
