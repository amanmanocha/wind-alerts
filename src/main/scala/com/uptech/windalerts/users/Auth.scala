package com.uptech.windalerts.users

import java.util.concurrent.TimeUnit

import cats.data.EitherT
import cats.effect.IO
import com.uptech.windalerts.domain.domain
import com.uptech.windalerts.domain.domain.{RefreshToken, TokensWithUser, User, UserId}
import dev.profunktor.auth.JwtAuthMiddleware
import dev.profunktor.auth.jwt.{JwtAuth, JwtSecretKey}
import io.circe.parser._
import pdi.jwt.{Jwt, JwtAlgorithm, JwtClaim}

import scala.util.Random

class Auth(refreshTokenRepositoryAlgebra: RefreshTokenRepositoryAlgebra) {
  val REFRESH_TOKEN_EXPIRY = 7L * 24L * 60L * 60L * 1000L

  case class AccessTokenWithExpiry(accessToken: String, expiredAt: Long)

  private val key = JwtSecretKey("secretKey")
  val jwtAuth = JwtAuth(key, JwtAlgorithm.HS256)
  val authenticate: JwtClaim => IO[Option[UserId]] = {
        claim => {
          val r = for {
            parseResult <- IO.fromEither(parse(claim.content))
            accessTokenId <- IO.fromEither(parseResult.hcursor.downField("accessTokenId").as[String])
            maybeRefreshToken <- refreshTokenRepositoryAlgebra.getByAccessTokenId(accessTokenId).value
          } yield maybeRefreshToken

          r.map(f=>f.map(t=>UserId(t.userId)))
        }
      }

  val middleware = JwtAuthMiddleware[IO, UserId](jwtAuth, authenticate)


  def createToken(userId: String, expirationInMinutes: Int, accessTokenId: String): EitherT[IO, ValidationError, AccessTokenWithExpiry] = {
    val current = System.currentTimeMillis()
    val expiry = current / 1000 + TimeUnit.MINUTES.toSeconds(expirationInMinutes)
    val claims = JwtClaim(
      expiration = Some(expiry),
      issuedAt = Some(current / 1000),
      issuer = Some("wind-alerts.com"),
      subject = Some(userId)
    ) + ("accessTokenId", accessTokenId)

    EitherT.right(IO(AccessTokenWithExpiry(Jwt.encode(claims, key.value, JwtAlgorithm.HS256), expiry)))
  }

  def tokens(accessToken: String, refreshToken: RefreshToken, expiredAt: Long, user:User): EitherT[IO, ValidationError, TokensWithUser] =
    EitherT.right(IO(domain.TokensWithUser(accessToken, refreshToken.refreshToken, expiredAt, user)))

  def generateRandomString(n: Int) = {
    val alpha = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789"
    val size = alpha.size

    (1 to n).map(_ => alpha(Random.nextInt.abs % size)).mkString
  }
}