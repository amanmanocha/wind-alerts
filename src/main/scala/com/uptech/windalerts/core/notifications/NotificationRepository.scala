package com.uptech.windalerts.core.notifications

import cats.data.EitherT
import com.uptech.windalerts.domain.domain.{ UserWithCount}

trait NotificationRepository[F[_]] {
  def create(notifications: Notification): F[Notification]
  def countNotificationInLastHour(userId: String): EitherT[F, Exception, UserWithCount]
}