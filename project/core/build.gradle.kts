// core — the two-tier search algorithm over the core-api abstractions. (design/03)
plugins {
    id("cobblestone.publish-conventions")
}

dependencies {
    api(project(":api"))
    // Nullability annotations (@Nullable): compile-time hints only, not shipped/transitive.
    compileOnly("org.jetbrains:annotations:24.0.1")
}
