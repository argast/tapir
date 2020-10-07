package sttp.tapir.server.http4s.monixbio

import monix.bio._
import org.http4s.HttpRoutes
import sttp.capabilities.WebSockets
import sttp.capabilities.fs2.Fs2Streams
import sttp.tapir.monixbio._
import sttp.tapir.server.http4s.{EndpointToHttp4sServer, Http4sServerOptions}

trait TapirMonixBioHttp4sServer {

  implicit class RichMonixBioRoutes[I, E, O](e: MonixBioEndpoint[I, E, O, Fs2Streams[Task] with WebSockets]) {

    def toRoutes(logic: I => IO[E, O])(implicit serverOptions: Http4sServerOptions[Task]): HttpRoutes[Task] =
      e.bioServerLogic(logic).toRoutes
  }

  implicit class RichMonixBioServerEndpoint[I, E, O](serverEndpoint: MonixBioServerEndpoint[I, E, O, Fs2Streams[Task] with WebSockets]) {
    def toRoutes(implicit serverOptions: Http4sServerOptions[Task]): HttpRoutes[Task] = List(serverEndpoint).toRoutes
  }

  implicit class RichMonixBioEndpointsRoutes[I, E, O](
      serverEndpoints: List[MonixBioServerEndpoint[_, _, _, Fs2Streams[Task] with WebSockets]]
  ) {
    def toRoutes(implicit serverOptions: Http4sServerOptions[Task]): HttpRoutes[Task] = {
      new EndpointToHttp4sServer(serverOptions).toRoutes(serverEndpoints)
    }
  }
}
