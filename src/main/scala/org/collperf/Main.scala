package org.collperf



import collection._
import util.parsing.combinator._



object Main {

  def main(args: Array[String]) {
    // initialize
    val configuration = Configuration.fromCommandLineArgs(args)
    run(configuration)
  }

  def run(configuration: Configuration) {
    // prepare initial context
    // identify test objects
    initialContext = Context.topLevel ++ configuration.context
    import configuration._

    // schedule benchmarks
    for (benchname <- benches) {
      val bench = Class.forName(benchname).newInstance.asInstanceOf[PerformanceTest]
    }
  }

  case class Configuration(benches: Seq[String], context: Context)

  object Configuration extends JavaTokenParsers {

    def fromCommandLineArgs(args: Array[String]) = {
      def arguments: Parser[Configuration] = rep(arg) ^^ {
        case configs => configs.foldLeft(Configuration(Nil, Context.empty)) {
          case (acc, x) => Configuration(acc.benches ++ x.benches, acc.context ++ x.context)
        }
      }
      def arg: Parser[Configuration] = benches | intsetting | stringsetting | flag
      def listOf(flagname: String, shorthand: String): Parser[Seq[String]] = "-" ~ (flagname | shorthand) ~ classnames ^^ {
        case _ ~ _ ~ classnames => classnames
      }
      def classnames: Parser[Seq[String]] = repsep(classname, ":")
      def classname: Parser[String] = repsep(ident, ".") ^^ { _.mkString(".") }
      def benches: Parser[Configuration] = listOf("benches", "b") ^^ {
        case names => Configuration(names, Context.empty)
      }
      def intsetting: Parser[Configuration] = "-" ~ ident ~ decimalNumber ^^ {
        case _ ~ "Cwarmups" ~ num => Configuration(Nil, Context(Key.warmupRuns -> num.toInt))
        case _ ~ "Cruns" ~ num => Configuration(Nil, Context(Key.benchRuns -> num.toInt))
      }
      def stringsetting: Parser[Configuration] = "-" ~ ident ~ ident ^^ {
        case _ ~ "Cresultdir" ~ s => Configuration(Nil, Context(Key.resultDir -> s))
      }
      def flag: Parser[Configuration] = "-" ~ ident ^^ {
        case _ ~ "verbose" => Configuration(Nil, Context(Key.verbose -> true))
        case _ ~ unknownFlag => sys.error(s"Unknown flag '$unknownFlag'")
      }

      parseAll(arguments, args.mkString(" ")) match {
        case Success(result, _) => result
        case Failure(msg, _) => sys.error("failed to parse args: " + msg)
        case Error(msg, _) => sys.error("error while parsing args: " + msg)
      }
    }
  }

}















