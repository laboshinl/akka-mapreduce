import akka.actor._
import geekie.mapred._
import geekie.mapred.io.FileChunks

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.duration._

/**
 * Created by nlw on 05/04/15.
 * Akka based map-reduce task example with neat job flow control.
 *
 */
object SuperscalarWordCountMain extends App {
  println("RUNNING \"SUPERSCALAR\" M/R")
  if (args.length < 1) println("MISSING INPUT FILENAME")
  else {
    val system = ActorSystem("akka-wordcount")

    val wordcountSupervisor = system.actorOf(Props[SsWcMapReduceSupervisor], "wc-super")
    wordcountSupervisor ! MultipleFileReaders(args(0))
    // wcSupAct ! SingleFileReader(args(0))
  }
}

class SsWcMapReduceSupervisor extends Actor {

  type A = String
  type RedK = String
  type RedV = Int

  val nMappers = 4

  val myworkers = pipe_map {
    ss: String => ss split raw"\s+"
  } times 4 map {
    word: String => Some(word.trim.toLowerCase.filterNot(_ == ','))
  } times 4 map {
    word: String => if (StopWords.contains(word)) Some(word) else None
  } times 4 mapkv {
    word: String => Some(KeyVal(word, 1))
  } times 4 reduce {
    (a: Int, b: Int) => a + b
  } times 8 output self

  val mapper = myworkers.head

  var progress = 0
  var finalAggregate: Map[RedK, RedV] = Map()

  def receive = {
    case MultipleFileReaders(filename) =>
      println(s"PROCESSING FILE $filename")
      FileChunks(filename, nMappers) foreach (mapper ! _.iterator)
      mapper ! EndOfData

    case ReducerResult(agAny) =>
      val ag = agAny.asInstanceOf[Map[RedK, RedV]]
      finalAggregate = finalAggregate ++ ag

    case EndOfData =>
      PrintResults(finalAggregate)
      context.system.scheduler.scheduleOnce(1.second, self, HammerdownProtocol)

    case HammerdownProtocol => context.system.shutdown()
  }
}

object SsWcPrintResults {
  def apply[RedK, RedV](finalAggregate: Map[RedK, RedV]) = {
    println("FINAL RESULTS")
    val ag = finalAggregate.asInstanceOf[Map[String, Int]]
    ag.toList sortBy (-_._2) take 20 foreach {
      case (s, i) => println(f"$s%8s:$i%5d")
    }
  }
}