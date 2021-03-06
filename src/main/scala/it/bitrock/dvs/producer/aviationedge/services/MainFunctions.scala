package it.bitrock.dvs.producer.aviationedge.services

import akka.Done
import akka.actor.{ActorSystem, Cancellable}
import akka.http.scaladsl.Http
import it.bitrock.dvs.producer.aviationedge.config.{
  ApiProviderConfig,
  AppConfig,
  AviationConfig,
  KafkaConfig,
  OpenSkyConfig,
  ServerConfig
}
import it.bitrock.dvs.producer.aviationedge.routes.Routes
import it.bitrock.dvs.producer.aviationedge.services.Graphs._
import it.bitrock.dvs.producer.aviationedge.services.JsonSupport._
import it.bitrock.dvs.producer.aviationedge.services.context.{
  ApiProviderStreamContext,
  AviationStreamContext,
  OpenSkyStreamContext
}

import scala.concurrent.{ExecutionContext, Future}

object MainFunctions {
  val serverConfig: ServerConfig           = AppConfig.server
  val apiProviderConfig: ApiProviderConfig = AppConfig.apiProvider
  val aviationConfig: AviationConfig       = apiProviderConfig.aviationEdge
  val openSkyConfig: OpenSkyConfig         = apiProviderConfig.openSky
  val kafkaConfig: KafkaConfig             = AppConfig.kafka

  def bindRoutes()(implicit system: ActorSystem): Future[Http.ServerBinding] = {
    val host   = serverConfig.host
    val port   = serverConfig.port
    val routes = new Routes(serverConfig)
    Http().bindAndHandle(routes.routes, host, port)
  }

  def runAviationEdgeStream[A: ApiProviderStreamContext]()(
      implicit system: ActorSystem,
      ec: ExecutionContext
  ): (Cancellable, Future[Done]) = {
    val config = AviationStreamContext[A].config(apiProviderConfig)

    val tickSource = new TickSource(config.pollingStart, config.pollingInterval, aviationConfig.tickSource).source
    val aviationFlow =
      new ApiProviderFlow().flow(aviationConfig.aviationUri(config.path), aviationConfig.apiTimeout)(
        aviationEdgePayloadJsonReader
      )
    val rawSink           = AviationStreamContext[A].sink(kafkaConfig)
    val errorSink         = SideStreamContext.errorSink(kafkaConfig)
    val invalidFlightSink = SideStreamContext.invalidSink(kafkaConfig)
    val monitoringSink    = SideStreamContext.monitoringSink(kafkaConfig)

    val jsonSource = tickSource.via(aviationFlow).via(monitoringGraph(monitoringSink)).mapConcat(identity)

    mainGraph(jsonSource, rawSink, errorSink, invalidFlightSink).mapMaterializedValue(combineCompletionValue).run()
  }

  def runOpenSkyStream[A: ApiProviderStreamContext]()(
      implicit system: ActorSystem,
      ec: ExecutionContext
  ): (Cancellable, Future[Done]) = {
    val config = OpenSkyStreamContext[A].config(apiProviderConfig)

    val tickSource = new TickSource(config.pollingStart, config.pollingInterval, openSkyConfig.tickSource).source
    val openSkyFlow = new ApiProviderFlow().flow(openSkyConfig.openSkyUri(config.path), openSkyConfig.apiTimeout)(
      openSkyResponsePayloadJsonFormat
    )
    val rawSink = AviationStreamContext[A].sink(kafkaConfig)

    val jsonSource = tickSource.via(openSkyFlow).mapConcat(identity)

    collectRightMessagesGraph(jsonSource, rawSink).run()
  }

  private def combineCompletionValue[T](value: (Cancellable, Future[T], Future[T], Future[T]))(implicit ec: ExecutionContext) =
    value match {
      case (cancel, one, two, three) => (cancel, Future.firstCompletedOf(List(one, two, three)))
    }
}
