import akka.actor.{Actor, ActorSystem, Props}
import geekie.mapred._

import scala.math.random
import scala.reflect.ClassTag

/**
 * Created by nlw on 16/04/15.
 * Bad stochastic Pi calculation with "loopy" flow control to print results.
 *
 */
object ApproximatePi extends App {
  println("APPROXIMATING PI")
  val system = ActorSystem("akka-wordcount")

  def mySup = PiMapReduceSupervisor[Int, String, BigDecimal](10, 2) { x: Int =>
    val x = random * 2 - 1
    val y = random * 2 - 1
    Seq(
      KeyVal("SUM", BigDecimal(if (x * x + y * y < 1) 1 else 0)),
      KeyVal("N", BigDecimal(1))
    )
  }(_ + _)

  val wordcountSupervisor = system.actorOf(Props(mySup), "wc-super")
  wordcountSupervisor ! StartCalculations
}


class PiMapReduceSupervisor[A: ClassTag, RedK: ClassTag, RedV: ClassTag](nMappers: Int, nReducers: Int)
                                                                        (mapFun: A => Traversable[KeyVal[RedK, RedV]])
                                                                        (redFun: (RedV, RedV) => RedV) extends Actor {
  val reducer = Reducer[RedK, RedV](self, nReducers)(redFun)
  val mapper = Mapper[A, KeyVal[RedK, RedV]](reducer, nMappers)(mapFun)

  var progress = 0
  var finalAggregate: Map[RedK, RedV] = Map()

  def receive = {
    case StartCalculations =>
      println(s"STARTING MAPPERS")
      mapper ! Stream.continually(1).take(1000000).iterator
      mapper ! EndOfData

    case ReducerResult(agAny) =>
      val ag = agAny.asInstanceOf[Map[RedK, RedV]]
      finalAggregate = finalAggregate ++ ag

    case EndOfData =>
      PiPrintResults(finalAggregate)
      self ! StartCalculations
    // context.system.scheduler.scheduleOnce(1.second, self, HammerdownProtocol)

    case HammerdownProtocol => context.system.shutdown()
  }
}

object PiMapReduceSupervisor {
  def apply[A: ClassTag, RedK: ClassTag, RedV: ClassTag](nm: Int, nr: Int)(mapFun: A => Seq[KeyVal[RedK, RedV]])
                                                        (redFun: (RedV, RedV) => RedV) = new PiMapReduceSupervisor(nm, nr)(mapFun)(redFun)
}

object PiPrintResults {
  def apply[K, V](finalAggregate: Map[K, V]) = {
    val ag = finalAggregate.asInstanceOf[Map[String, BigDecimal]]
    val s = ag("SUM")
    val n = ag("N")
    println(s"Pi is roughly 4 * $s / $n ${4.0 * s / n}")
  }
}

case object StartCalculations