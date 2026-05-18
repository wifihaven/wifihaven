package wifihaven.shared.contract

import java.nio.file.{Files, Path, Paths}

// Regenerate every golden contract fixture (#634).
//
// Run via:   mill shared.test.runMain wifihaven.shared.contract.ContractGenerate
// Or the wrapper script: scripts/regen-contract-fixtures.sh
//
// Writes both halves of the contract from the *same* in-process Scala values
// (ContractFixtures) using the production zio-json codecs. The
// router-to-api/*.json files are intentionally generated from the API's
// own codec so they start life as a valid round-trip; once committed, any
// future codec drift that would change the serialized shape will break
// ContractGoldenSpec's round-trip assertion.
//
// After regenerating, inspect the diff — every change should be intentional
// (the corresponding consumer code on the other side must be updated in the
// same PR).
object ContractGenerate {

  def main(args: Array[String]): Unit = {
    val root = locateContractDir()
    println(s"Regenerating contract fixtures under: $root")

    var n = 0
    for ((name, body) <- ContractFixtures.apiToRouter) {
      writeFile(root.resolve("api-to-router").resolve(name), body)
      n += 1
    }
    for ((name, body) <- ContractFixtures.routerToApi) {
      writeFile(root.resolve("router-to-api").resolve(name), body)
      n += 1
    }
    println(s"Wrote $n fixture(s).")
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
