package org.collperf
package collections



import collection._
import Key._



class SetBenchmarks extends PerformanceTest.Regression with Collections {

  def persistor = new persistance.SerializationPersistor()

  /* tests */

  performance of "Set" in {

    measure method "apply" configuration (
      exec.minWarmupRuns -> 25,
      exec.benchRuns -> 40,
      exec.independentSamples -> 10,
      reporting.regression.significance -> 1e-13
    ) in {
      val from = 50000
      val to = 500000
      val by = 100000
      
      using(hashtablesets(from, to, by)) curve("mutable.HashSet") in { xs =>
        var i = 0
        val sz = xs.size
        val until = sz * 3
        while (i < until) {
          xs.apply(i)
          i += 1
        }
      }

      using(linkedhashtablesets(from, to, by)) curve("mutable.LinkedHashSet") in { xs =>
        var i = 0
        val sz = xs.size
        val until = sz * 3
        while (i < until) {
          xs.apply(i)
          i += 1
        }
      }

      using(avlsets(from, to, by)) curve("mutable.TreeSet") in { xs =>
        var i = 0
        val sz = xs.size
        val until = sz * 3
        while (i < until) {
          xs.apply(i)
          i += 1
        }
      }

      using(hashtriesets(from, to, by)) curve("immutable.HashSet") configuration (
        exec.benchRuns -> 36,
        exec.independentSamples -> 6,
        exec.reinstantiation.frequency -> 1,
        exec.reinstantiation.fullGC -> true,
        exec.noise.magnitude -> 1.0
      ) in { xs =>
        var i = 0
        val sz = xs.size
        val until = sz * 3
        while (i < until) {
          xs.apply(i)
          i += 1
        }
      }

      using(redblacksets(from, to, by)) curve("immutable.TreeSet") in { xs =>
        var i = 0
        val sz = xs.size
        val until = sz * 3
        while (i < until) {
          xs.apply(i)
          i += 1
        }
      }

    }

    measure method "add" configuration (
      exec.minWarmupRuns -> 15,
      exec.benchRuns -> 32,
      exec.independentSamples -> 8,
      reporting.regression.significance -> 1e-13
    ) in {
      val from = 100000
      val to = 750000
      val by = 150000
      
      using(sizes(from, to, by)) curve("mutable.HashSet") in { sz =>
        var i = 0
        val xs = mutable.HashSet[Int]()
        while (i < sz) {
          xs.add(i)
          i += 1
        }
      }

      using(sizes(from, to, by)) curve("mutable.LinkedHashSet") in { sz =>
        var i = 0
        val xs = mutable.LinkedHashSet[Int]()
        while (i < sz) {
          xs.add(i)
          i += 1
        }
      }

      using(sizes(from, to, by)) curve("mutable.TreeSet") in { sz =>
        var i = 0
        val xs = mutable.TreeSet[Int]()
        while (i < sz) {
          xs.add(i)
          i += 1
        }
      }

    }

    measure method "update" configuration (
      exec.minWarmupRuns -> 15,
      exec.benchRuns -> 32,
      exec.independentSamples -> 8,
      reporting.regression.significance -> 1e-13
    ) in {
      val from = 25000
      val to = 125000
      val by = 25000
      
      using(sized(hashtablesets(from, to, by))) curve("mutable.HashSet") tearDown {
        case (sz, xs) => for (i <- 0 until sz) xs.add(i)
      } in {
        case (sz, xs) =>
          var i = 0
          val until = sz * 8
          while (i < until) {
            xs.update(i % sz, i % 17 > 8)
            i += 1
          }
      }

      using(sized(avlsets(from, to, by))) curve("mutable.TreeSet") configuration (
        exec.minWarmupRuns -> 6,
        exec.benchRuns -> 30,
        exec.independentSamples -> 4
      ) tearDown {
        case (sz, xs) => for (i <- 0 until (sz / 8)) xs.add(i)
      } in {
        case (sz, xs) =>
          var i = 0
          val until = sz / 8
          while (i < until) {
            xs.update(i % sz, i % 17 > 8)
            i += 1
          }
      }
    }

    measure method "remove" configuration (
      exec.minWarmupRuns -> 15,
      exec.benchRuns -> 35,
      exec.independentSamples -> 5,
      reporting.regression.significance -> 1e-13
    ) in {
      val from = 50000
      val to = 500000
      val by = 100000
      
      using(sized(hashtablesets(from, to, by))) curve("mutable.HashSet") tearDown {
        case (sz, xs) => for (i <- 0 until sz) xs.add(i)
      } in {
        case (sz, xs) =>
          var i = 0
          val until = sz
          while (i < until) {
            xs.remove(i)
            i += 1
          }
      }

      using(sized(avlsets(from, to, by))) curve("mutable.TreeSet") tearDown {
        case (sz, xs) => for (i <- 0 until sz) xs.add(i)
      } in {
        case (sz, xs) =>
          var i = 0
          val until = sz
          while (i < until) {
            xs.remove(i)
            i += 1
          }
      }
    }

    measure method "+" configuration (
      exec.minWarmupRuns -> 15,
      exec.benchRuns -> 35,
      exec.independentSamples -> 5,
      reporting.regression.significance -> 1e-13
    ) in {
      val from = 50000
      val to = 500000
      val by = 100000
      
      using(sizes(from, to, by)) curve("immutable.HashSet") in { sz =>
        var i = 0
        var xs = immutable.HashSet[Int]()
        while (i < sz) {
          xs += i
          i += 1
        }
      }

      using(sizes(from, to, by)) curve("immutable.TreeSet") in { sz =>
        var i = 0
        var xs = immutable.TreeSet[Int]()
        while (i < sz) {
          xs += i
          i += 1
        }
      }

    }

    measure method "-" configuration (
      exec.minWarmupRuns -> 15,
      exec.benchRuns -> 35,
      exec.independentSamples -> 5,
      reporting.regression.significance -> 1e-13,
      exec.noise.magnitude -> 0.5
    ) in {
      val from = 50000
      val to = 500000
      val by = 100000
      
      using(hashtriesets(from, to, by)) curve("immutable.HashSet") in { xs =>
        var i = 0
        val until = xs.size
        var s = xs
        while (i < until) {
          s -= i
          i += 1
        }
      }

      using(redblacksets(from, to, by)) curve("immutable.TreeSet") in { xs =>
        var i = 0
        val until = xs.size
        var s = xs
        while (i < until) {
          s -= i
          i += 1
        }
      }

    }

  }

}

















