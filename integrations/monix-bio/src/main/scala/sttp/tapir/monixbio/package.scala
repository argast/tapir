package sttp.tapir

import sttp.tapir.server.ServerEndpoint
import monix.bio._
import sttp.tapir.internal.split
import sttp.tapir.typelevel.ParamSubtract

package object monixbio {
//  type MonixBioEndpoint[I, E, O] = Endpoint[I, E, O, Any]
  type MonixBioEndpoint[I, E, O, -R] = Endpoint[I, E, O, R]
//  type MonixBioServerEndpoint[I, E, O] = ServerEndpoint[I, E, O, Any, Task]
  type MonixBioServerEndpoint[I, E, O, -R] = ServerEndpoint[I, E, O, R, Task]

  implicit class RichMonixEndpoint[I, E, O, -R](e: MonixBioEndpoint[I, E, O, R]) {
    def bioServerLogic(logic: I => IO[E, O]): MonixBioServerEndpoint[I, E, O, R] = {
      ServerEndpoint(e, _ => logic(_).redeem(Left(_), Right(_)))
    }

    def bioServerLogicPart[T, J, U](
        f: T => IO[E, U]
    )(implicit iMinusT: ParamSubtract.Aux[I, T, J]): MonixBioServerEndpointInParts[U, J, I, E, O, R] = {
      type _T = T
      new MonixBioServerEndpointInParts[U, J, I, E, O, R](e) {
        override type T = _T
        override def splitInput: I => (T, J) = i => split(i)(iMinusT)
        override def logicFragment: _T => IO[E, U] = f
      }
    }

    def bioServerLogicForCurrent[U](f: I => IO[E, U]): MonixBioPartialServerEndpoint[U, Unit, E, O, R] =
      new MonixBioPartialServerEndpoint[U, Unit, E, O, R](e.copy(input = emptyInput)) {
        override type T = I
        override def tInput: EndpointInput[T] = e.input
        override def partialLogic: T => IO[E, U] = f
      }
  }

}
