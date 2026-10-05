// core — the two-tier search algorithm over the core-api abstractions.
plugins {
    id("cobblestone.java-conventions")
}

dependencies {
    api(project(":api"))
    // Nullability annotations (@Nullable): compile-time hints only, not shipped/transitive.
    compileOnly("org.jetbrains:annotations:24.0.1")
}
