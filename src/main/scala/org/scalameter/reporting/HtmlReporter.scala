package org.scalameter
package reporting



import java.util.Date
import java.io._
import java.awt.Color
import org.jfree.chart._
import collection._
import xml._
import utils.Tree
import Key._



case class HtmlReporter(val renderers: HtmlReporter.Renderer*) extends Reporter {

  val sep = File.separator

  def head = 
    <head>
      <title>Performance Report</title>
      <link type="text/css" media="screen" rel="stylesheet" href="css/bootstrap.min.css" />
      <link type="text/css" media="screen" rel="stylesheet" href="css/index.css" />
      <link type="text/css" media="screen" rel="stylesheet" href="css/ui.dynatree.css" />
      <script type="text/javascript" src="js/d3.v3.min.js"></script>
      <script type="text/javascript" src="js/crossfilter.min.js"></script>
      <script type="text/javascript" src="js/jquery-1.9.1.js"></script>
      <script type="text/javascript" src="js/jquery-ui.custom.min.js"></script>
      <script type="text/javascript" src="js/jquery.dynatree.js"></script>
      <script type="text/javascript" src="js/bootstrap.min.js"></script>
      <script type="text/javascript" src="js/parse.js"></script>
    </head>

  def body(result: Tree[CurveData], persistor: Persistor) = {
    <body>
      {skeleton}
      {machineInformation}
      {date(result)}
      <script type="text/javascript">
        var cd = curvedata.rawdata('.rawdata');
      </script>
      {
        for ((ctx, scoperesults) <- result.scopes; if scoperesults.nonEmpty) yield
          {
            val histories = scoperesults.map(cd => persistor.load(cd.context))
            for (r <- renderers) yield r.render(ctx, scoperesults, histories)
          }
      }
      <script type="text/javascript">
        cd.setReady();
      </script>
    </body>
  }

  def skeleton =
    <div>
      <h1>Performance Report</h1>        
      <div class="tree"></div>
      <div class="chartholder">
        <ul class="nav nav-tabs">
          <li class="active"><a onclick="cd.chartType('line');" data-toggle="tab">Line Chart</a></li>
          <li><a onclick="cd.chartType('line_ci');" data-toggle="tab">Line Chart with CI</a></li>
          <li><a onclick="cd.chartType('bar');" data-toggle="tab">Bar Chart</a></li>
        </ul>
        <div class="chart"></div>
      </div>
      <h1>Filters</h1>        
      <div id="filters">
        <div id="selectdate" class="filter">
          <div class="title">Date</div>
        </div>
        <div id="selectparam" class="filter">
          <div class="title">param-size</div>
        </div>
      </div>
      <h1>Raw data</h1>
      <table class="table rawdata"></table>
    </div>

  def machineInformation =
    <div>
      <h1>Machine information</h1>
      <ul>
      {
        for ((k, v) <- Context.machine.properties.toList.sortBy(_._1)) yield <li>
        {k + ": " + v}
        </li>
      }
      </ul>
    </div>

  def date(results: Tree[CurveData]) = {
    val dateoption = for {
      start <- results.context.get[Date](reports.startDate)
      end <- results.context.get[Date](reports.endDate)
    } yield <div>
      <div>Started: {start}</div>
      <div>Finished: {end}</div>
      <div>Running time: {(end.getTime - start.getTime) / 1000} seconds</div>
    </div>
    dateoption.getOrElse(<div>No date information.</div>)
  }
  
  def report(result: CurveData, persistor: Persistor) {
    // nothing - the charts are generated only at the end
  }
  
  def copyResource(from: String, to: File) {
    val res = getClass.getClassLoader.getResourceAsStream(from)
    try {
      val reader = new BufferedReader(new InputStreamReader(res))
      printToFile(to) { p =>
        var line = ""
        while (line != null) {
          p.println(line)
          line = reader.readLine()
        }
      }
    } finally {
      res.close()
    }
  }

  def report(results: Tree[CurveData], persistor: Persistor) = {
    val resultdir = results.context.goe(reports.resultDir, "tmp")

    new File(s"$resultdir").mkdir()

    val root = new File(s"$resultdir${sep}report")

    root.mkdir()
    new File(root, "css").mkdir()
    new File(root, "js").mkdir()

    val report = <html>{head ++ body(results, persistor)}</html>

    List(
      "css/bootstrap.min.css",
      "css/icons.gif",
      "css/index.css",
      "css/ui.dynatree.css",
      "css/vline.gif",
      "js/bootstrap.min.js",
      "js/crossfilter.min.js",
      "js/d3.v3.min.js",
      "js/jquery-1.9.1.js",
      "js/jquery-ui.custom.min.js",
      "js/jquery.dynatree.js",
      "js/parse.js"
    ).foreach { filename =>
      copyResource(filename, new File(root, filename))
    }

    printToFile(new File(s"$resultdir${sep}report${sep}index.html")) {
      _.println("<!DOCTYPE html>\n" + report.toString)
    }

    true
  }

  def printToFile(f: java.io.File)(op: java.io.PrintWriter => Unit) {
    val p = new java.io.PrintWriter(f)
    try { op(p) } finally { p.close() }
  }

}


object HtmlReporter {

  trait Renderer {
    def render(context: Context, curves: Seq[CurveData], hs: Seq[History]): Node
  }

  object Renderer {
    def regression = Seq(Info(), Chart(ChartReporter.ChartFactory.XYLine()), Chart(ChartReporter.ChartFactory.TrendHistogram()))

    def basic = Seq(Info(), BigO(), Chart(ChartReporter.ChartFactory.XYLine()))

    case class Info() extends Renderer {
      def render(context: Context, curves: Seq[CurveData], hs: Seq[History]): Node = 
      <div>Info:
      <ul>
      <li>Number of runs: {context.goe(exec.benchRuns, "")}</li>
      <li>Executor: {context.goe(dsl.executor, "")}</li>
      </ul>
      </div>
    }

    case class BigO() extends Renderer {
      def render(context: Context, curves: Seq[CurveData], hs: Seq[History]): Node = 
      <div>Big O analysis:
      <ul>
      {
        for (cd <- curves) yield <li>
        {cd.context.goe(dsl.curve, "")}: {cd.context.goe(reports.bigO, "(no data)")}
        </li>
      }
      </ul>
      </div>
    }

    case class Chart(factory: ChartReporter.ChartFactory, title: String = "Chart", colors: Seq[Color] = Seq()) extends Renderer {
      def render(context: Context, curves: Seq[CurveData], hs: Seq[History]): Node = {
        val resultdir = context.goe(reports.resultDir, "tmp")
        val scopename = context.scope
        val chart = factory.createChart(scopename, curves, hs, colors)
        val chartfile = new File(s"$resultdir${File.separator}report${File.separator}images${File.separator}$scopename.png")
        ChartUtilities.saveChartAsPNG(chartfile, chart, 1600, 1200)

        <div>
        <p>{s"$title :"}</p>
        <a href={"images/" + scopename + ".png"}>
        <img src={"images/" + scopename + ".png"} alt={scopename} width="800" height="600"></img>
        </a>
        </div>
      }
    }
    
    case class JSChart() extends Renderer {
      def render(context: Context, curves: Seq[CurveData], hs: Seq[History]): Node = {
        val resultdir = ".." //context.goe(reports.resultDir, "tmp")
        val group = context.scope
        val sep = "/"

        def addCurve(curve: CurveData): Node = {
          val curveName = curve.context.curve
          val scope = scala.xml.Unparsed(new scala.util.parsing.json.JSONArray(curve.context.scopeList).toString())
          val filename = s"$resultdir$sep$group.$curveName.dsv"
          <script type="text/javascript">
            cd.addGraph({ scope }, { s"'$curveName'" }, { s"'$filename'" });
          </script>
        }
        <div> {
          for (c <- curves) yield addCurve(c)
        } </div>
      }
    }

    case class HistoryList() extends Renderer {
      def render(context: Context, curves: Seq[CurveData], hs: Seq[History]): Node = {
        // TODO

        <div>
        </div>
      }
    }

    case class Regression(tester: RegressionReporter.Tester, colors: Seq[Color] = Seq()) extends Renderer {
      def render(context: Context, curves: Seq[CurveData], hs: Seq[History]): Node = {

        val factory = ChartReporter.ChartFactory.ConfidenceIntervals(true, true, tester)
        val resultdir = context.goe(reports.resultDir, "tmp")
        val scopename = context.scope

        for {
          (curve, history) <- curves zip hs
          (prevdate, prevctx, previousCurve) <- history.results
        } yield {
          val checkedCurve = dyn.log.withValue(Log.None) {
            tester.apply(curve.context, curve, Seq(previousCurve))
          }
          if (!checkedCurve.success) {
            // draw a graph
            val chart = factory.createChart(scopename, Seq(checkedCurve), Seq(History(Seq((prevdate, prevctx, previousCurve)))))
            val chartfile = new File(s"$resultdir${File.separator}report${File.separator}images${File.separator}$scopename.png")
            ChartUtilities.saveChartAsPNG(chartfile, chart, 1600, 1200)
          }
        }

        <div>
        <p>Failed tests:</p>
        <a href={"images/" + scopename + ".png"}>
        <img src={"images/" + scopename + ".png"} alt={scopename} width="800" height="600"></img>
        </a>
        </div>
      }
    }
  }

}










