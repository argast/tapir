package sttp.tapir.server.akkahttp.monixbio

import cats.data.{Kleisli, NonEmptyList}
import cats.effect._
import cats.syntax.all._
import cats.~>
import monix.bio.{IO => BIO, _}
import org.http4s.HttpRoutes
import org.http4s.server.blaze.BlazeServerBuilder
import org.http4s.syntax.kleisli._
import sttp.capabilities.WebSockets
import sttp.capabilities.fs2.Fs2Streams
import sttp.client3._
import sttp.monad.MonadError
import org.scalatest.matchers.should.Matchers._
import sttp.tapir._
import sttp.tapir.monixbio._
import sttp.tapir.server.http4s._
import sttp.tapir.server.tests._
import sttp.tapir.tests._

class Http4sMonixBioServerTests[R >: Fs2Streams[Task] with WebSockets] extends TestSuite {

  override def tests: Resource[IO, List[Test]] = backendResource.map { backend =>
    implicit val m: MonadError[Task] = TaskMonadError
    val interpreter = new MonixBioHttp4sServerInterpreter()
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
          .server(NonEmptyList.of(e.toRoutes))
          .use { port =>
            basicRequest.get(uri"http://localhost:$port/test?result=ok").send(backend).map(_.body shouldBe Right("ok")) >>
              basicRequest.get(uri"http://localhost:$port/test?result=error").send(backend).map(_.body shouldBe Left("error")) >>
              basicRequest
                .get(uri"http://localhost:$port/test?result=fail")
                .send(backend)
                .handleErrorWith(e => IO.pure(sttp.client3.Response.ok(Left(e.getMessage))))
                .map(_.body shouldBe Left(s"Exception when sending request: GET http://localhost:$port/test?result=fail"))
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
          .server(NonEmptyList.of(e.toRoutes))
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
          .server(NonEmptyList.of(e.toRoutes))
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
      new ServerStreamingTests(backend, createServerTest, Fs2Streams[Task]).tests() ++
      new ServerWebSocketTests(backend, createServerTest, Fs2Streams[Task]) {
        override def functionToPipe[A, B](f: A => B): streams.Pipe[A, B] = in => in.map(f)
      }.tests() ++
      additionalTests()
  }

}
