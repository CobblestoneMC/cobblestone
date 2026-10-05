/*
 * Cobblestone — a Minecraft navigation plugin.
 * Copyright (c) 2026 whimxiqal.
 *
 * Licensed under the MIT License. See the LICENSE file in the project root for full text.
 */

package org.cobblestonemc.stonebrick.bench;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.Reader;
import java.io.Writer;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.cobblestonemc.stonebrick.format.CaptureDirectory;
import org.cobblestonemc.stonebrick.format.CorpusLayout;
import org.yaml.snakeyaml.Yaml;

/**
 * Generates the benchmark corpus from a seed, by running a real server and capturing from it.
 *
 * <p><b>Why this exists.</b> Captures are too large to keep in version control, so without this the
 * terrain on each developer's machine is whatever they happened to walk to — which makes a baseline
 * meaningless to anyone else. A seed fixes the routes and landforms, so the scenarios and the
 * manifest are shareable. It does not fix every block -- decorations differ between two generations
 * of the same seed -- so captures stay local, and so do the baselines measured against them.
 *
 * <p><b>Commands go in over stdin.</b> Paper reads its console from standard input, so driving it
 * needs no RCON port, no password and no protocol implementation — and the copier already reports
 * to the console, so the same stream says when a capture has finished.
 *
 * <p>⚠️ Running a Minecraft server means accepting Mojang's EULA, which is not something this tool
 * will do on someone's behalf. It refuses to start until told explicitly.
 */
public final class CorpusProvisioner {

  /** How long to wait for the server to finish starting. */
  private static final long BOOT_TIMEOUT_SECONDS = 300;

  /** How long to wait for one capture to finish. */
  private static final long CAPTURE_TIMEOUT_SECONDS = 1800;

  private final Path corpusRoot;
  private final Path workDir;
  private final Path copierJar;
  private final boolean eulaAccepted;
  private final String javaExecutable;

  /**
   * Creates a provisioner.
   *
   * @param corpusRoot the corpus directory, holding {@code corpus.yml} and the captures
   * @param workDir where the server is unpacked and run
   * @param copierJar the built copier plugin
   * @param eulaAccepted whether the caller has accepted Mojang's EULA
   * @param javaExecutable the Java to run the server with; modern Paper needs a newer one than this
   *     build compiles with, so the Gradle toolchain resolves it rather than trusting PATH
   */
  public CorpusProvisioner(
      Path corpusRoot, Path workDir, Path copierJar, boolean eulaAccepted, String javaExecutable) {
    this.corpusRoot = corpusRoot;
    this.workDir = workDir;
    this.copierJar = copierJar;
    this.eulaAccepted = eulaAccepted;
    this.javaExecutable = javaExecutable;
  }

  /** What the corpus is generated from. */
  record Corpus(long seed, String minecraftVersion, Integer paperBuild) {}

  /**
   * Captures whatever the manifest asks for and the corpus does not already hold.
   *
   * @throws IOException if the corpus cannot be read, or the server cannot be run
   * @throws InterruptedException if waiting for the server is interrupted
   */
  public void provision() throws IOException, InterruptedException {
    Corpus corpus = readCorpus();
    Map<String, NeededChunks> needed =
        NeededChunks.read(corpusRoot.resolve(NeededChunks.FILE_NAME));
    List<Scenario> scenarios = Scenario.loadAll(corpusRoot.resolve(Scenario.FILE_NAME));

    List<Request> outstanding = outstanding(scenarios, needed);
    if (outstanding.isEmpty()) {
      System.out.println("Corpus is complete; nothing to capture.");
      return;
    }
    System.out.printf("%d capture(s) needed:%n", outstanding.size());
    for (Request request : outstanding) {
      System.out.printf(
          "  %-20s %s  %,d chunks missing%n",
          request.capture(), request.world(), request.missing());
    }

    if (!eulaAccepted) {
      throw new IOException(
          """
          Generating the corpus runs a Minecraft server, which means accepting Mojang's EULA
          (https://aka.ms/MinecraftEULA). This tool will not accept it for you. Re-run with:
            ./gradlew captureCorpus -PacceptMinecraftEula=true""");
    }

    Path jar = paperJar(corpus);
    prepareServer(corpus, jar);
    try {
      runServer(outstanding, jar);
    } catch (IOException first) {
      // A world left half-written by an interrupted boot cannot be recovered from, and the error it
      // produces ("Overworld settings missing") says nothing about what to do about it. The world
      // is
      // only ever a cache of terrain the seed already determines, so discarding it and trying once
      // more costs generation time and nothing else.
      if (!Files.isDirectory(workDir.resolve("world"))) {
        throw first;
      }
      System.out.println("Server failed to start; discarding the generated world and retrying.");
      discardWorld();
      runServer(outstanding, jar);
    }
    collect();
  }

  /** One capture that has to be taken. */
  private record Request(
      String capture, String world, int x1, int z1, int x2, int z2, int radius, int missing) {}

  private List<Request> outstanding(List<Scenario> scenarios, Map<String, NeededChunks> needed) {
    List<Request> requests = new ArrayList<>();
    for (Scenario scenario : scenarios) {
      NeededChunks capsule = needed.get(scenario.id());
      if (capsule == null) {
        capsule = NeededChunks.initial(scenario);
      }
      CaptureDirectory capture = CorpusLayout.at(corpusRoot).capture(scenario.capture());
      int missing = countMissing(capture, capsule);
      // Terrain can be complete while the traits describing it are not. A new trait bit changes
      // what the block data means, and re-capturing is the only way to refresh it -- so a stale
      // table is a reason to capture even when every column is already on disk.
      if (missing == 0 && !staleTraits(capture)) {
        continue;
      }
      requests.add(
          new Request(
              scenario.capture(),
              capsule.world(),
              capsule.from().x() >> 4,
              capsule.from().z() >> 4,
              capsule.to().x() >> 4,
              capsule.to().z() >> 4,
              capsule.radiusChunks(),
              missing));
    }
    return requests;
  }

  /**
   * Counts how many of a capsule's chunks the capture does not hold.
   *
   * <p>Checked per chunk rather than by a marker file, so an interrupted capture resumes rather
   * than being either redone from scratch or wrongly believed complete.
   */
  /** Whether this capture's trait table was written against different trait bits. */
  boolean staleTraits(CaptureDirectory capture) {
    java.nio.file.Path table = capture.root().resolve("traits.tsv");
    if (!java.nio.file.Files.isRegularFile(table)) {
      return true;
    }
    try {
      org.cobblestonemc.stonebrick.format.TraitTable.read(table);
      return false;
    } catch (java.io.IOException e) {
      return true;
    }
  }

  private int countMissing(CaptureDirectory capture, NeededChunks capsule) {
    // Shift, not divide. Integer division truncates towards zero, so block -1 would land in chunk
    // 0 rather than chunk -1 — and the box would then miss a strip of the capsule on every negative
    // edge, leaving chunks permanently reported as missing that the capture command never asks for.
    int minX = (Math.min(capsule.from().x(), capsule.to().x()) >> 4) - capsule.radiusChunks();
    int maxX = (Math.max(capsule.from().x(), capsule.to().x()) >> 4) + capsule.radiusChunks();
    int minZ = (Math.min(capsule.from().z(), capsule.to().z()) >> 4) - capsule.radiusChunks();
    int maxZ = (Math.max(capsule.from().z(), capsule.to().z()) >> 4) + capsule.radiusChunks();
    int missing = 0;
    for (int x = minX; x <= maxX; x++) {
      for (int z = minZ; z <= maxZ; z++) {
        if (capsule.covers(x, z) && !Files.exists(capture.column(capsule.world(), x, z))) {
          missing++;
        }
      }
    }
    return missing;
  }

  @SuppressWarnings("unchecked")
  private Corpus readCorpus() throws IOException {
    Path file = corpusRoot.resolve("corpus.yml");
    if (!Files.isRegularFile(file)) {
      throw new IOException("no corpus.yml at " + file);
    }
    try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
      Map<String, Object> root = new Yaml().load(reader);
      Object build = root.get("paperBuild");
      return new Corpus(
          ((Number) root.get("seed")).longValue(),
          String.valueOf(root.get("minecraftVersion")),
          build instanceof Number number ? number.intValue() : null);
    } catch (RuntimeException e) {
      throw new IOException(file + ": " + e.getMessage(), e);
    }
  }

  /**
   * A published Paper build: its number, where to get it, and what it should hash to.
   *
   * @param id the build number
   * @param url the download URL
   * @param sha256 the expected checksum
   * @param name the jar's file name
   */
  private record PaperBuild(int id, String url, String sha256, String name) {}

  /**
   * Identifies this tool to the Paper API.
   *
   * <p>Requested by their terms of use, and it is the courteous thing: an unattributed script
   * hammering a free service is how free services stop being free.
   */
  private static final String USER_AGENT =
      "cobblestone-stonebrick/0.2 (+https://cobblestonemc.org)";

  /**
   * Returns the Paper jar, downloading it if this machine does not have it.
   *
   * <p>If {@code corpus.yml} does not pin a build, the newest stable one is resolved and <b>written
   * back</b>. Terrain is only reproducible within a build, so leaving it floating would mean the
   * corpus quietly changed the next time Paper published — the pin is as much the corpus's identity
   * as the seed is.
   */
  private Path paperJar(Corpus corpus) throws IOException, InterruptedException {
    PaperBuild build = resolveBuild(corpus);
    if (corpus.paperBuild() == null) {
      pinBuild(build.id());
      System.out.println("Pinned Paper build " + build.id() + " in corpus.yml.");
    }
    Path jar = workDir.resolve("cache").resolve(build.name());
    if (Files.isRegularFile(jar) && sha256(jar).equals(build.sha256())) {
      return jar;
    }
    Files.createDirectories(jar.getParent());
    System.out.println("Downloading " + build.url());
    Path part = jar.resolveSibling(build.name() + ".part");
    HttpResponse<Path> response =
        HttpClient.newHttpClient()
            .send(
                HttpRequest.newBuilder(URI.create(build.url()))
                    .header("User-Agent", USER_AGENT)
                    .build(),
                HttpResponse.BodyHandlers.ofFile(part));
    if (response.statusCode() != 200) {
      Files.deleteIfExists(part);
      throw new IOException("Paper download failed with HTTP " + response.statusCode());
    }
    // Verified rather than trusted: a truncated download would otherwise surface as an
    // incomprehensible server crash rather than as a failed download.
    String actual = sha256(part);
    if (!actual.equals(build.sha256())) {
      Files.deleteIfExists(part);
      throw new IOException(
          "Paper download is corrupt: expected sha256 " + build.sha256() + ", got " + actual);
    }
    Files.move(part, jar, StandardCopyOption.REPLACE_EXISTING);
    return jar;
  }

  private static String sha256(Path file) throws IOException {
    try {
      java.security.MessageDigest digest = java.security.MessageDigest.getInstance("SHA-256");
      StringBuilder hex = new StringBuilder();
      for (byte b : digest.digest(Files.readAllBytes(file))) {
        hex.append(Character.forDigit((b >> 4) & 0xF, 16)).append(Character.forDigit(b & 0xF, 16));
      }
      return hex.toString();
    } catch (java.security.NoSuchAlgorithmException e) {
      throw new IOException(e);
    }
  }

  /**
   * Finds the build to run: the one pinned in {@code corpus.yml}, or the newest stable one.
   *
   * <p>Against {@code fill.papermc.io/v3}, which replaced the older API. The response is
   * newest-first and carries the download URL and checksum directly, so nothing here has to guess a
   * URL shape.
   */
  @SuppressWarnings("unchecked")
  private PaperBuild resolveBuild(Corpus corpus) throws IOException, InterruptedException {
    URI uri =
        URI.create(
            "https://fill.papermc.io/v3/projects/paper/versions/"
                + corpus.minecraftVersion()
                + "/builds");
    HttpResponse<String> response =
        HttpClient.newHttpClient()
            .send(
                HttpRequest.newBuilder(uri).header("User-Agent", USER_AGENT).build(),
                HttpResponse.BodyHandlers.ofString());
    if (response.statusCode() != 200) {
      throw new IOException(
          "could not list Paper builds for "
              + corpus.minecraftVersion()
              + ": HTTP "
              + response.statusCode());
    }
    // JSON is valid YAML, so the parser already on the classpath reads it.
    List<Map<String, Object>> builds = new Yaml().load(response.body());
    if (builds == null || builds.isEmpty()) {
      throw new IOException("no Paper builds for " + corpus.minecraftVersion());
    }
    for (Map<String, Object> build : builds) {
      int id = ((Number) build.get("id")).intValue();
      boolean wanted =
          corpus.paperBuild() == null
              ? "STABLE".equals(build.get("channel"))
              : id == corpus.paperBuild();
      if (!wanted) {
        continue;
      }
      Map<String, Object> downloads = (Map<String, Object>) build.get("downloads");
      Map<String, Object> server = (Map<String, Object>) downloads.get("server:default");
      Map<String, Object> checksums = (Map<String, Object>) server.get("checksums");
      return new PaperBuild(
          id,
          String.valueOf(server.get("url")),
          String.valueOf(checksums.get("sha256")),
          String.valueOf(server.get("name")));
    }
    throw new IOException(
        corpus.paperBuild() == null
            ? "no stable Paper build for " + corpus.minecraftVersion()
            : "Paper build "
                + corpus.paperBuild()
                + " is not published for "
                + corpus.minecraftVersion());
  }

  private void pinBuild(int build) throws IOException {
    Path file = corpusRoot.resolve("corpus.yml");
    String text = Files.readString(file, StandardCharsets.UTF_8);
    if (text.contains("paperBuild:")) {
      text = text.replaceAll("(?m)^paperBuild:.*$", "paperBuild: " + build);
    } else {
      text = text.stripTrailing() + "\npaperBuild: " + build + "\n";
    }
    Files.writeString(file, text, StandardCharsets.UTF_8);
  }

  private void prepareServer(Corpus corpus, Path jar) throws IOException {
    Files.createDirectories(workDir.resolve("plugins"));
    Files.writeString(workDir.resolve("eula.txt"), "eula=true\n", StandardCharsets.UTF_8);
    Files.copy(
        copierJar,
        workDir.resolve("plugins").resolve(copierJar.getFileName()),
        StandardCopyOption.REPLACE_EXISTING);

    Map<String, String> properties = new LinkedHashMap<>();
    properties.put("level-seed", String.valueOf(corpus.seed()));
    properties.put("online-mode", "false");
    properties.put("spawn-protection", "0");
    // Nothing connects, and the corpus only wants terrain: keep the server from simulating any more
    // of the world than it must, so generation is what the time is spent on.
    properties.put("view-distance", "2");
    properties.put("simulation-distance", "2");
    properties.put("spawn-monsters", "false");
    properties.put("spawn-animals", "false");
    properties.put("spawn-npcs", "false");
    properties.put("allow-nether", "true");
    properties.put("sync-chunk-writes", "false");
    properties.put("motd", "stonebrick corpus");
    // Nothing ever connects to this server — commands go in over stdin — so binding the default
    // port buys nothing and collides with anything else on the machine, including a previous run
    // that has not finished shutting down. Zero asks the OS for a free one.
    properties.put("server-port", "0");
    StringBuilder text = new StringBuilder();
    properties.forEach((key, value) -> text.append(key).append('=').append(value).append('\n'));
    Files.writeString(
        workDir.resolve("server.properties"), text.toString(), StandardCharsets.UTF_8);

    // ⚠️ `level-seed` is read only when a world is first created. An existing world would silently
    // outrank a changed seed, and the corpus would then be generated from terrain nobody asked for
    // — a wrong answer that looks exactly like a right one. So the seed a world was made with is
    // recorded, and a mismatch discards it.
    Path stamp = workDir.resolve("world-seed.txt");
    String want = String.valueOf(corpus.seed());
    if (Files.isDirectory(workDir.resolve("world"))
        && (!Files.isRegularFile(stamp) || !Files.readString(stamp).trim().equals(want))) {
      System.out.println("The generated world is from a different seed; discarding it.");
      discardWorld();
    }
    Files.writeString(stamp, want + System.lineSeparator(), StandardCharsets.UTF_8);
  }

  /** Deletes the generated world, which is only ever a cache of what the seed already decides. */
  private void discardWorld() throws IOException {
    for (String name : List.of("world", "world_nether", "world_the_end")) {
      Path dir = workDir.resolve(name);
      if (!Files.isDirectory(dir)) {
        continue;
      }
      try (var walk = Files.walk(dir)) {
        for (Path path : walk.sorted(java.util.Comparator.reverseOrder()).toList()) {
          Files.deleteIfExists(path);
        }
      }
    }
  }

  private void runServer(List<Request> requests, Path jar)
      throws IOException, InterruptedException {
    ProcessBuilder builder =
        new ProcessBuilder(javaExecutable, "-Xms2G", "-Xmx4G", "-jar", jar.toString(), "--nogui")
            .directory(workDir.toFile())
            .redirectErrorStream(true);
    Process server = builder.start();

    AtomicBoolean booted = new AtomicBoolean();
    AtomicInteger finished = new AtomicInteger();
    AtomicBoolean failed = new AtomicBoolean();
    Thread reader =
        Thread.ofVirtual()
            .start(
                () -> {
                  try (BufferedReader out =
                      new BufferedReader(
                          new InputStreamReader(server.getInputStream(), StandardCharsets.UTF_8))) {
                    String line;
                    while ((line = out.readLine()) != null) {
                      System.out.println("  | " + line);
                      if (line.contains("Done (") && line.contains("For help, type")) {
                        booted.set(true);
                      }
                      // The copier already reports to the console, so the same stream that shows
                      // progress is the one that says a capture is over.
                      if (line.contains("Capture complete.")
                          || line.contains("Capture cancelled.")) {
                        finished.incrementAndGet();
                      }
                      if (line.contains("Failed to finish the capture")) {
                        failed.set(true);
                        finished.incrementAndGet();
                      }
                    }
                  } catch (IOException e) {
                    failed.set(true);
                  }
                });

    try (Writer console =
        new java.io.OutputStreamWriter(server.getOutputStream(), StandardCharsets.UTF_8)) {
      await(server, booted::get, BOOT_TIMEOUT_SECONDS, "the server to start");

      int done = 0;
      for (Request request : requests) {
        String command =
            "copier copy capsule %d %d %d %d %d --name %s --world %s --full --generate --overwrite"
                .formatted(
                    request.x1(),
                    request.z1(),
                    request.x2(),
                    request.z2(),
                    request.radius(),
                    request.capture(),
                    request.world());
        System.out.println("> " + command);
        console.write(command + "\n");
        console.flush();
        done++;
        int target = done;
        await(
            server,
            () -> finished.get() >= target,
            CAPTURE_TIMEOUT_SECONDS,
            "capture " + request.capture());
      }
      console.write("stop\n");
      console.flush();
    }

    if (!server.waitFor(120, TimeUnit.SECONDS)) {
      server.destroyForcibly();
    }
    reader.join(TimeUnit.SECONDS.toMillis(10));
    if (failed.get()) {
      throw new IOException("a capture failed; see the server output above");
    }
  }

  /**
   * Waits for something the server is meant to do, or fails.
   *
   * <p>Watches the process as well as the condition. A server that refuses to start — the wrong
   * Java, a port in use, a corrupt jar — otherwise costs the full timeout before saying anything,
   * and by then the reason is several screens up in its own output.
   */
  private static void await(
      Process server, java.util.function.BooleanSupplier ready, long seconds, String what)
      throws IOException, InterruptedException {
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(seconds);
    while (System.nanoTime() < deadline) {
      if (ready.getAsBoolean()) {
        return;
      }
      if (!server.isAlive()) {
        throw new IOException(
            "the server exited with code "
                + server.exitValue()
                + " while waiting for "
                + what
                + "; see its output above");
      }
      Thread.sleep(250);
    }
    throw new IOException("timed out after " + seconds + "s waiting for " + what);
  }

  /** Moves what the server captured into the corpus. */
  private void collect() throws IOException {
    Path out = workDir.resolve("plugins").resolve("StonebrickCopier").resolve("out");
    Path from = out.resolve(CorpusLayout.CAPTURES);
    if (!Files.isDirectory(from)) {
      throw new IOException("the server wrote no captures at " + from);
    }
    Path into = CorpusLayout.at(corpusRoot).captures();
    try (var walk = Files.walk(from)) {
      for (Path source : walk.filter(Files::isRegularFile).toList()) {
        Path target = into.resolve(from.relativize(source).toString());
        Files.createDirectories(target.getParent());
        Files.copy(source, target, StandardCopyOption.REPLACE_EXISTING);
      }
    }
    System.out.println("Captures collected into " + into);
  }

  /**
   * Entry point for the Gradle task.
   *
   * @param args corpus root, work directory, copier jar, eula flag, java executable
   * @throws Exception if provisioning fails
   */
  public static void main(String[] args) throws Exception {
    if (args.length < 5) {
      System.err.println(
          "usage: CorpusProvisioner <corpusRoot> <workDir> <copierJar> <eula> <javaExecutable>");
      System.exit(2);
      return;
    }
    new CorpusProvisioner(
            Path.of(args[0]),
            Path.of(args[1]),
            Path.of(args[2]),
            Boolean.parseBoolean(args[3]),
            args[4])
        .provision();
  }
}
