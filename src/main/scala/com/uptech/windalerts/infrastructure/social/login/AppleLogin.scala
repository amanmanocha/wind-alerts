package com.uptech.windalerts.infrastructure.social.login

import cats.Monad
import cats.effect.{Async, ContextShift}
import cats.implicits._
import com.softwaremill.sttp.{HttpURLConnectionBackend, sttp, _}
import com.turo.pushy.apns.auth.ApnsSigningKey
import com.uptech.windalerts.core.social.login.{AppleAccessRequest, SocialLogin, SocialUser}
import com.uptech.windalerts.infrastructure.endpoints.codecs._
import com.uptech.windalerts.infrastructure.endpoints.dtos.{AppleUser, TokenResponse}
import io.circe.parser
import org.log4s.getLogger
import pdi.jwt._

import java.io.File
import java.security.PrivateKey
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future

class AppleLogin[F[_]](filename: String)(implicit cs: ContextShift[F], s: Async[F], M: Monad[F]) extends SocialLogin[F, AppleAccessRequest] {
  val privateKey = getPrivateKey(filename)
  override def fetchUserFromPlatform(credentials: AppleAccessRequest): F[SocialUser] = {
    fetchUserFromPlatform_(credentials)
  }

  private def fetchUserFromPlatform_(credentials: AppleAccessRequest) = {
    Async.fromFuture(M.pure(Future(getUser(credentials.authorizationCode))))
      .map(appleUser => SocialUser(appleUser.sub, appleUser.email, credentials.deviceType, credentials.deviceToken, credentials.name))
  }

  def getUser(authorizationCode: String): AppleUser = {
    getUser(authorizationCode, privateKey)
  }

  private def getUser(authorizationCode: String, privateKey: PrivateKey): AppleUser = {
    val req = sttp.body(Map(
      "client_id" -> "com.passiondigital.surfsup.ios",
      "client_secret" -> generateJWT(privateKey),
      "grant_type" -> "authorization_code",
      "code" -> authorizationCode,
    ))
      .post(uri"https://appleid.apple.com/auth/token?scope=email")

    implicit val backend = HttpURLConnectionBackend()

    val responseBody = req.send().body
    getLogger.error(responseBody.toString)
    val tokenResponse = responseBody.flatMap(parser.parse(_)).flatMap(x => x.as[TokenResponse]).right.get
    val claims = Jwt.decode(tokenResponse.id_token, JwtOptions(signature = false))
    val parsedEither = parser.parse(claims.toOption.get.content)
    getLogger.error(claims.toOption.get.content)
    parsedEither.flatMap(x => x.as[AppleUser]).right.get
  }

  private def generateJWT(privateKey:PrivateKey) = {
    val current = System.currentTimeMillis()
    val claims = JwtClaim(
      issuer = Some("W9WH7WV85S"),
      audience = Some(Set("https://appleid.apple.com")),
      subject = Some("com.passiondigital.surfsup.ios"),
      expiration = Some(System.currentTimeMillis() / 1000 + (60 * 5)),
      issuedAt = Some(current / 1000)
    )
    val header = JwtHeader(JwtAlgorithm.ES256).withType(null).withKeyId("A423X8QGF3")
    Jwt.encode(header.toJson, claims.toJson, privateKey, JwtAlgorithm.ES256)
  }


  private def getPrivateKey(filename: String) = {
    ApnsSigningKey.loadFromPkcs8File(new File(filename), "W9WH7WV85S", "A423X8QGF3")
  }

}