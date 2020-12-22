package com.uptech.windalerts.infrastructure.beaches

import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.TimeZone

import cats.data.EitherT
import cats.effect.Sync
import cats.{Applicative, Functor}
import com.softwaremill.sttp._
import com.uptech.windalerts.domain.UnknownError
import com.uptech.windalerts.domain.domain.SurfsUpEitherT
import com.uptech.windalerts.domain.swellAdjustments.Adjustments
import com.uptech.windalerts.infrastructure.beaches.Swells.Swell
import com.uptech.windalerts.infrastructure.endpoints
import com.uptech.windalerts.infrastructure.endpoints.domain.BeachId
import com.uptech.windalerts.status.SwellsService
import io.circe.generic.semiauto.{deriveDecoder, deriveEncoder}
import io.circe.optics.JsonPath._
import io.circe.{Decoder, Encoder, parser}
import org.http4s.circe.{jsonEncoderOf, jsonOf}
import org.http4s.{EntityDecoder, EntityEncoder}
import org.log4s.getLogger


class WillyWeatherSwellsService[F[_] : Sync](apiKey: String, adjustments: Adjustments)(implicit backend: SttpBackend[Id, Nothing], F: Functor[F]) extends SwellsService[F] {
  private val logger = getLogger

  def get(beachId: BeachId): SurfsUpEitherT[F, com.uptech.windalerts.infrastructure.endpoints.domain.Swell] =
    EitherT.fromEither(getFromWillyWeatther(apiKey, beachId))

  def getFromWillyWeatther(apiKey: String, beachId: BeachId): Either[UnknownError, com.uptech.windalerts.infrastructure.endpoints.domain.Swell] = {
    logger.error(s"Fetching swell status for $beachId")

    val body = sttp.get(uri"https://api.willyweather.com.au/v2/$apiKey/locations/${beachId.id}/weather.json?forecasts=swell&days=1").send().body
    val either = body
      .left.map(UnknownError(_))
      .flatMap(parser.parse(_))
      .map(root.forecasts.swell.days.each.entries.each.json.getAll(_))
      .left.map(e => UnknownError(e.getMessage))
    either
      .flatMap(
        _.flatMap(j => j.as[Swell].toSeq.filter(isCurrentHour(body, _)))
          .map(swell => swell.copy(height = adjustments.adjust(swell.height)))
          .headOption.toRight(UnknownError("Empty response from WW")))
      .map(swell => endpoints.domain.Swell(swell.height, swell.direction, swell.directionText))
      .orElse(Right(endpoints.domain.Swell(Double.NaN, Double.NaN, "NA")))
  }

  private def isCurrentHour(body: Either[String, String], s: Swell) = {
    val sdf = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
    val eitherTimezone: Either[Exception, String] =
      body.left.map(new RuntimeException(_)).flatMap(parser.parse(_)).flatMap(_.hcursor.downField("location").downField("timeZone").as[String])
    eitherTimezone match {
      case Left(_) => false
      case Right(tz) => {
        val timeZone = TimeZone.getTimeZone(tz)

        val entry = LocalDateTime.parse(s.dateTime, sdf).atZone(timeZone.toZoneId).withZoneSameInstant(TimeZone.getDefault.toZoneId)

        entry.getHour == LocalDateTime.now().getHour
      }
    }
  }
}

object Swells {


  case class Swell(
                    dateTime: String,
                    direction: Double,
                    directionText: String,
                    height: Double,
                    period: Double
                  )

  object Swell {
    implicit val swellDecoder: Decoder[Swell] = deriveDecoder[Swell]

    implicit def swellEntityDecoder[F[_] : Sync]: EntityDecoder[F, Swell] =
      jsonOf

    implicit val swellEncoder: Encoder[Swell] = deriveEncoder[Swell]

    implicit def swellEntityEncoder[F[_] : Applicative]: EntityEncoder[F, Swell] =
      jsonEncoderOf
  }


}