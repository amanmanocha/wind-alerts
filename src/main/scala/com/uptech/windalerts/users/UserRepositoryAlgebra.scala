package com.uptech.windalerts.users


import cats.data.{EitherT, OptionT}
import cats.effect.{ContextShift, IO}
import com.uptech.windalerts.domain.{UserNotFoundError, SurfsUpError, domain}
import com.uptech.windalerts.domain.domain.UserT
import com.uptech.windalerts.domain.domain.UserType.{Premium, Trial}
import org.mongodb.scala.MongoCollection
import org.mongodb.scala.bson.ObjectId
import org.mongodb.scala.bson.conversions.Bson
import org.mongodb.scala.model.Filters.{and, equal, lt}
import org.mongodb.scala.model.Updates.set

import scala.concurrent.ExecutionContext.Implicits.global

trait UserRepositoryAlgebra[F[_]] {

  def getByUserIdEitherT(userId: String): EitherT[F, Exception, UserT]

  def getByUserId(userId: String): F[Option[UserT]]

  def getByEmailAndDeviceType(email: String, deviceType: String): F[Option[UserT]]

  def create(user: UserT): F[UserT]

  def update(user: UserT): OptionT[F, UserT]

  def updateDeviceToken(userId: String, deviceToken: String): OptionT[F, Unit]

  def findTrialExpiredUsers(): EitherT[F, SurfsUpError, Seq[UserT]]

  def findAndroidPremiumExpiredUsers(): EitherT[F, SurfsUpError, Seq[UserT]]

  def findApplePremiumExpiredUsers(): EitherT[F, SurfsUpError, Seq[UserT]]
}


class MongoUserRepository(collection: MongoCollection[UserT])(implicit cs: ContextShift[IO]) extends UserRepositoryAlgebra[IO] {
  override def getByUserIdEitherT(userId: String): EitherT[IO, Exception, UserT] = {
    OptionT(getByUserId(userId)).toRight(UserNotFoundError())
  }

  override def getByUserId(userId: String): IO[Option[UserT]] = {
    findByCriteria(equal("_id", new ObjectId(userId)))
  }


  override def getByEmailAndDeviceType(email: String, deviceType: String): IO[Option[UserT]] = {
    findByCriteria(and(equal("email", email), equal("deviceType", deviceType)))
  }

  override def create(user: UserT): IO[UserT] = {
    IO.fromFuture(IO(collection.insertOne(user).toFuture().map(_ => user)))
  }

  override def update(user: domain.UserT): OptionT[IO, domain.UserT] = {
    OptionT.liftF(
      for {
        updateResultIO <- IO.fromFuture(IO(collection.replaceOne(equal("_id", user._id), user).toFuture()))
        updatedUser <- getByUserId(user._id.toHexString).map(u=>u.get)
      } yield updatedUser)
  }

  override def updateDeviceToken(userId: String, deviceToken: String): OptionT[IO, Unit] = {
    OptionT.liftF(IO.fromFuture(IO(collection.updateOne(equal("_id", new ObjectId(userId)), set("deviceToken", deviceToken)).toFuture().map(_ => ()))))
  }

  override def findTrialExpiredUsers(): EitherT[IO, SurfsUpError, Seq[UserT]] = {
    EitherT.liftF(findAllByCriteria(and(equal("userType", Trial.value),
      lt("endTrialAt", System.currentTimeMillis())
    )))
  }

  private def findByCriteria(criteria: Bson) = {
    findAllByCriteria(criteria).map(_.headOption)
  }

  private def findAllByCriteria(criteria: Bson) =
    IO.fromFuture(IO(collection.find(criteria).toFuture()))

  override def findAndroidPremiumExpiredUsers(): EitherT[IO, SurfsUpError, Seq[UserT]] = {
    EitherT.liftF(findAllByCriteria(
      and(equal("userType", Premium.value),
        equal("deviceType", "ANDROID"),
        lt("nextPaymentAt", System.currentTimeMillis())
      )))
  }

  override def findApplePremiumExpiredUsers(): EitherT[IO, SurfsUpError, Seq[UserT]] = {
    EitherT.liftF(findAllByCriteria(
      and(
        equal("userType", Premium.value),
        equal("deviceType", "IOS"),
        lt("nextPaymentAt", System.currentTimeMillis())
      )))
  }
}
