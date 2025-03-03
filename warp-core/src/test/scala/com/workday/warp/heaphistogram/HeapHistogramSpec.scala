package com.workday.warp.heaphistogram

import java.io.InputStream
import com.workday.warp.{HasRandomTestId, TestId}
import com.workday.warp.collectors._
import com.workday.warp.controllers.{AbstractMeasurementCollectionController, DefaultMeasurementCollectionController}
import com.workday.warp.junit.{UnitTest, WarpJUnitSpec}
import org.junit.jupiter.api.TestInfo
import org.junit.jupiter.api.parallel.Isolated

/**
  * Tests for the Histogram-related methods
  *
  * Created by vignesh.kalidas on 4/6/18.
  */
@Isolated
class HeapHistogramSpec extends WarpJUnitSpec with HistogramIoLike with HasRandomTestId {

  /**
    * Gets the heap histogram
    */
  @UnitTest
  def getHeapHistogramSpec(): Unit = {
    case class Cat()
    List.fill(1000)(new Cat)
    // seems that sporadically, reading heap histogram will not reflect the changes of our Cat memory allocation
    Thread.sleep(1000)

    val histogram: Seq[HeapHistogramEntry] = getHeapHistogram
    histogram.find(_.className.contains("HeapHistogramSpec$Cat")).map(_.numInstances) should be (Some(1000))
  }

  /**
    * Calls the PID method of the companion object explicitly
    */
  @UnitTest
  def getPidSpec(): Unit = {
    val pid: String = HistogramIoLike.pid
    pid should not be "0"
  }

  /**
    * Calls the companion object's `vm` field explicitly
    */
  @UnitTest
  def initCompanionObjectSpec(): Unit = {
    val inputStream: InputStream = HistogramIoLike.vm.heapHisto("-live")
    val heapHistogramString: String = scala.io.Source.fromInputStream(inputStream).mkString

    heapHistogramString should not be empty
  }

  /**
    * Tests the full process of registering a collector and calling begin/end for measurement collection
    */
  @UnitTest
  def lifecycleSpec(info: TestInfo): Unit = {
    val measCollectionController: AbstractMeasurementCollectionController = new DefaultMeasurementCollectionController(info)
    val contHeapHistoCollector: AbstractMeasurementCollector = new ContinuousHeapHistogramCollector(TestId.undefined)

    measCollectionController.registerCollector(contHeapHistoCollector)
    measCollectionController.registerCollector(new HeapHistogramCollector(this.randomTestId()))

    measCollectionController.beginMeasurementCollection()
    val histogram: Seq[HeapHistogramEntry] = getHeapHistogram
    measCollectionController.endMeasurementCollection()

    histogram should not be empty
  }
}
