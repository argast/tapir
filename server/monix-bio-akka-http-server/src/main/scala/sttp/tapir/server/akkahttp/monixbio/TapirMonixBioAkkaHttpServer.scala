package sttp.tapir.server.akkahttp.monixbio

import scala.concurrent.Future
import scala.reflect.ClassTag
import akka.http.scaladsl.server.Route
import sttp.tapir.server.ServerEndpoint
import monix.bio._
import sttp.tapir.monixbio._
import monix.execution.Scheduler
import sttp.capabilities.WebSockets
import sttp.capabilities.akka.AkkaStreams
import sttp.tapir.server.akkahttp._

trait TapirMonixBioAkkaHttpServer {

  implicit class RichAkkaHttpMonixEndpoint[I, E, O](e: MonixBioEndpoint[I, E, O, AkkaStreams with WebSockets])(implicit
      serverOptions: AkkaHttpServerOptions,
      s: Scheduler
  ) {
    def toDirective = new EndpointToAkkaServer(serverOptions).toDirective(e)

    def toRoute(logic: I => IO[E, O]): Route =
      new EndpointToAkkaServer(serverOptions).toRoute(toFutureServerEndpoint(e.bioServerLogic(logic)))

    def toRouteRecoverErrors(
        logic: I => Task[O]
    )(implicit serverOptions: AkkaHttpServerOptions, eIsThrowable: E <:< Throwable, eClassTag: ClassTag[E], s: Scheduler): Route = {
      new EndpointToAkkaServer(serverOptions).toRoute(toFutureServerEndpoint(e.serverLogicRecoverErrors(logic)))
    }
  }

  implicit class RichAkkaHttpMonixServerEndpoint[I, E, O](serverEndpoint: MonixBioServerEndpoint[I, E, O, AkkaStreams with WebSockets])(
      implicit
      serverOptions: AkkaHttpServerOptions,
      s: Scheduler
  ) {
    def toDirective =
      new EndpointToAkkaServer(serverOptions).toDirective(serverEndpoint.endpoint)

    def toRoute: Route =
      new EndpointToAkkaServer(serverOptions).toRoute(toFutureServerEndpoint(serverEndpoint))
  }

  implicit class RichAkkaHttpMonixServerEndpoints(serverEndpoints: List[MonixBioServerEndpoint[_, _, _, AkkaStreams with WebSockets]]) {
    def toRoute(implicit serverOptions: AkkaHttpServerOptions, s: Scheduler): Route = {
      new EndpointToAkkaServer(serverOptions).toRoute(serverEndpoints.map(toFutureServerEndpoint(_)))
    }
  }

  private def toFutureServerEndpoint[I, E, O](
      serverEndpoint: MonixBioServerEndpoint[I, E, O, AkkaStreams with WebSockets]
  )(implicit s: Scheduler): ServerEndpoint[I, E, O, AkkaStreams with WebSockets, Future] = {
    ServerEndpoint(
      serverEndpoint.endpoint,
      _ => i => serverEndpoint.logic(TaskMonadError)(i).runToFuture
    )
  }
}
