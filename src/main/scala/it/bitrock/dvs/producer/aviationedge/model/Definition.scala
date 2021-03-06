package it.bitrock.dvs.producer.aviationedge.model

import java.time.Instant

sealed trait ApiProviderStream

sealed trait AviationStream extends ApiProviderStream
case object FlightStream    extends AviationStream
case object AirplaneStream  extends AviationStream
case object AirportStream   extends AviationStream
case object AirlineStream   extends AviationStream
case object CityStream      extends AviationStream

sealed trait OpenSkyStream    extends ApiProviderStream
case object FlightStateStream extends OpenSkyStream

case class Tick()

case class ErrorMessageJson(
    errorSource: String,
    errorMessage: String,
    failedJson: String,
    timestamp: Instant
)

case class MonitoringMessageJson(
    messageReceivedOn: Instant,
    minUpdated: Option[Instant],
    maxUpdated: Option[Instant],
    averageUpdated: Option[Instant],
    numErrors: Int,
    numValid: Int,
    numInvalid: Int,
    total: Int
)

object PartitionPorts {
  final val RawPort     = 0
  final val ErrorPort   = 1
  final val InvalidPort = 2
}
