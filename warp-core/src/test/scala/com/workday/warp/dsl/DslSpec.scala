package com.workday.warp.dsl

import com.workday.warp.arbiters.{ArbiterLike, Ballot, RequirementViolationException}
import com.workday.warp.{HasRandomTestId, TestId, TrialResult}
import com.workday.warp.collectors.AbstractMeasurementCollector
import com.workday.warp.utils.Implicits._
import com.workday.warp.persistence.TablesLike._
import com.workday.warp.persistence._
import com.workday.warp.logger.WarpLogging
import com.workday.warp.dsl.using.measuring
import com.workday.warp.dsl.Implicits._
import com.workday.warp.junit.{UnitTest, WarpJUnitSpec}
import com.workday.warp.math.{DistributionLike, GaussianDistribution}
import com.workday.warp.TestIdImplicits.string2TestId
import com.workday.warp.controllers.AbstractMeasurementCollectionController
import org.junit.jupiter.api.parallel.Isolated
import org.scalatest.exceptions.TestFailedException

import scala.util.Try

/**
  * Created by tomas.mccandless on 3/25/16.
  */
@Isolated
class DslSpec extends WarpJUnitSpec with HasRandomTestId with WarpLogging {

  @UnitTest
  def dsl(): Unit = {
    using arbiters {
      new SomeArbiter
    } only these collectors {
      new SomeMeasurementCollector
    } measuring {
      someExperiment()
    } should notExceedThreshold (5000 milliseconds)
  }


  /** Checks usage of measuring multithreaded tests. */
  @UnitTest
  def dslThreads(): Unit = {
    val results: Seq[TrialResult[_]] = using no collectors threads 5 trials 5 measure someExperiment()
    results should have length 5
    results foreach { _.maybeResponseTime.get should be (5 seconds) }
    results should not exceed (5 seconds)
  }


  /** Checks that we get back the right kind of exception for tests that fail. */
  @UnitTest
  def dslThreadsException(): Unit = {
    // make sure we get back the right kind of exception, it shouldnt be a generic ExecutionException
    intercept[IllegalStateException] {
      using threads 5 trials 5 measure { throw new IllegalStateException }
    }

    // the same behavior should be present for warmup iterations
    intercept[IllegalStateException] {
      using warmups 5 trials 0 measure { throw new IllegalStateException }
    }

    // the same behavior should be present in single mode when a measured trial fails
    intercept[IllegalStateException] {
      using mode single threads 5 trials 5 measure { throw new IllegalStateException }
    }

    // the same behavior should be present for warmup iterations
    intercept[IllegalStateException] {
      using mode single threads 5 warmups 5 trials 0 measure { throw new IllegalStateException }
    }
  }


  /** Checks that we are only returned results for measured invocations. */
  @UnitTest
  def dslWarmups(): Unit = {
    val results: Seq[TrialResult[_]] = using no collectors threads 5 warmups 3 trials 10 measure someExperiment()
    results should have length 10
    results foreach { _.maybeResponseTime.get should be (5 seconds) }
  }


  /** Checks that measurement modes work correctly. */
  @UnitTest
  def dslMode(): Unit = {
    // running in multi mode (which is the default) should measure each invocation
    using threads 8 trials 8 mode multi measuring someExperiment() should have length 8
    using threads 8 trials 8 measuring someExperiment() should have length 8
    using threads 8 trials 8 warmups 8 measuring someExperiment() should have length 8

    // running in single mode should treat the entire schedule as a single logical test
    val singleResult: Seq[TrialResult[Int]] = using threads 8 trials 8 mode single measure someExperiment()
    singleResult should have length 1
    // even though the fake "experiment" we are measuring reports that it took 5 seconds, the overall time taken should be short
    singleResult should not exceed (500 milliseconds)
  }


  /** Checks that we can schedule with a statistical delay between successive submitted invocations. */
  @UnitTest
  def dslDistribution(): Unit = {
    val normal: DistributionLike = GaussianDistribution(50, 10)
    using no collectors trials 8 distribution normal measure { logger.trace("i'm being measured") }
    using no collectors trials 8 distribution normal threads 8 measure { logger.trace("i'm being measured on multiple threads") }

    // check using the default distribution -- the overall time should be very short since there is no delay
    val config: ExecutionConfig = using no collectors trials 8 threads 2 mode single
    config measuring { someExperiment() } should not exceed (100 millis)

    // using the above normal distribution should make the overall time a bit longer
    val result: Seq[TrialResult[Int]] = config distribution normal measure { 1 + 1 }
    result should have length 1
    result.head.maybeResponseTime.get should be > (200 millis)
  }


  /** Checks that a failure exception is thrown when we exceed threshold. */
  @UnitTest
  def dslFailure(): Unit = {
    intercept[TestFailedException] {
      using arbiters {
        new SomeArbiter :: new SomeOtherArbiter
      } only these collectors {
        new SomeMeasurementCollector
      } measuring {
        someExperiment()
      } should notExceedThreshold (2 nanoseconds)
    }
  }


  @UnitTest
  def dslOnlyArbiters(): Unit = {
    intercept[RequirementViolationException] {
      using no collectors onlyArbiters {
        new ExceptionArbiter
      } measuring {
        someExperiment()
      }
    }
  }


  /** Checks that we can configure collectors before arbiters. */
  @UnitTest
  def dslCollectorsFirst(): Unit = {
    using only these collectors {
      new SomeMeasurementCollector
    } arbiters {
      new SomeArbiter
    } measuring {
      someExperiment()
    } should notExceedThreshold (5 seconds)
  }


  /** Checks that we can use a default [[ExecutionConfig]] */
  @UnitTest
  def dslMeasuring(): Unit = {
    measuring {
      new TrialResult(499 millis)
    } should not exceed (500 millis)
  }


  /** Checks that we can disable all arbiters. */
  @UnitTest
  def noArbiters(): Unit = {
    using no arbiters measuring {
      someExperiment()
    }

    val config: ExecutionConfig = using no arbiters

    // make sure any existing arbiters are disabled and no new arbiters are registered
    config.disableExistingArbiters should be (true)
    config.additionalArbiters should be (empty)
    Researcher(config).collectionController().arbiters count { _.isEnabled } should be (0)
  }


  /** Checks that we can disable all collectors. */
  @UnitTest
  def noCollectors(): Unit = {
    using no collectors measuring {
      someExperiment()
    }

    val config: ExecutionConfig = using no collectors

    // make sure any existing collectors are disabled and no new collectors are registered
    config.disableExistingCollectors should be (true)
    config.additionalCollectors should be (empty)
    Researcher(config).collectionController().collectors count { _.isEnabled } should be (0)
  }


  /** Checks that we can disable all arbiters and collectors. */
  @UnitTest
  def noCollectorsNoArbiters(): Unit = {
    using no collectors no arbiters measuring {
      someExperiment()
    }

    val config: ExecutionConfig = using no collectors no arbiters
    val controller: AbstractMeasurementCollectionController = Researcher(config).collectionController()

    // make sure any existing arbiters are disabled and no new arbiters are registered
    config.disableExistingArbiters should be (true)
    config.additionalArbiters should be (empty)
    controller.arbiters count { _.isEnabled } should be (0)

    // make sure any existing collectors are disabled and no new collectors are registered
    config.disableExistingCollectors should be (true)
    config.additionalCollectors should be (empty)
    controller.collectors count { _.isEnabled } should be (0)
  }


  /** Checks that we can use anomaly detection */
  @UnitTest
  def anomalies(): Unit = {
    using trials 5 measuring {
      someExperiment()
    } should not be anomalous

    List.fill(30)(TrialResult(5 seconds)) should not be anomalous

    List.fill(15)(TrialResult(15 seconds)) ++ List.fill(15)(TrialResult(19 seconds)) should not be anomalous

    List.fill(29)(TrialResult(3 seconds)) ++ List(TrialResult(9 seconds)) shouldBe anomalous

    List.fill(15)(TrialResult(3 seconds)) ++ List(TrialResult(9 seconds)) ++
      List.fill(15)(TrialResult(3 seconds)) shouldBe anomalous

    List.fill(30)(TrialResult(5 seconds)) ++ List.fill(2)(TrialResult(13 seconds)) shouldBe anomalous

    intercept[TestFailedException] {
      List.fill(29)(TrialResult(10 seconds)) ++ List(TrialResult(25 seconds)) should not be anomalous
    }

    intercept[TestFailedException] {
      List.fill(35)(TrialResult(8 seconds)) shouldBe anomalous
    }
  }


  /** Checks that we can match using the not keyword. */
  @UnitTest
  def matching(): Unit = {
    Seq(TrialResult(2 milliseconds)) should notExceedThreshold (10 milliseconds)
    Seq(TrialResult(2 milliseconds)) should notExceed (10 milliseconds)
    Seq(TrialResult(5 milliseconds)) should not exceed (10 milliseconds)
    Seq(TrialResult(500 milliseconds)) should not exceed (0.5 seconds)

    intercept[TestFailedException] {
      Seq(TrialResult(20 milliseconds)) should notExceedThreshold(10 milliseconds)
    }

    intercept[TestFailedException] {
      Seq(TrialResult(20 milliseconds)) should notExceed (10 milliseconds)
    }

    intercept[TestFailedException] {
      Seq(TrialResult(501 milliseconds)) should not exceed (21 nanos)
    }

    intercept[TestFailedException] {
      Seq(TrialResult(501 milliseconds)) should not exceed (0.5 seconds)
    }


    Seq(TrialResult(5 seconds), TrialResult(6 seconds)) should notExceedThreshold (7 seconds)
    Seq(TrialResult(5 seconds), TrialResult(6 seconds)) should notExceed (7 seconds)
    Seq(TrialResult(5 seconds), TrialResult(6 seconds)) should not exceed (7 seconds)

    intercept[TestFailedException] {
      Seq(TrialResult(5 seconds), TrialResult(6 seconds)) should notExceedThreshold (5.5 seconds)
    }

    intercept[TestFailedException] {
      Seq(TrialResult(5 seconds), TrialResult(6 seconds)) should notExceed (5.5 seconds)
    }

    intercept[TestFailedException] {
      Seq(TrialResult(5 seconds), TrialResult(6 seconds)) should not exceed (5.5 seconds)
    }
  }

  /** Checks that we can match using different Seq subtypes */
  @UnitTest
  def seqMatching(): Unit = {
    List(TrialResult(2 milliseconds)) should notExceedThreshold (10 milliseconds)
    List(TrialResult(2 milliseconds)) should notExceed (10 milliseconds)
    List(TrialResult(5 milliseconds)) should not exceed (10 milliseconds)
    List(TrialResult(500 milliseconds)) should not exceed (0.5 seconds)

    Vector(TrialResult(2 milliseconds)) should notExceedThreshold (10 milliseconds)
    Vector(TrialResult(2 milliseconds)) should notExceed (10 milliseconds)
    Vector(TrialResult(5 milliseconds)) should not exceed (10 milliseconds)
    Vector(TrialResult(500 milliseconds)) should not exceed (0.5 seconds)
  }


  /** Checks that we can measure a function over multiple invocations. */
  @UnitTest
  def iterations(): Unit = {
    using trials 5 measuring {
      someExperiment()
    } should have length 5

    // check that invocations are passed through when we set arbiters and collectors
    using trials 5 only these collectors {
      new SomeMeasurementCollector
    } arbiters {
      new SomeArbiter
    } measuring {
      someExperiment()
    } should have length 5

    // check that invocations are passed through when we disable arbiters and collectors
    using trials 5 no arbiters no collectors measuring {
      someExperiment()
    } should have length 5
  }


  /** Checks that we can set test id manually. */
  @UnitTest
  def testIdCheck(): Unit = {
    // check that we can manually override the test id
    val someTestId: String = "com.workday.warp.dsl.test1"
    val config: ExecutionConfig = using testId someTestId
    Researcher(config).collectionController().testId.id should be (someTestId)
    config measure { 1 + 1 }
    ConfigStore.get(someTestId) should be (Some(config))
    Researcher(using trials 5 testId someTestId).collectionController().testId.id should be (someTestId)

    val randomTestId: TestId = this.randomTestId()
    Researcher(using testId randomTestId).collectionController().testId should be (randomTestId)

    // check that we handle empty string correctly
    Researcher(using testId "").collectionController().testId should be (TestId.undefined)
  }



  /** Checks the usage of syntax like `using only these collectors` or `using only these arbiters` */
  @UnitTest
  def onlyThese(): Unit = {
    val collector: AbstractMeasurementCollector = new SomeMeasurementCollector
    // the only arbiter we'll use is one that always votes on failure
    val arbiter: ArbiterLike = new ExceptionArbiter

    // the wrapped ExecutionConfig inside ResultOfOnlyThese should be the same as the one we started with
    (using only these).config should be (using)
    val someConfig: ExecutionConfig = using testId "com.workday.warp.dsl" trials 8
    (someConfig only these).config should be (someConfig)

    // check the actual dsl usage
    val config: ExecutionConfig = using only these collectors {
      collector
    } only these arbiters {
      arbiter
    }

    val config2: ExecutionConfig = using onlyCollectors {
      collector
    } onlyArbiters {
      arbiter
    }

    // check syntax equivalence
    config should be (config2)
    config.disableExistingArbiters should be (true)
    config.disableExistingCollectors should be (true)

    // make sure the collectors and arbiters are correctly configured
    val controller: AbstractMeasurementCollectionController = Researcher(config).collectionController()
    controller.enabledCollectors should (have length 1 and contain only collector)
    controller.enabledArbiters should (have length 1 and contain only arbiter)

    // we expect an exception to be thrown by the configured arbiter
    intercept[RequirementViolationException] {
      config measuring {
        someExperiment()
      }
    }
  }

  /** Checks syntax usage and functionality of tags. */
  @UnitTest
  def testTags(): Unit = {
    // check tags implicits compile correctly
    "val tags: List[Tag] = List(DefinitionTag(\"some instance id\", \"some value\"))" should compile
    "using tags {" +
      "DefinitionTag(\"some instance id\", \"some value\")" +
      "}" should compile
    "using tags {" +
      "DefinitionTag(\"some instance id\", \"some value\") :: " +
      "DefinitionTag(\"some other key\", \"some other value\")" +
      "}" should compile

    // checks params are passed in correctly to Measurement Controller
    val someTags: List[Tag] =
      List(DefinitionTag("some instance id", "some value"), ExecutionTag("some other key", "some other value"))
    // checking the implicit
    DefinitionTag("instance id 1", "some value")
    val someConfig: ExecutionConfig = using testId "com.workday.warp.dsl" tags someTags
    Researcher(someConfig).collectionController().tags should be (someTags)
  }


  /** Checks that we can measure with a default configuration. */
  @UnitTest
  def onlyDefaults(): Unit = {
    using only defaults measuring someExperiment() should not exceed (5 seconds)
    using only defaults should be (new ExecutionConfig)
  }



  /** Checks that we can measure tests with different return types. */
  @UnitTest
  def correctType(): Unit = {
    // check measuring something that returns a TrialResult
    val someExperimentList: Seq[TrialResult[Int]] = using no collectors measuring someExperiment()
    someExperimentList.head.maybeResponseTime.get should be (5 seconds)
    someExperimentList.head.maybeResult.get should be (5)

    // check measuring something that returns a Try[TrialResult]
    val tryExperimentList: Seq[TrialResult[Int]] = using no collectors warmups 1 measuring Try(someExperiment())
    tryExperimentList.head.maybeResponseTime.get should be (5 seconds)
    tryExperimentList.head.maybeResult.get should be (5)

    // check measuring something returns a TrialResult[_] (No result)
    val voidExperimentList: Seq[TrialResult[_]] = using no collectors measuring voidExperiment()
    voidExperimentList.head.maybeResult should be (None)

    // check an arbitrary other return types
    val stringExperimentList: Seq[TrialResult[String]] = using no collectors measuring { "hello" }
    stringExperimentList should not exceed (500 millis)
    stringExperimentList.length should be (1)

    // An insidious error that will not get caught at compile time.
    // This function's return value is a String, but we are storing it as Int
    // Due to runtime casting, an exception will not be thrown until the value is unboxed.
    intercept[ClassCastException] {
      val badCastList: Seq[TrialResult[Int]] = using no collectors measuring { "hello" }
      badCastList.length should be (1)
      // Unbox value. Should throw ClassCastException
      badCastList.head.maybeResult.get
    }
  }


  /** @return a [[TrialResult]] that took 5 seconds with a result of 5. */
  def someExperiment(): TrialResult[Int] = TrialResult(maybeResponseTime = Some(5 seconds), maybeResult = Some(5))

  /** @return a [[TrialResult]] that took 5 seconds with no result. */
  def voidExperiment(): TrialResult[_] = TrialResult(maybeResponseTime = Some(5 seconds))

  // some dummy arbiters and collectors to use in testing
  class SomeArbiter extends ArbiterLike with CorePersistenceAware {
    override def vote[T: TestExecutionRowLikeType](ballot: Ballot, testExecution: T): Option[Throwable] = {
      logger.debug("some arbiter voting")
      None
    }
  }

  class SomeOtherArbiter extends ArbiterLike with CorePersistenceAware {
    override def vote[T: TestExecutionRowLikeType](ballot: Ballot, testExecution: T): Option[Throwable] = {
      logger.debug("some other arbiter voting")
      None
    }
  }

  class ExceptionArbiter extends ArbiterLike with CorePersistenceAware {
    override def vote[T: TestExecutionRowLikeType](ballot: Ballot, testExecution: T): Option[Throwable] =
      Option(new RequirementViolationException("the test failed"))
  }


  class SomeMeasurementCollector extends AbstractMeasurementCollector {
    /**
      * Called prior to starting an individual test invocation.
      */
    override def startMeasurement(): Unit = logger.debug("starting measurement")

    /**
      * Called after finishing an individual test invocation.
      *
      * @param maybeTestExecution     Optional field. If the test execution is None the client should
      *                          not attempt to write out to the database.
      */
    override def stopMeasurement[T: TestExecutionRowLikeType](maybeTestExecution: Option[T]): Unit = {
      logger.debug("stopping measurement")
    }
  }

  class SomeOtherMeasurementCollector extends AbstractMeasurementCollector {
    /**
      * Called prior to starting an individual test invocation.
      */
    override def startMeasurement(): Unit = logger.debug("starting measurement")

    /**
      * Called after finishing an individual test invocation.
      *
      * @param maybeTestExecution     Optional field. If the test execution is None the client should
      *                          not attempt to write out to the database.
      */
    override def stopMeasurement[T: TestExecutionRowLikeType](maybeTestExecution: Option[T]): Unit = {
      logger.debug("stopping measurement")
    }
  }
}
