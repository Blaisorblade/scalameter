package org.collperf
package execution



import collection._
import compat.Platform
import utils.Tree



/** Runs warmups until the maximum number of warmups is done,
 *  or the running times have stabilized. After that, it runs
 *  the tests the specified number of times and collects
 *  the results using an `aggregate` function.
 * 
 *  Stabilization is detected by tracking the running times
 *  for which there may have been and those for which there
 *  was no garbage collection.
 *  When either of the two running times stabilizes, we consider
 *  the JVM warmed up for the snippet.
 * 
 *  We do this by registering on GC events instead of invoking
 *  `Platform.collectGarbage`, since usually the time to invoke
 *  the snippet is less than the time to perform full GC, and
 *  most triggered GC cycles are fast because they collect only
 *  the young generation.
 */
class LocalExecutor(val aggregator: Aggregator, val measurer: Executor.Measurer) extends Executor {

  def run[T](setups: Tree[Setup[T]]) = {
    // run all warmups for classloading purposes
    for (bench <- setups) {
      import bench._
      for (x <- gen.warmupset) {
        val warmups = context.goe(Key.warmupRuns, 1)
        for (_ <- Warmer(warmups, setupFor(x), teardownFor(x))) snippet(x)
      }
    }

    // for every benchmark - do a warmup, and then measure
    for (bench <- setups) yield {
      runSingle(bench)
    }
  }

  private[execution] def runSingle[T](benchmark: Setup[T]): CurveData = {
    import benchmark._

    // run warm up
    val warmups = context.goe(Key.warmupRuns, 1)
    customwarmup match {
      case Some(warmup) =>
        for (i <- 0 until warmups) warmup()
      case _ =>
        for (x <- gen.warmupset) {
          for (i <- Warmer(warmups, setupFor(x), teardownFor(x))) snippet(x)
        }
    }

    // perform GC
    Platform.collectGarbage()

    // run tests
    val measurements = new mutable.ArrayBuffer[Measurement]()
    val repetitions = context.goe(Key.benchRuns, 1)
    for ((x, params) <- gen.dataset) {
      val set = setupFor(x)
      val tear = teardownFor(x)

      log.verbose(s"$repetitions repetitions of the snippet starting.")
      val times = measurer.measure(repetitions, set, tear, snippet(x))
      log.verbose("Repetitions ended.")

      val processedTime = aggregator(times)
      val data = aggregator.data(times)
      measurements += Measurement(processedTime, params, data)
    }

    CurveData(measurements, Map.empty, context)
  }

  override def toString = s"LocalExecutor(${aggregator.name})"

}


object LocalExecutor extends Executor.Factory[LocalExecutor] {

  def apply(a: Aggregator, m: Executor.Measurer) = new LocalExecutor(a, m)

}












