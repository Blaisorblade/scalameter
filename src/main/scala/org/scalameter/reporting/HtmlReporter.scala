package org.scalameter
package reporting



import java.util.Date
import java.io._
import java.awt.Color
import util.parsing.json._
import org.jfree.chart._
import collection._
import xml._
import utils.Tree
import Key._



case class HtmlReporter(val embedDsv: Boolean) extends Reporter {
  import HtmlReporter._

  def report(result: CurveData, persistor: Persistor) {
    // nothing - the charts are generated only at the end
  }
  
  def report(results: Tree[CurveData], persistor: Persistor) = {
    if (!embedDsv) {
      new DsvReporter(dsvDelimiter).report(results, persistor)
    }

    val resultdir = results.context.goe(reports.resultDir, "tmp")
    new File(resultdir).mkdir()
    val root = new File(resultdir, "report")
    root.mkdir()

    val curvesJSONIndex = JSONIndex(results)

    resourceDirs.foreach {
      new File(root, _).mkdir()
    }

    resourceFiles.foreach { filename =>
      copyResource(filename, new File(root, filename))
    }

    printToFile(new File(root, jsDataFile)) { pw =>
      pw.println("var ScalaMeter = (function(parent) {");
      pw.println("  var my = { name: \"data\" };");
      pw.println(s"  my.index = $curvesJSONIndex;")
      if (embedDsv) {
        printTsv(results, persistor, pw)
      }
      pw.println("  parent[my.name] = my;");
      pw.println("  return parent;");
      pw.println("})(ScalaMeter || {});");
    }

    true
  }

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
  
  def JSONIndex(results: Tree[CurveData]) = {
    def JSONCurve(context: Context, curve: CurveData) = JSONObject(
      immutable.Map(
        "scope" -> new JSONArray(curve.context.scopeList),
        "name" -> curve.context.curve,
        "file" -> s"../${context.scope}.${curve.context.curve}.dsv"
      )
    )

    val JSONCurves = for {
      (ctx, curves) <- results.scopes if curves.nonEmpty
      curve <- curves
    } yield {
      JSONCurve(ctx, curve)
    }
    new JSONArray(JSONCurves.toList)
  }

  def printTsv(results: Tree[CurveData], persistor: Persistor, pw: PrintWriter) {
    val allCurves = for {
      (ctx, curves) <- results.scopes if curves.nonEmpty
      curve <- curves
    } yield curve

    val separators = "" #:: Stream.continually(", ")

    pw.print("  my.tsvData = [")
    for ((curve, sep) <- allCurves.toStream zip separators) {
      pw.print(sep)
      pw.print("'")
      DsvReporter.writeCurveData(curve, persistor, pw, dsvDelimiter, "\\n")
      pw.print("'")
    }
    pw.println("];")
  }

}


object HtmlReporter {
  val resourceDirs = List("css", "img", "js", "js/ScalaMeter")

  val resourceFiles = List(
    "index.html",
    "css/bootstrap.min.css",
    "css/bootstrap-slider.css",
    "css/icons.gif",
    "css/index.css",
    "css/jquery-ui-1.10.3.custom.css",
    "css/ui.dynatree.css",
    "css/vline.gif",
    "img/arrow.png",
    "img/glyphicons-halflings.png",
    "js/bootstrap.min.js",
    "js/crossfilter.min.js",
    "js/d3.v3.min.js",
    "js/jquery.dynatree.js",
    "js/jquery-1.9.1.js",
    "js/jquery-compat.js",
    "js/jquery-ui-1.10.3.custom.min.js",
    "js/ScalaMeter/chart.js",
    "js/ScalaMeter/dimensions.js",
    "js/ScalaMeter/filter.js",
    "js/ScalaMeter/helper.js",
    "js/ScalaMeter/main.js",
    "js/ScalaMeter/permalink.js" )

  val jsDataFile = "js/ScalaMeter/data.js"

  val dsvDelimiter = '\t'

  def copyResource(from: String, to: File) {
    val res = getClass.getClassLoader.getResourceAsStream(from)
    try {
      val buffer = new Array[Byte](1024)
      val fos = new FileOutputStream(to)
      var nBytesRead = 0
      def read = { nBytesRead = res.read(buffer) }
      while ({read; nBytesRead != -1}) {
        fos.write(buffer, 0, nBytesRead)
      }
      if (fos != null) {
        fos.close();
      }
    } finally {
      res.close()
    }
  }

  def printToFile(f: File)(op: PrintWriter => Unit) {
    val p = new PrintWriter(f)
    try { op(p) } finally { p.close() }
  }

}
