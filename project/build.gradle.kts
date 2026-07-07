// Root aggregator project. It has no sources of its own — all shared build logic lives in the
// convention plugins under buildSrc/ (odyssey.java-conventions, odyssey.publish-conventions), and
// each subproject applies the appropriate one. See design/01-modules-and-build.md.
//
// Inspect the module graph with:  ./gradlew projects   and   ./gradlew :<module>:dependencies
