package sttp.tapir.server.akkahttp.monixbio

import akka.actor.ActorSystem
import akka.http.scaladsl.server.Directives
import akka.http.scaladsl.server.Directives._
import akka.stream.scaladsl.Flow
import cats.data.NonEmptyList
import cats.effect.{IO, Resource}
import cats.implicits._
import monix.bio.{Task, IO => BIO}
import monix.execution.Scheduler
import org.scalatest.matchers.should.Matchers._
import sttp.capabilities.akka.AkkaStreams
import sttp.client3._
import sttp.monad.{FutureMonad, MonadError}
import sttp.tapir.monixbio._
import sttp.tapir.server.akkahttp.monixbio._
import sttp.tapir.server.tests._
import sttp.tapir.tests.{Test, TestSuite}
import sttp.tapir.{endpoint, stringBody, _}

class AkkaHttpMonixBioServerTests extends TestSuite {

  implicit lazy val scheduler: Scheduler = monix.execution.Scheduler.global

  def actorSystemResource: Resource[IO, ActorSystem] =
    Resource.make(IO.delay(ActorSystem()))(actorSystem => IO.fromFuture(IO.delay(actorSystem.terminate())).void)

  override def tests: Resource[IO, List[Test]] = backendResource.flatMap { backend =>
    actorSystemResource.map { implicit actorSystem =>
      implicit val m: MonadError[Task] = TaskMonadError
      val interpreter = new MonixBioAkkaServerInterpreter()(actorSystem, scheduler)
      val createServerTest = new CreateServerTest(interpreter)

      def authLogic(user: String) = if (user == "admin") BIO.pure("admin") else BIO.raiseError("unauthorized")

      def additionalTests(): List[Test] = List(
        Test("server endpoint created using bioServerLogic") {
          val e =
            endpoint.get
              .in("test")
              .in(query[String]("result"))
              .errorOut(stringBody)
              .out(stringBody)
              .bioServerLogic {
                case "error" => BIO.raiseError("error")
                case "fail"  => BIO.terminate(new Exception("failure"))
                case _       => BIO.pure("ok")
              }

          interpreter
            .server(NonEmptyList.of(e.toRoute))
            .use { port =>
              basicRequest.get(uri"http://localhost:$port/test?result=ok").send(backend).map(_.body shouldBe Right("ok")) >>
                basicRequest.get(uri"http://localhost:$port/test?result=error").send(backend).map(_.body shouldBe Left("error")) >>
                basicRequest
                  .get(uri"http://localhost:$port/test?result=fail")
                  .send(backend)
                  .handleErrorWith(e => IO.pure(sttp.client3.Response.ok(Left(e.getMessage))))
                  .map(_.body shouldEqual Left("There was an internal server error."))
            }
            .unsafeRunSync
        },
        Test("partial server endpoint created using bioServerLogicForCurrent") {
          val partialEndpoint = endpoint
            .in(header[String]("X-USER"))
            .errorOut(stringBody)
            .bioServerLogicForCurrent(authLogic)

          val e = partialEndpoint.get.in("test").out(stringBody).serverLogic { case (user, _) => BIO.pure(s"welcome $user") }
          interpreter
            .server(NonEmptyList.of(e.toRoute))
            .use { port =>
              basicRequest
                .get(uri"http://localhost:$port/test")
                .header("X-USER", "admin")
                .send(backend)
                .map(_.body shouldBe Right("welcome admin")) >>
                basicRequest
                  .get(uri"http://localhost:$port/test")
                  .header("X-USER", "guest")
                  .send(backend)
                  .map(_.body shouldBe Left("unauthorized"))
            }
            .unsafeRunSync
        },
        Test("partial server endpoint created using bioServerLogicPart") {
          val partialEndpoint = endpoint
            .in(header[String]("X-USER"))
            .errorOut(stringBody)
            .get
            .in("test")
            .out(stringBody)

          val e = partialEndpoint.bioServerLogicPart(authLogic(_)).andThen { case (user, _) => BIO.pure(s"welcome $user") }
          interpreter
            .server(NonEmptyList.of(e.toRoute))
            .use { port =>
              basicRequest
                .get(uri"http://localhost:$port/test")
                .header("X-USER", "admin")
                .send(backend)
                .map(_.body shouldBe Right("welcome admin")) >>
                basicRequest
                  .get(uri"http://localhost:$port/test")
                  .header("X-USER", "guest")
                  .send(backend)
                  .map(_.body shouldBe Left("unauthorized"))
            }
            .unsafeRunSync
        }
      )
      new ServerBasicTests(backend, createServerTest, interpreter).tests() ++
//        new ServerStreamingTests(backend, createServerTest, AkkaStreams).tests() ++
//        new ServerWebSocketTests(backend, createServerTest, AkkaStreams) {
//          override def functionToPipe[A, B](f: A => B): streams.Pipe[A, B] = Flow.fromFunction(f)
//        }.tests() ++
        additionalTests()
    }
  }
}
