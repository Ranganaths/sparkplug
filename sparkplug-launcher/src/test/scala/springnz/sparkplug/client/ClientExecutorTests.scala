package springnz.sparkplug.client

import org.scalatest._
import springnz.sparkplug.util.Logging

import scala.concurrent.duration._
import scala.concurrent.{ Await, Future }
import scala.util.Try

trait ClientExecutableFixture extends BeforeAndAfterEach { this: Suite ⇒

  var executor: ClientExecutor = null

  override def beforeEach() {
    try executor = ClientExecutor.create()
    finally super.beforeEach()
  }

  override def afterEach() {
    try super.afterEach()
    finally {
      executor.shutDown()
      // it seems to need to be give a chance to clean up (I think shutdown happens asnychronously)
    }
  }
}

class ClientExecutorTests extends WordSpec with ShouldMatchers with Logging with ClientExecutableFixture with Inspectors {
  implicit val ec = scala.concurrent.ExecutionContext.global

  "client executor" should {
    "Calculate a single job" in {
      val future = executor.execute[Any]("springnz.sparkplug.executor.LetterCountPlugin", None)
      val result = Await.result(future, 30.seconds)
      result shouldBe ((2, 2))
    }

    "Handle an error in a job request" in {
      val future = executor.execute[Any]("springnz.sparkplug.executor.InvalidPluginName", None)
      intercept[ClassNotFoundException] {
        Await.result(future, 30.seconds)
      }
    }

    "Still carry on working after a failure" in {
      val futureError = executor.execute[Any]("springnz.sparkplug.executor.InvalidPluginName", None)
      intercept[ClassNotFoundException] {
        Await.result(futureError, 30.seconds)
      }
      val futureOk = executor.execute[Any]("springnz.sparkplug.executor.LetterCountPlugin", None)
      val result = Await.result(futureOk, 30.seconds)
      result shouldBe ((2, 2))
    }

    "Calculate a sequence of job requests in parallel" in {
      val futures: List[Future[Any]] = List.fill(10) { executor.execute[Any]("springnz.sparkplug.executor.LetterCountPlugin", None) }

      implicit val ec = scala.concurrent.ExecutionContext.global
      val sequence: Future[List[Any]] = Future.sequence(futures)
      val results = Await.result(sequence, 30.seconds)

      // this way of executing does not return anything
      results shouldBe a[List[_]]

      results.length shouldBe 10

      forAll(results) {
        result ⇒ result shouldBe ((2, 2))
      }
    }

    "Handle immediate timeout" in {
      val future = executor.execute[Any]("springnz.sparkplug.executor.WaitPlugin", None)
      Try {
        Await.result(future, 0.seconds)
      }

    }

  }
}

class ClientExecutorSingleTests extends WordSpec with ShouldMatchers with Logging {
  "client executor" should {
    "Calculate a single job" in {
      implicit val ec = scala.concurrent.ExecutionContext.global
      val future = ClientExecutor[(Long, Long)]("springnz.sparkplug.executor.LetterCountPlugin", None)
      val result = Await.result(future, 30.seconds)
      result shouldBe ((2, 2))
    }

  }
}
