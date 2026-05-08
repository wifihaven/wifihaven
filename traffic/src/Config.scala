package familydns.traffic

import familydns.api.DbConfig
import zio.*
import zio.config.*
import zio.config.magnolia.*
import zio.config.typesafe.*

case class TrafficAppConfig(
    db: DbConfig,
    traffic: TrafficConfig,
)

object TrafficAppConfig {
  val layer: ZLayer[Any, Config.Error, TrafficAppConfig] =
    ZLayer.fromZIO {
      val path = sys.props.getOrElse("config.file", "config/application.conf")
      read(
        deriveConfig[TrafficAppConfig]
          .nested("familydns")
          .from(
            TypesafeConfigProvider.fromHoconFile(new java.io.File(path)),
          ),
      )
    }
}
