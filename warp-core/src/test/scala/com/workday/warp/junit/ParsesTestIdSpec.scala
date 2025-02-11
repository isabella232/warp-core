package com.workday.warp.junit

import com.workday.warp.logger.WarpLogging

/**
  * Created by tomas.mccandless on 6/17/20.
  */
class ParsesTestIdSpec extends WarpJUnitSpec with ParsesTestId with WarpLogging {

  @WarpTest(warmups = 2, trials = 5)
  def testIdFromUniqueId(): Unit = {
    logger.info("running test!")
    // scalastyle:off
    fromUniqueId("[engine:junit-jupiter]/[class:com.workday.warp.junit.MeasurementCallbacksSpec]/[method:foo()]") should
      be (Some("com.workday.warp.junit.MeasurementCallbacksSpec.foo"))
    // repeated invocations will have [<invocation num>] appended
    fromUniqueId("[engine:junit-jupiter]/[class:com.workday.warp.junit.MeasurementCallbacksSpec]/[method:foo()][9]") should
      be (Some("com.workday.warp.junit.MeasurementCallbacksSpec.foo"))
    // test methods can accept parameters
    fromUniqueId( "[engine:junit-jupiter]/[class:com.workday.warp.junit.MeasurementExtensionSpec]/[method:fooWithTestInfo(org.junit.jupiter.api.TestInfo)]" ) should
      be (Some("com.workday.warp.junit.MeasurementExtensionSpec.fooWithTestInfo"))
    fromUniqueId("[engine:junit-jupiter]/[class:com.workday.warp.junit.TestIdSupportSpec]/[test-template:testIdFromUniqueId()]/[test-template-invocation:#5]") should
      be (Some("com.workday.warp.junit.TestIdSupportSpec.testIdFromUniqueId"))
    // scalastyle:on
  }
}
