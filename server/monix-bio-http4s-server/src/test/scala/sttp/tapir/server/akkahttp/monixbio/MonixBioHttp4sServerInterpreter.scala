package sttp.tapir.server.akkahttp.monixbio

import cats.data.{Kleisli, NonEmptyList}
import cats.effect._
import cats.syntax.all._
import cats.~>
import monix.bio.{IO => BIO, _}
import BIO._
import monix.bio.instances.CatsConcurrentForTask
import monix.execution.Scheduler
import org.http4s.server.blaze.BlazeServerBuilder
import org.http4s.syntax.kleisli._
import org.http4s.{HttpRoutes, Request, Response}
import sttp.capabilities.WebSockets
import sttp.capabilities.fs2.Fs2Streams
import sttp.tapir.monixbio._
import sttp.tapir.server.http4s._
import sttp.tapir.server.http4s.monixbio._
import sttp.tapir.server.tests.TestServerInterpreter
import sttp.tapir.server._
import sttp.tapir.tests.Port

import scala.concurrent.ExecutionContext
import scala.reflect.ClassTag

class MonixBioHttp4sServerInterpreter extends TestServerInterpreter[Task, Fs2Streams[Task] with WebSockets, HttpRoutes[Task]] {

  implicit val ec: ExecutionContext = scala.concurrent.ExecutionContext.Implicits.global
  implicit val contextShift: ContextShift[IO] = IO.contextShift(ec)
  implicit val timer: Timer[IO] = IO.timer(ec)
  implicit val scheduler: Scheduler = monix.execution.Scheduler.global

  override def route[I, E, O](
      e: MonixBioServerEndpoint[I, E, O, Fs2Streams[Task] with WebSockets],
      decodeFailureHandler: Option[DecodeFailureHandler]
  ): HttpRoutes[Task] = {
    implicit val serverOptions: Http4sServerOptions[Task] = Http4sServerOptions
      .default[Task]
      .copy(
        decodeFailureHandler = decodeFailureHandler.getOrElse(ServerDefaults.decodeFailureHandler)
      )
    e.toRoutes
  }

  override def routeRecoverErrors[I, E <: Throwable, O](e: MonixBioEndpoint[I, E, O, Fs2Streams[Task] with WebSockets], fn: I => Task[O])(
      implicit eClassTag: ClassTag[E]
  ): HttpRoutes[Task] = {
    e.toRouteRecoverErrors(fn)
  }

  override def server(routes: NonEmptyList[HttpRoutes[Task]]): Resource[IO, Port] = {
    val service: Kleisli[Task, Request[Task], Response[Task]] = routes.reduceK.orNotFound

    val server = BlazeServerBuilder[Task](ExecutionContext.global)
      .bindHttp(0, "localhost")
      .withHttpApp(service)
      .resource
      .map(_.address.getPort)
    server.mapK(λ[Task ~> IO](_.to[IO]))
  }
}
