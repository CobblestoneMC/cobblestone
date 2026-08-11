// odyssey-beautyquests — connects SkytAsul's BeautyQuests to Odyssey. When a player advances to a
// new quest stage that has a precise location (a "reach location", an NPC to talk to), it starts (or
// replaces) a guided Odyssey trip there; it also surfaces each started quest's current locatable
// stage as a `/navigate quest <name>` destination. A thin, un-shaded jar: it compiles against
// Odyssey's published API and the BeautyQuests API, both provided at runtime.
//
// NOTE ON VERSION: we target the RELEASED BeautyQuests API (fr.skytasul:beautyquests-api:1.0.4, the
// PlayerAccount model), which is what servers run — not the unreleased 2.0 "Quester" rewrite that is
// not yet published to Maven.

plugins {
    id("odyssey.java-conventions")
}

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(25)
    }
}

repositories {
    maven {
        name = "papermc"
        url = uri("https://repo.papermc.io/repository/maven-public/")
    }
    maven {
        name = "codemc"
        url = uri("https://repo.codemc.io/repository/maven-public/")
    }
}

dependencies {
    // Odyssey's published API — provided at runtime by the Odyssey plugin, so compileOnly only.
    compileOnly(project(":minecraft:platform:paper:paper-api"))
    compileOnly(project(":minecraft:platform:paper:paper-plugin-api"))
    // The server API (Bukkit/Paper) and BeautyQuests, both provided at runtime. BeautyQuests is
    // pulled without its transitive deps (bundled libraries we neither use nor want on classpath).
    compileOnly("io.papermc.paper:paper-api:26.1.2.build.+")
    compileOnly("fr.skytasul:beautyquests-api:1.0.4") { isTransitive = false }
    // BeautyQuests' API signatures carry JetBrains @NotNull/@Nullable; provide them so javac resolves.
    compileOnly("org.jetbrains:annotations:24.0.1")
}
