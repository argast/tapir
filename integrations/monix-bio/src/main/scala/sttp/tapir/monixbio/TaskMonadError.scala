package sttp.tapir.monixbio

import monix.bio.Cause._
import monix.bio._
import sttp.monad.MonadError

object TaskMonadError extends MonadError[Task] {
  override def unit[T](t: T): Task[T] = IO.pure(t)
  override def map[T, T2](fa: Task[T])(f: T => T2): Task[T2] = fa.map(f)
  override def flatMap[T, T2](fa: Task[T])(f: T => Task[T2]): Task[T2] = fa.flatMap(f)
  override def error[T](t: Throwable): Task[T] = IO.raiseError(t)
  override def eval[T](t: => T): Task[T] = IO.eval(t)
  override protected def handleWrappedError[T](rt: Task[T])(h: PartialFunction[Throwable, Task[T]]): Task[T] =
    rt.redeemCauseWith(
      recover = {
        case Error(t)       => h(t)
        case Termination(t) => h(t)
      },
      bind = IO.pure
    )
  override def ensure[T](f: Task[T], e: => Task[Unit]): Task[T] = f.guarantee(e.hideErrors)
}
