package wifihaven.api.db

import zio.*

// #706: dev-only fixtures. Previously V11 migration seeded `test_ads` /
// `test_social` blocklists into every database including prod, which leaked
// into router policy snapshots. V26 deletes those rows unconditionally;
// this seeder re-creates them only when WIFIHAVEN_ENV=dev so local stacks
// and ephemeral staging keep their fixtures.
object DevSeeder {

  private val TestBlocklistDomains: List[(String, String)] = List(
    "adserver.example.com" -> "test_ads",
    "doubleclick.net"      -> "test_ads",
    "googleadservices.com" -> "test_ads",
    "facebook.com"         -> "test_social",
    "instagram.com"        -> "test_social",
    "tiktok.com"           -> "test_social",
  )

  def seedTestBlocklists(blRepo: BlocklistRepo): Task[Unit] =
    blRepo.insertBatch(TestBlocklistDomains).unit
}
