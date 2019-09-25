package it.bitrock.kafkaflightstream.producer.kafka

import akka.actor.ActorSystem
import akka.kafka.ProducerSettings
import akka.stream.ActorMaterializer
import akka.stream.scaladsl.Source
import akka.testkit.TestKit
import io.confluent.kafka.serializers.AbstractKafkaAvroSerDeConfig
import it.bitrock.kafkaflightstream.producer.TestValues
import it.bitrock.kafkaflightstream.producer.kafka.KafkaTypes.{Flight => KafkaTypesFlight}
import it.bitrock.kafkaflightstream.producer.model._
import it.bitrock.kafkageostream.kafkacommons.serialization.ImplicitConversions._
import it.bitrock.kafkageostream.testcommons.{FixtureLoanerAnyResult, Suite}
import net.manub.embeddedkafka.schemaregistry.{EmbeddedKafka, EmbeddedKafkaConfig, _}
import org.apache.kafka.common.serialization.{Serde, Serdes}
import org.scalatest.{BeforeAndAfterAll, WordSpecLike}

class KafkaFlightSinkFactorySpec
    extends TestKit(ActorSystem("KafkaFlightSinkFactorySpec"))
    with Suite
    with WordSpecLike
    with BeforeAndAfterAll
    with EmbeddedKafka
    with TestValues {
  import KafkaFlightSinkFactorySpec._

  implicit val mat: ActorMaterializer = ActorMaterializer()

  "sink method" should {

    "convert a domain model to Kafka model and push it to a topic" in ResourceLoaner.withFixture {
      case Resource(embeddedKafkaConfig, keySerde, factory) =>
        implicit val embKafkaConfig: EmbeddedKafkaConfig = embeddedKafkaConfig
        implicit val kSerde: Serde[KafkaTypesFlight.Key] = keySerde
        val result = withRunningKafka {
          Source.single(FlightMessage).runWith(factory.sink)
          consumeFirstKeyedMessageFrom[KafkaTypesFlight.Key, KafkaTypesFlight.Value](factory.topic)
        }
        result shouldBe (IcaoNumber, ExpectedFlightRaw)
    }

  }

  object ResourceLoaner extends FixtureLoanerAnyResult[Resource] {
    override def withFixture(body: Resource => Any): Any = {
      implicit val embeddedKafkaConfig: EmbeddedKafkaConfig = EmbeddedKafkaConfig()

      val outputTopic     = "output_topic"
      val rsvpRawKeySerde = Serdes.String
      val rsvpRawSerde    = specificAvroSerializer[KafkaTypesFlight.Value]

      val producerSettings = ProducerSettings(system, rsvpRawKeySerde.serializer, rsvpRawSerde)
        .withBootstrapServers(s"localhost:${embeddedKafkaConfig.kafkaPort}")
        .withProperty(
          AbstractKafkaAvroSerDeConfig.SCHEMA_REGISTRY_URL_CONFIG,
          s"http://localhost:${embeddedKafkaConfig.schemaRegistryPort}"
        )

      val factory = new KafkaSinkFactory[MessageJson, KafkaTypesFlight.Key, KafkaTypesFlight.Value](
        outputTopic,
        producerSettings
      )

      body(
        Resource(
          embeddedKafkaConfig,
          rsvpRawKeySerde,
          factory
        )
      )
    }
  }

  override def afterAll(): Unit = {
    shutdown()
    super.afterAll()
  }

}

object KafkaFlightSinkFactorySpec {

  final case class Resource(
      embeddedKafkaConfig: EmbeddedKafkaConfig,
      keySerde: Serde[KafkaTypesFlight.Key],
      factory: KafkaSinkFactory[MessageJson, KafkaTypesFlight.Key, KafkaTypesFlight.Value]
  )

}
