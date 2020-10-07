package sttp.tapir.monixbio

import sttp.tapir.internal.{ParamsAsAny, mkCombine, _}
import sttp.tapir.typelevel.ParamConcat
import sttp.tapir._
import monix.bio._
import sttp.tapir.server.ServerEndpoint

/** An endpoint, with some of the server logic already provided, and some left unspecified.
  *
  * The part of the server logic which is provided transforms some inputs either to an error of type `E`, or value of
  * type `U`.
  *
  * The part of the server logic which is not provided, transforms a tuple: `(U, I)` either into an error, or a value
  * of type `O`.
  *
  * Inputs/outputs can be added to partial endpoints as to regular endpoints, however the shape of the error outputs
  * is fixed and cannot be changed.
  *
  * @tparam U Type of partially transformed input.
  * @tparam I Input parameter types.
  * @tparam E Error output parameter types.
  * @tparam O Output parameter types.
  */
abstract class MonixBioPartialServerEndpoint[U, I, E, O, -R](val endpoint: MonixBioEndpoint[I, E, O, R])
    extends EndpointInputsOps[I, E, O, R]
    with EndpointOutputsOps[I, E, O, R]
    with EndpointInfoOps[I, E, O, R]
    with EndpointMetaOps[I, E, O, R] { outer =>
  // original type of the partial input (transformed into U)
  type T
  protected def tInput: EndpointInput[T]
  protected def partialLogic: T => IO[E, U]

  override type EndpointType[_I, _E, _O, -_R] = MonixBioPartialServerEndpoint[U, _I, _E, _O, _R]

  override def input: EndpointInput[I] = endpoint.input
  def errorOutput: EndpointOutput[E] = endpoint.errorOutput
  override def output: EndpointOutput[O] = endpoint.output
  override def info: EndpointInfo = endpoint.info

  private def withEndpoint[I2, O2, R2 <: R](
      e2: MonixBioEndpoint[I2, E, O2, R2]
  ): MonixBioPartialServerEndpoint[U, I2, E, O2, R2] =
    new MonixBioPartialServerEndpoint[U, I2, E, O2, R2](e2) {
      override type T = outer.T
      override protected def tInput: EndpointInput[T] = outer.tInput
      override protected def partialLogic: T => IO[E, U] = outer.partialLogic
    }
  override private[tapir] def withInput[I2, R2](
      input: EndpointInput[I2]
  ): MonixBioPartialServerEndpoint[U, I2, E, O, R with R2] =
    withEndpoint(endpoint.withInput(input))
  override private[tapir] def withOutput[O2, R2](output: EndpointOutput[O2]) = withEndpoint(endpoint.withOutput(output))
  override private[tapir] def withInfo(info: EndpointInfo) = withEndpoint(endpoint.withInfo(info))

  override protected def additionalInputsForShow: Vector[EndpointInput.Basic[_]] = tInput.asVectorOfBasicInputs()
  override protected def showType: String = "PartialServerEndpoint"

  def serverLogicForCurrent[V, UV](
      f: I => IO[E, V]
  )(implicit concat: ParamConcat.Aux[U, V, UV]): MonixBioPartialServerEndpoint[UV, Unit, E, O, R] =
    new MonixBioPartialServerEndpoint[UV, Unit, E, O, R](endpoint.copy(input = emptyInput)) {
      override type T = (outer.T, I)
      override def tInput: EndpointInput[(outer.T, I)] = outer.tInput.and(outer.endpoint.input)
      override def partialLogic: ((outer.T, I)) => IO[E, UV] = { case (t, i) =>
        outer.partialLogic(t).flatMap { u =>
          f(i).map { v =>
            mkCombine(concat).apply(ParamsAsAny(u), ParamsAsAny(v)).asAny.asInstanceOf[UV]
          }
        }
      }
    }

  def serverLogic(g: ((U, I)) => IO[E, O]): MonixBioServerEndpoint[(T, I), E, O, R] =
    ServerEndpoint(
      endpoint.prependIn(tInput): MonixBioEndpoint[(T, I), E, O, R],
      _ => { case (t, i) =>
        partialLogic(t).flatMap(u => g((u, i))).redeem(Left(_), Right(_))
      }
    )
}
