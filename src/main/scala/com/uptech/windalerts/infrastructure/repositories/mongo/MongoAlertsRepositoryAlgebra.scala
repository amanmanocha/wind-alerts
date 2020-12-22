package com.uptech.windalerts.infrastructure.repositories.mongo

import cats.data.EitherT
import cats.effect.{ContextShift, IO}
import com.uptech.windalerts.alerts.domain.AlertT
import com.uptech.windalerts.alerts.{AlertsRepositoryT}
import com.uptech.windalerts.domain.{SurfsUpError, conversions}
import com.uptech.windalerts.infrastructure.endpoints.domain.AlertRequest
import io.scalaland.chimney.dsl._
import org.mongodb.scala.MongoCollection
import org.mongodb.scala.bson.ObjectId
import org.mongodb.scala.bson.conversions.Bson
import org.mongodb.scala.model.Filters.equal

import scala.concurrent.ExecutionContext.Implicits.global

class MongoAlertsRepositoryAlgebra(collection: MongoCollection[AlertT])(implicit cs: ContextShift[IO]) extends AlertsRepositoryT[IO] {


  private def findByCriteria(criteria: Bson) =
    IO.fromFuture(IO(collection.find(criteria).toFuture()))

  override def disableAllButOneAlerts(userId: String): IO[Seq[AlertT]] = {
    for {
      all <- getAllForUser(userId)
      updatedIOs <- IO({
        all.filter(_.enabled) match {
          case Seq() => List[IO[AlertT]]()
          case Seq(only) => List[IO[AlertT]](IO(only))
          case longSeq => longSeq.tail.map(alert => update(alert._id.toHexString, alert.copy(enabled = false)))
        }
      }
      )
      updatedAlerts <- conversions.toIOSeq(updatedIOs)
    } yield updatedAlerts
  }

  private def update(alertId: String, alert: AlertT): IO[AlertT] = {
    IO.fromFuture(IO(collection.replaceOne(equal("_id", new ObjectId(alertId)), alert).toFuture().map(_ => alert)))
  }

  def getById(id: String): IO[Option[AlertT]] = {
    findByCriteria(equal("_id", new ObjectId(id))).map(_.headOption)
  }

  override def getAllForUser(user: String): IO[Seq[AlertT]] = {
    findByCriteria(equal("owner", user))
  }


  override def getAllEnabled(): IO[Seq[AlertT]]  = {
    findByCriteria( equal("enabled", true))
  }

  override def save(alertRequest: AlertRequest, user: String): IO[AlertT] = {
    val alert = AlertT(alertRequest, user)

    IO.fromFuture(IO(collection.insertOne(alert).toFuture().map(_ => alert)))
  }

  override def delete(requester: String, alertId: String): EitherT[IO, SurfsUpError, Unit] = {
    EitherT.liftF(IO.fromFuture(IO(collection.deleteOne(equal("_id", new ObjectId(alertId))).toFuture().map(_=>()))))
  }

  override def update(requester: String, alertId: String, updateAlertRequest: AlertRequest):EitherT[IO, SurfsUpError, AlertT] = {
    val alertUpdated = updateAlertRequest.into[AlertT].withFieldComputed(_._id, u => new ObjectId(alertId)).withFieldComputed(_.owner, _ => requester).transform
    for {
      _ <- EitherT.liftF(IO(collection.replaceOne(equal("_id", new ObjectId(alertId)), alertUpdated).toFuture()))
      alert <-  EitherT.liftF(getById(alertId)).map(alertOption=>alertOption.get)
    } yield alert
  }

}