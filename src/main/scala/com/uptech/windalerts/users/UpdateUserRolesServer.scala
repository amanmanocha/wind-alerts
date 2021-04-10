package com.uptech.windalerts.users

import cats.effect.{IO, _}
import cats.implicits._
import com.softwaremill.sttp.HttpURLConnectionBackend
import com.uptech.windalerts.LazyRepos
import com.uptech.windalerts.core.credentials.UserCredentialService
import com.uptech.windalerts.core.otp.OTPService
import com.uptech.windalerts.core.user.{AuthenticationService, UserRolesService, UserService}
import com.uptech.windalerts.infrastructure.endpoints.logger._
import com.uptech.windalerts.infrastructure.endpoints.{UpdateUserRolesEndpoints, errors}
import com.uptech.windalerts.infrastructure.social.subscriptions.SubscriptionsServiceImpl
import org.http4s.implicits._
import org.http4s.server.Router
import org.http4s.server.blaze.BlazeServerBuilder
import org.http4s.server.middleware.Logger
import org.log4s.getLogger

object UpdateUserRolesServer extends IOApp {

  implicit val backend = HttpURLConnectionBackend()

  override def run(args: List[String]): IO[ExitCode] = for {
    _ <- IO(getLogger.error("Starting"))

    repos = new LazyRepos()
    authService <- IO(new AuthenticationService(repos))
    otpService <-  IO(new OTPService[IO](repos))
    userCredentialsService <- IO(new UserCredentialService[IO](repos))
    userService <- IO(new UserService[IO](repos, userCredentialsService, otpService,authService))
    subscriptionsService <- IO(new SubscriptionsServiceImpl[IO](repos))
    userRolesService <- IO(new UserRolesService[IO](repos, subscriptionsService, userService))
    endpoints <- IO(new UpdateUserRolesEndpoints[IO](userRolesService))


    httpApp <- IO(errors.errorMapper(Logger.httpApp(true, true, logAction = requestLogger)(
      Router(
        "/v1/users/roles" -> endpoints.endpoints(),
      ).orNotFound)))
    server <- BlazeServerBuilder[IO]
      .bindHttp(sys.env("PORT").toInt, "0.0.0.0")
      .withHttpApp(httpApp)
      .serve
      .compile
      .drain
      .as(ExitCode.Success)

  } yield server

}
