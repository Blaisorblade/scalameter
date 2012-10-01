package org



import java.util.Date
import collection._
import scala.util.DynamicVariable



package object collperf {

  private[collperf] class DynamicContext extends DynamicVariable(Context.topLevel) {
    def withAttribute[T](name: String, v: Any)(block: =>T) = withValue(value + (name -> v))(block)
  }

  private[collperf] val currentContext = new DynamicContext

  private val scheduled = mutable.ArrayBuffer[Benchmark[_]]()

  def scheduleBenchmark[T](benchmark: Benchmark[T]) {
    scheduled += benchmark
  }

  def flushBenchmarkSchedule(): Seq[Benchmark[_]] = {
    val result = scheduled.toVector
    scheduled.clear()
    result
  }

  /* logging */

  object log {
    def verbose(msg: =>Any) {
      if (currentContext.value.getOrElse("verbose", false)) println(msg)
    }
  }

}


package collperf {

  object Key {
    val module = "module"
    val method = "method"

    val jvmVersion = "jvm-version"
    val jvmVendor = "jvm-vendor"
    val jvmName = "jvm-name"
    val osName = "os-name"
    val osArch = "os-arch"
    val cores = "cores"
    val hostname = "hostname"

    val benchRuns = "runs"
    val warmupRuns = "warmups"
    val verbose = "verbose"
  }

  case class Context(properties: immutable.Map[String, Any]) {
    def +(t: (String, Any)) = Context(properties + t)
    def ++(that: Context) = Context(this.properties ++ that.properties)
    def get[T](key: String) = properties.get(key).asInstanceOf[Option[T]]
    def getOrElse[T](key: String, v: T) = properties.getOrElse(key, v).asInstanceOf[T]
  }

  object Context {
    def apply(xs: (String, Any)*) = new Context(immutable.Map(xs: _*))

    val empty = new Context(immutable.Map())

    val topLevel = machine

    def machine = Context(immutable.Map(
      Key.jvmVersion -> sys.props("java.vm.version"),
      Key.jvmVendor -> sys.props("java.vm.vendor"),
      Key.jvmName -> sys.props("java.vm.name"),
      Key.osName -> sys.props("os.name"),
      Key.osArch -> sys.props("os.arch"),
      Key.cores -> Runtime.getRuntime.availableProcessors,
      Key.hostname -> java.net.InetAddress.getLocalHost.getHostName
    ))
  }

  case class Parameters(axisData: immutable.ListMap[String, Any]) {
    def ++(that: Parameters) = Parameters(this.axisData ++ that.axisData)
  }

  object Parameters {
    def apply(xs: (String, Any)*) = new Parameters(immutable.ListMap(xs: _*))
  }

  case class Measurement(time: Long, params: Parameters)

  case class Result(measurements: Seq[Measurement], context: Context)

  case class History(results: Seq[(Date, Result)])

  case class Benchmark[T](executor: Executor, context: Context, gen: Gen[T], setup: Option[T => Any], teardown: Option[T => Any], customwarmup: Option[() => Any], snippet: T => Any) {
    def run() = executor.run(this)
  }

  trait Reporter {
    def report(result: Result, persistor: Persistor): Unit
  }

  object Reporter {
    object None extends Reporter {
      def report(result: Result, persistor: Persistor) {}
    }
  }

  trait Persistor {
    def load(context: Context): History
    def save(result: Result): Unit
  }

  object Persistor {
    object None extends Persistor {
      def load(context: Context): History = History(Nil)
      def save(result: Result) {}
    }
  }

  trait HasExecutor {
    def executor: Executor
  }

}









