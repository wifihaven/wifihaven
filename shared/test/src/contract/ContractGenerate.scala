package wifihaven.shared.contract

import java.nio.file.{Files, Path, Paths}

// Regenerate the API → router golden contract fixtures (#634).
//
// Run via:   mill shared.test.runMain wifihaven.shared.contract.ContractGenerate
// Or (with the lua half too): scripts/regen-contract-fixtures.sh
//
// Writes shared/contract/api-to-router/*.json from the in-process Scala
// values in ContractFixtures, serialized through the production zio-json
// codecs. The router-to-api/*.json fixtures are produced separately by the
// lua agent — see openwrt/test/contract_gen.lua. Splitting the producers
// this way means each side's wire drift surfaces as a diff in its own
// fixture, rather than being smoothed over by the API's codec acting as
// both producer and consumer.
//
// After regenerating, inspect the diff — every change should be intentional
// (the corresponding consumer code on the other side must be updated in the
// same PR).
object ContractGenerate {

  def main(args: Array[String]): Unit = {
    val root = locateContractDir()
    println(s"Regenerating api-to-router fixtures under: $root")

    var n = 0
    for ((name, body) <- ContractFixtures.apiToRouter) {
      writeFile(root.resolve("api-to-router").resolve(name), body)
      n += 1
    }
    println(s"Wrote $n api-to-router fixture(s).")
    // Force exit so the mill `runMain` subprocess returns 0 even if a
    // transitive dependency leaves a non-daemon thread alive past main
    // (#674). ContractGoldenSpec exercises the same codecs inside the test
    // runner without issue, so the hang is specific to the forked
    // `runMain` JVM's shutdown. TODO(#675): remove once root cause found.
    System.exit(0)
  }

  private def writeFile(path: Path, body: String): Unit = {
    Files.createDirectories(path.getParent)
    Files.writeString(path, body)
    println(s"  wrote ${path}")
  }

  private def locateContractDir(): Path = {
    val start    = Paths.get(sys.props.getOrElse("user.dir", "."))
    var cur      = start.toAbsolutePath
    while (cur != null) {
      val cand = cur.resolve("shared/contract")
      if (Files.isDirectory(cand)) return cand
      cur = cur.getParent
    }
    // Fall back to creating it under the repo root (one level up from
    // wherever this main happens to run).
    val fallback = start.toAbsolutePath.resolve("shared/contract")
    Files.createDirectories(fallback)
    fallback
  }
}
