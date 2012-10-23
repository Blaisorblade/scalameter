package org.collperf
package execution



import java.io._
import collection._
import sys.process._
import utils.Tree



class MultipleJvmPerSetupExecutor(val aggregator: Aggregator, val measurer: Executor.Measurer) extends Executor {

  val runner = new JvmRunner

  def maxHeap = 2048

  def startHeap = 2048

  def independentSamples = 6

  def run[T](setuptree: Tree[Setup[T]]): Tree[CurveData] = {
    for (setup <- setuptree) yield runSetup(setup)
  }

  private[execution] def runSetup[T](setup: Setup[T]): CurveData = {
    import setup._

    val warmups = context.goe(Key.warmupRuns, 1)
    val totalreps = context.goe(Key.benchRuns, 1)
    def repetitions(idx: Int): Int = {
      val is = independentSamples
      totalreps / is + (if (idx < totalreps % is) 1 else 0)
    }

    val m = measurer

    def sample(idx: Int, reps: Int): Map[Parameters, Seq[Long]] = runner.run(jvmflags(startHeap = startHeap, maxHeap = maxHeap)) {
      initialContext = context
      
      log.verbose(s"Sampling $reps measurements in separate JVM invocation $idx - ${context.scope}, ${context.goe(Key.curve, "")}.")

      // warmup
      customwarmup match {
        case Some(warmup) =>
          for (i <- 0 until warmups) warmup()
        case _ =>
          for (x <- gen.warmupset) {
            for (i <- Warmer(warmups, setupFor(x), teardownFor(x))) snippet(x)
          }
      }

      // measure
      val observations = for (params <- gen.dataset) yield {
        val set = setupFor()
        val tear = teardownFor()
        val regen = regenerateFor(params)
        (params, m.measure(reps, set, tear, regen, snippet))
      }

      observations.toMap
    }

    log.verbose(s"Running test set for ${context.scope}, curve ${context.goe(Key.curve, "")}")
    log.verbose(s"Starting $totalreps measurements across $independentSamples independent JVM runs.")

    val timemaps = for {
      idx <- 0 until independentSamples
      reps = repetitions(idx)
    } yield sample(idx, reps)

    // ugly as hell
    val timemap = timemaps.reduceLeft { (accmap, timemap) =>
      val a1 = accmap.toSeq.sortBy(_._1.axisData.toList.map(_._1).toString)
      val a2 = timemap.toSeq.sortBy(_._1.axisData.toList.map(_._1).toString)
      val result = a1 zip a2 map {
        case ((k1, x), (k2, y)) => (k1, x ++ y)
      }
      result.toMap
    }

    log.verbose(s"Obtained measurements:\n${timemap.mkString("\n")}")

    val measurements = timemap map {
      case (params, times) => Measurement(
        aggregator(times),
        params,
        aggregator.data(times)
      )
    }

    CurveData(measurements.toSeq, Map.empty, context)
  }

  override def toString = s"MultipleJvmPerSetupExecutor(${aggregator.name}, ${measurer.name})"

}


object MultipleJvmPerSetupExecutor extends Executor.Factory[MultipleJvmPerSetupExecutor] {

  def apply(agg: Aggregator, m: Executor.Measurer) = new MultipleJvmPerSetupExecutor(agg, m)

}




























