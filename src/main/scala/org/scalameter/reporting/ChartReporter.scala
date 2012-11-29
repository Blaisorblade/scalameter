package org.scalameter
package reporting



import org.jfree.data.xy.{XYSeries, XYSeriesCollection, YIntervalSeriesCollection, YIntervalSeries}
import org.jfree.chart.renderer.xy.XYLineAndShapeRenderer
import org.jfree.chart.renderer.xy.DeviationRenderer
import org.jfree.chart.renderer.category.BarRenderer
import org.jfree.chart.plot.{XYPlot, CategoryPlot}
import org.jfree.data.statistics._
import org.jfree.chart.{ChartFactory => JFreeChartFactory}
import org.jfree.chart.plot.PlotOrientation
import org.jfree.data.category.DefaultCategoryDataset
import org.jfree.chart._
import java.io._
import collection._
import utils.Tree
import utils.Statistics._
import java.awt.BasicStroke
import java.awt.Color
import Key.reports._



case class ChartReporter(drawer: ChartReporter.ChartFactory, fileNamePrefix: String = "", wdt: Int = 1600, hgt: Int = 1200) extends Reporter {

  def report(result: CurveData, persistor: Persistor) {
    // nothing - the charts are generated only at the end
  }

  def report(result: Tree[CurveData], persistor: Persistor) = {
    for ((ctx, curves) <- result.scopes if curves.nonEmpty) {
      val scopename = ctx.scope
      val history = persistor.load(ctx)
      val chart = drawer.createChart(scopename, curves, history)
      val dir = result.context.goe(resultDir, "tmp")
      new File(dir).mkdir()
      val chartfile = new File(s"$dir/$fileNamePrefix$scopename.png")
      ChartUtilities.saveChartAsPNG(chartfile, chart, wdt, hgt)
    }
    
    true
  } 

}


object ChartReporter {

  import Key._

  trait ChartFactory {
    /** Generates a chart for the given curve data, with the given history.
     *
     *  @param scopename      name of the chart
     *  @param cs             a list of curves that should appear on the chart
     *  @param history        previous chart data for the same set of curves
     */
    def createChart(scopename: String, cs: Seq[CurveData], history: History, colors: Seq[Color] = Seq()): JFreeChart
  }

  object ChartFactory {

    case class XYLine() extends ChartFactory {
      def createChart(scopename: String, cs: Seq[CurveData], history: History, colors: Seq[Color] = Seq()): JFreeChart = {
        val seriesCollection = new XYSeriesCollection
        val chartName = scopename
        val xAxisName = cs.head.measurements.head.params.axisData.head._1
        val renderer = new XYLineAndShapeRenderer()

        for ((curve, idx) <- cs.zipWithIndex) {
          val series = new XYSeries(curve.context.goe(dsl.curve, idx.toString), true, true)
          for (measurement <- curve.measurements) {
            series.add(measurement.params.axisData.head._2.asInstanceOf[Int], measurement.time)
          }
          seriesCollection.addSeries(series)
          renderer.setSeriesShapesVisible(idx, true)
        }

        val chart = JFreeChartFactory.createXYLineChart(chartName, xAxisName, "time", seriesCollection, PlotOrientation.VERTICAL, true, true, false)
        chart.getPlot.setBackgroundPaint(new java.awt.Color(180, 180, 180))
        chart.getPlot.asInstanceOf[XYPlot].setRenderer(renderer)
        chart.setAntiAlias(true)

        chart
      }
    }

    case class ConfidenceIntervals(showLatestCi: Boolean, showHistoryCi: Boolean, alpha: Double) extends ChartFactory {
      /**
       *
       *
       * @param colors        specifies the colors assigned to the the first `colors.size` curves from `cs`.
                              The rest of the curves are assigned some default set of colors.
       */
      def createChart(scopename: String, cs: Seq[CurveData], history: History, colors: Seq[Color] = Seq()): JFreeChart = {

        def createDataset = {
          val dataset = new YIntervalSeriesCollection
          if(history.results.isEmpty) {
              for ((curve, curveIndex) <- cs.zipWithIndex) {
                val series = new YIntervalSeries(curve.context.goe(dsl.curve, curveIndex.toString))
                for (measurement <- curve.measurements) {
                  val ciForThisPoint = if (showLatestCi) {
                    confidenceInterval(measurement.complete, alpha)
                  } else {
                    (0D, 0D)
                  }
                series.add(measurement.params.axisData.head._2.asInstanceOf[Int], measurement.time, ciForThisPoint._1, ciForThisPoint._2)
                }
              }
          } else {
            for ((curve, curveIndex) <- cs.zipWithIndex) {
              val newestSeries = new YIntervalSeries(curve.context.goe(dsl.curve, curveIndex.toString))
              val historySeries = new YIntervalSeries(curve.context.goe(dsl.curve, curveIndex.toString))

              for ((measurement, measurementIndex) <- curve.measurements.zipWithIndex) {
                /* Fetch, for each corresponding curve in history, the measurements that were at the same position (same size for instance)
                on x-axis, and make a list of them */
                val previousMeasurements = for {
                  pastResult <- history.results
                  correspondingCurveInHistory = pastResult._3(curveIndex) //._3 to access the Seq[CurveData]  
                } yield correspondingCurveInHistory.measurements(measurementIndex)
                // We then take all observations that gave the time measurement (by calling complete) of each point, and concat them
                val previousMeasurementsObservations = previousMeasurements flatMap(m => m.complete)

                val ciForThisPoint = if (showHistoryCi) { confidenceInterval(previousMeasurementsObservations, alpha) } else { (0D, 0D) }
                val meanForThisPoint = mean(previousMeasurementsObservations)
                // Params : x - the x-value, y - the y-value, yLow - the lower bound of the y-interval, yHigh - the upper bound of the y-interval.
                historySeries.add(measurement.params.axisData.head._2.asInstanceOf[Int], meanForThisPoint, ciForThisPoint._1, ciForThisPoint._2)

                val ciForNewestPoint = if (showLatestCi) {
                    confidenceInterval(measurement.complete, alpha)
                  } else {
                    (0D, 0D)
                  }

                newestSeries.add(measurement.params.axisData.head._2.asInstanceOf[Int], measurement.time, ciForNewestPoint._1, ciForNewestPoint._2)
              }
              dataset.addSeries(historySeries)
              dataset.addSeries(newestSeries)
            }
          }

          dataset

        }
        /* We may want to call other methods from the JFreeChart API, as there are a
           lot of them related to appearance in class DeviationRenderer and in its parent classes */
        def paintCurves(renderer: DeviationRenderer) {
          //val test = List(new Color(255, 200, 200), new Color(200, 200, 255))
          for((color, i) <- colors.zipWithIndex) {
            renderer.setSeriesStroke(i, new BasicStroke(3F, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND))
            renderer.setSeriesPaint(i, color)
            renderer.setSeriesFillPaint(i, color)
          }
          renderer.setAlpha(0.25F)
        }

        val dataset = createDataset
        val chartName = scopename
        val xAxisName = cs.head.measurements.head.params.axisData.head._1

        // instantiate a DeviationRenderer (lines, shapes)
        val renderer = new DeviationRenderer(true, true)
        paintCurves(renderer)
    
        //String title, String xAxisLabel, String yAxisLabel, XYDataset dataset, PlotOrientation orientation, boolean legend,boolean tooltips, boolean urls
        val chart = JFreeChartFactory.createXYLineChart(chartName, xAxisName, "time", dataset, PlotOrientation.VERTICAL, true, true, false)
        val plot: XYPlot = chart.getPlot.asInstanceOf[XYPlot]
        plot.setBackgroundPaint(new java.awt.Color(200, 200, 200))
        plot.setRenderer(renderer)
        // There are many other configurable appearance options !
        chart.setAntiAlias(true)
        chart
      }
    }

    case class Histogram() extends ChartFactory {
      def createChart(scopename: String, cs: Seq[CurveData], history: History, colors: Seq[Color] = Seq()): JFreeChart = {
        val chartName = scopename
        val xAxisName = cs.head.measurements.head.params.axisData.head._1 //date (for trend histogram), or size (for normal histogram)
        val dataset = new DefaultCategoryDataset
        // 1 curve is 1 category here
        // REMOVE WHEN DONE : http://www.java2s.com/Code/Java/Chart/JFreeChartBarChartDemo.htm
        for((curve, curveIndex) <- cs.zipWithIndex) {
          for((measurement, measurementIndex) <- curve.measurements.zipWithIndex) {
            // addValue params : Double value, String series_name (eg. ArrayBuffer), category name (should be a date or a size)
            // addValue(double value, java.lang.Comparable rowKey, java.lang.Comparable columnKey)
            // We need to decide if we put the series_name in the CurveData's info or maybe the Measurement's params
            // if 1 curve is 1 category, categoryName should be curve.context.goe(dsl.curve, curveIndex.toString)
            dataset.addValue(measurement.time, (curve.info("seriesNames").asInstanceOf[Map[Int, String]])(measurementIndex),
              curve.context.goe(dsl.curve, curveIndex.toString)/*curve.info("categoryName").asInstanceOf[String]*/)
          }
        }
        val chart = JFreeChartFactory.createBarChart(chartName, xAxisName, "time", dataset, PlotOrientation.VERTICAL, true, true, false)
        val plot: CategoryPlot = chart.getPlot.asInstanceOf[CategoryPlot]
        val renderer: BarRenderer = plot.getRenderer.asInstanceOf[BarRenderer]
        renderer.setDrawBarOutline(false)
        for (seriesIndex <- 0 to cs.head.info("seriesNames").asInstanceOf[Map[Int, String]].size) {
          // create new gradient paint ... colors parameter to be used here
          // renderer.setSeriesPaint(seriesIndex, gradientPaint)
        }
        plot.setBackgroundPaint(new java.awt.Color(200, 200, 200))
        plot.setDomainGridlinePaint(Color.white)
        plot.setRangeGridlinePaint(Color.white)
        chart.setBackgroundPaint(Color.white)
        // probably needs additional graphical customisation, like appareance of axes
        chart.setAntiAlias(true)
        chart
      }
    }

  }

}



















