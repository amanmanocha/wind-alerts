package com.uptech.windalerts.core.refresh.tokens

import cats.data.{EitherT, OptionT}
import com.uptech.windalerts.core.TokenNotFoundError

trait RefreshTokenRepository[F[_]] {
  def create(refreshToken: RefreshToken): F[RefreshToken]

  def getByAccessTokenId(accessTokenId: String): OptionT[F, RefreshToken]

  def getByRefreshToken(refreshToken: String): OptionT[F, RefreshToken]

  def deleteForUserId(uid: String): F[Unit]

  def updateExpiry(id: String, expiry: Long): EitherT[F, TokenNotFoundError, RefreshToken]
}