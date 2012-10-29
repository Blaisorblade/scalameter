package org.collperf



import collection._
import compat._
import utils.{withGCNotification, Tree}



trait Executor {

  def run[T](setups: Tree[Setup[T]]): Tree[CurveData]

}


object Executor {

  trait Factory[E <: Executor] {
    def apply(aggregator: Aggregator, m: Measurer): E

    def min = apply(Aggregator.min, new Measurer.Default)

    def max = apply(Aggregator.max, new Measurer.Default)

    def average = apply(Aggregator.average, new Measurer.Default)

    def median = apply(Aggregator.median, new Measurer.Default)

    def complete(a: Aggregator) = apply(Aggregator.complete(a), new Measurer.Default)
  }

  trait Measurer extends Serializable {
    def name: String
    def measure[T, U](context: Context, measurements: Int, setup: T => Any, tear: T => Any, regen: () => T, snippet: T => Any): Seq[Long]
  }

  object Measurer {

    /** Mixin for measurers whose benchmarked value is based on the current iteration. */
    trait IterationBasedValue extends Measurer {

      /** Returns the value used for the benchmark at `iteration`.
       *  May optionally call `regen` to obtain a new value for the benchmark.
       *  
       *  By default, the value `v` is always returned and the value for the
       *  benchmark is never regenerated.
       */
      protected def valueAt[T](context: Context, iteration: Int, regen: () => T, v: T): T = v

    }

    /** A default measurer executes the test as many times as specified and returns the sequence of measured times. */
    class Default extends Measurer with IterationBasedValue {
      def name = "Measurer.Default"

      def measure[T, U](context: Context, measurements: Int, setup: T => Any, tear: T => Any, regen: () => T, snippet: T => Any): Seq[Long] = {
        var iteration = 0
        var times = List[Long]()
        var value = regen()

        while (iteration < measurements) {
          value = valueAt(context, iteration, regen, value)
          setup(value)

          val start = Platform.currentTime
          snippet(value)
          val end = Platform.currentTime
          val time = end - start

          tear(value)

          times ::= time
          iteration += 1
        }

        log.verbose(s"measurements: ${times.mkString(", ")}")

        times
      }
    }

    /** A measurer that discards measurements for which it detects that GC cycles occurred.
     *  
     *  Assume that `M` measurements are requested.
     *  To prevent looping forever, after the number of measurement failed due to GC exceeds the number of successful
     *  measurements by more than `M`, the subsequent measurements are accepted irregardless of whether GC cycles occur.
     */
    class IgnoringGC extends Measurer with IterationBasedValue {
      override def name = "Measurer.IgnoringGC"

      def measure[T, U](context: Context, measurements: Int, setup: T => Any, tear: T => Any, regen: () => T, snippet: T => Any): Seq[Long] = {
        var times = List[Long]()
        var okcount = 0
        var gccount = 0
        var ignoring = true
        var value = regen()

        while (okcount < measurements) {
          value = valueAt(context, okcount + gccount, regen, value)
          setup(value)

          @volatile var gc = false
          val time = withGCNotification { n =>
            gc = true
            log.verbose("GC detected.")
          } apply {
            val start = Platform.currentTime
            snippet(value)
            val end = Platform.currentTime
            end - start
          }

          tear(value)

          if (ignoring && gc) {
            gccount += 1
            if (gccount - okcount > measurements) ignoring = false
          } else {
            okcount += 1
            times ::= time
          }
        }

        log.verbose(s"${if (ignoring) "All GC time ignored" else "Some GC time recorded"}, accepted: $okcount, ignored: $gccount")
        log.verbose(s"measurements: ${times.mkString(", ")}")

        times
      }
    }

    /** A mixin measurer which causes the value for the benchmark to be reinstantiated
     *  every `Key.frequency` measurements.
     *  Before the new value has been instantiated, a full GC cycle is invoked if `Key.fullGC` is `true`.
     */
    trait PeriodicReinstantiation extends IterationBasedValue {
      abstract override def name = s"${super.name}+PeriodicReinstantiation"

      protected override def valueAt[T](context: Context, iteration: Int, regen: () => T, v: T) = {
        val frequency = context.goe(Key.frequency, 10)
        val fullGC = context.goe(Key.fullGC, false)

        if ((iteration + 1) % frequency == 0) {
          log.verbose("Reinstantiating benchmark value.")
          if (fullGC) Platform.collectGarbage()
          val nv = regen()
          nv
        } else v
      }
    }

    /** A mixin measurer which detects outliers (due to an undetected GC or JIT) and requests additional measurements to replace them.
     *  Outlier elimination can also eliminate some pretty bad allocation patterns in some cases.
     *  Only outliers from above are considered.
     *
     *  When detecting an outlier, `Key.suspectPercent`% (with a minimum of `1`) of worst times will be considered.
     *  For example, given `Key.suspectPercent = 25` the times:
     *
     *  {{{
     *      10, 11, 10, 12, 11, 11, 10, 11, 44
     *  }}}
     *
     *  times `12` and `44` are considered for outlier elimination.
     *
     *  Given the times:
     *  
     *  {{{
     *      10, 12, 14, 55
     *  }}}
     *  
     *  the time `55` will be considered for outlier elimination.
     *
     *  A potential outlier (suffix) is removed if removing it increases the coefficient of variance by at least `Key.covMultiplier` times.
     */
    trait OutlierElimination extends Measurer {

      abstract override def name = s"${super.name}+OutlierElimination"

      abstract override def measure[T, U](context: Context, measurements: Int, setup: T => Any, tear: T => Any, regen: () => T, snippet: T => Any): Seq[Long] = {
        import utils.Statistics._

        val suspectPercent = context.goe(Key.suspectPercent, 25)
        val covmult = context.goe(Key.covMultiplier, 2.0)
        var results = super.measure(context, measurements, setup, tear, regen, snippet).sorted
        val suspectnum = math.max(1, results.length * suspectPercent / 100)
        var retries = 8

        def outlierExists(rs: Seq[Long]) = {
          val cov = CoV(rs)
          val covinit = CoV(rs.dropRight(suspectnum))
          if (covinit != 0.0) cov > covmult * covinit
          else mean(rs.takeRight(suspectnum)) > 1.2 * mean(rs.dropRight(suspectnum))
        }

        while (outlierExists(results) && retries > 0) {
          log.verbose("Detected an outlier: " + results.mkString(", "))
          results = (results.dropRight(suspectnum) ++ super.measure(context, suspectnum, setup, tear, regen, snippet)).sorted
          retries -= 1
        }

        log.verbose("After outlier elimination: " + results.mkString(", "))
        results
      }
    }

    /** A measurer which adds noise to the measurement.
     *
     *  @define noise This measurer makes the regression tests more solid. While certain forms of
     *  gradual regressions are harder to detect, the measurements become less
     *  susceptible to actual randomness, because adding artificial noise increases
     *  the confidence intervals.
     */
    trait Noise extends Measurer {

      def noiseFunction(observations: Seq[Long], magnitude: Double): Long => Double

      abstract override def measure[T, U](context: Context, measurements: Int, setup: T => Any, tear: T => Any, regen: () => T, snippet: T => Any): Seq[Long] = {
        val observations = super.measure(context, measurements, setup, tear, regen, snippet)
        val magnitude = context.goe(Key.noiseMagnitude, 0.0)
        val noise = noiseFunction(observations, magnitude)
        val withnoise = observations map {
          x => (x + noise(x)).toLong
        }

        log.verbose("After applying noise: " + withnoise.mkString(", "))

        withnoise
      }

    }

    import utils.Statistics.clamp

    /** A mixin measurer which adds an absolute amount of Gaussian noise to the measurement.
     *  
     *  A random value is sampled from a Gaussian distribution for each measurement `x`.
     *  This value is then multiplied with `Key.noiseMagnitude` and added to the measurement.
     *  The default value for the noise magnitude is `0.0` - it has to be set manually
     *  for tests requiring artificial noise.
     *  The resulting value is clamped into the range `x - magnitude, x + magnitude`.
     *  
     *  $noise
     */
    trait AbsoluteNoise extends Noise {

      abstract override def name = s"${super.name}+AbsoluteNoise"

      def noiseFunction(observations: Seq[Long], m: Double) = (x: Long) => {
        clamp(m * util.Random.nextGaussian(), -m, +m)
      }

    }

    /** A mixin measurer which adds an amount of Gaussian noise to the measurement relative
     *  to its mean.
     * 
     *  An observations sequence mean `m` is computed.
     *  A random Gaussian value is sampled for each measurement `x` in the observations sequence.
     *  It is multiplied with `m / 10.0` times `Key.noiseMagnitude` (default `0.0`).
     *  Call this multiplication factor `f`.
     *  The resulting value is clamped into the range `x - f, x + f`.
     *
     *  The bottomline is - a `1.0` noise magnitude is a variation of `10%` of the mean.
     *
     *  $noise
     */
    trait RelativeNoise extends Noise {

      abstract override def name = s"${super.name}+RelativeNoise"

      def noiseFunction(observations: Seq[Long], magnitude: Double) = {
        val m = utils.Statistics.mean(observations)
        (x: Long) => {
          val f = m / 10.0 * magnitude
          clamp(f * util.Random.nextGaussian(), -f, +f)
        }
      }

    }

    /** Eliminates performance measurements tied to certain particularly bad allocation patterns, typically
     *  occurring immediately before the next major GC cycle.
     *  Useful for "flattening" out the curves.
     */
    case class OptimalAllocation(delegate: Measurer, aggregator: Aggregator, retries: Int = 5, confidence: Double = 0.8) extends Measurer {

      def name = s"Measurer.OptimalAllocation(retries: $retries, confidence: $confidence, aggregator: ${aggregator.name}, delegate: ${delegate.name})"

      val checkfactor = 8

      def measure[T, U](context: Context, measurements: Int, setup: T => Any, tear: T => Any, regen: () => T, snippet: T => Any): Seq[Long] = {
        import utils.Statistics._

        def sample(num: Int, value: T): Seq[Long] = delegate.measure(context, num, setup, tear, () => value, snippet)

        def different(observations: Seq[Long], checks: Seq[Long]): Boolean = {
          !ConfidenceIntervalTest(observations, checks, 1.0 - confidence)
        }

        def worse(observations: Seq[Long], checks: Seq[Long]): Boolean = {
          aggregator(checks) < aggregator(observations)
        }

        def potential(observations: Seq[Long], checks: Seq[Long]): Boolean = {
          different(observations, checks) && worse(observations, checks)
        }

        log.verbose("Taking initial set of measurements.")
        var last = sample(measurements, regen())
        var best = last
        var i = 0
        while (i < retries) {
          log.verbose("Taking another sample.")
          val value = regen()
          last = sample(measurements / checkfactor, value)

          if (potential(best, last)) {
            log.verbose("Found a potentially better sample, incrementally taking more samples of the same value.")

            var totalmeasurements = measurements / checkfactor
            do {
              val step = measurements / checkfactor * 2
              last = last ++ sample(step, value)
              totalmeasurements += step
            } while (totalmeasurements < measurements && potential(best, last))

            if (worse(best, last) && totalmeasurements >= measurements) {
              log.verbose("Better sample confirmed: " + last.mkString(", "))
              best = last
            } else log.verbose("Potentially better sample is false positive: " + last.mkString(", "))
          }

          i += 1
        }

        best
      }

    }

  }

}






















