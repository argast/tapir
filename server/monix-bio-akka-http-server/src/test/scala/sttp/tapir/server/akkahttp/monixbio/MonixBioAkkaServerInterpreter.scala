package sttp.tapir.server.akkahttp.monixbio

import akka.actor.ActorSystem
import akka.http.scaladsl.Http
import akka.http.scaladsl.server.Directives.concat
import akka.http.scaladsl.server.Route
import cats.data.NonEmptyList
import cats.effect.{IO, Resource}
import cats.syntax.all._
import monix.bio.Task
import monix.execution.Scheduler
import sttp.tapir.monixbio._
import sttp.tapir.server.akkahttp.AkkaHttpServerOptions
import sttp.tapir.server.tests.TestServerInterpreter
import sttp.tapir.server.{DecodeFailureHandler, ServerDefaults}
import sttp.tapir.tests.Port

import scala.reflect.ClassTag

class MonixBioAkkaServerInterpreter(implicit actorSystem: ActorSystem, s: Scheduler) extends TestServerInterpreter[Task, Any, Route] {

  override def route[I, E, O](
      e: MonixBioServerEndpoint[I, E, O, Any],
      decodeFailureHandler: Option[DecodeFailureHandler] = None
  ): Route = {
    implicit val serverOptions: AkkaHttpServerOptions = AkkaHttpServerOptions.default.copy(
      decodeFailureHandler = decodeFailureHandler.getOrElse(ServerDefaults.decodeFailureHandler)
    )
    e.toRoute
  }

  override def routeRecoverErrors[I, E <: Throwable, O](e: MonixBioEndpoint[I, E, O, Any], fn: I => Task[O])(implicit
      eClassTag: ClassTag[E]
  ): Route = e.toRouteRecoverErrors(fn)

  override def server(routes: NonEmptyList[Route]): Resource[IO, Port] = {
    val bind = IO.fromFuture(IO(Http().newServerAt("localhost", 0).bind(concat(routes.toList: _*))))
    Resource.make(bind)(binding => IO.fromFuture(IO(binding.unbind())).void).map(_.localAddress.getPort)
  }
}
