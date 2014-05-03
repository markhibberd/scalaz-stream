package scalaz.stream2

import org.scalacheck.Properties
import org.scalacheck.Prop._

import scalaz.concurrent.{Strategy, Task}
import scalaz.stream2.Process._
import scalaz.{\/, -\/, \/-}
import scala.concurrent.duration._
import scala.concurrent.SyncVar


object WyeSpec extends  Properties("Wye"){

  implicit val S = Strategy.DefaultStrategy
  implicit val scheduler = scalaz.stream2.DefaultScheduler


  property("feedL") = secure {
    val w = wye.feedL(List.fill(10)(1))(process1.id)
    val x = Process.range(0,100).wye(halt)(w).runLog.run
    x.toList == (List.fill(10)(1) ++ List.range(0,100))
  }

  property("feedR") = secure {
    val w = wye.feedR(List.fill(10)(1))(wye.merge[Int])
    val x = Process.range(0,100).wye(halt)(w).runLog.run
    x.toList == (List.fill(10)(1) ++ List.range(0,100))
  }

  // ensure that wye terminates when once side of it is infinite
  // and other side of wye is either empty, or one.
  property("infinite.one.side") = secure {
    import ReceiveY._
    def whileBoth[A,B]: Wye[A,B,Nothing] = {
      def go: Wye[A,B,Nothing] = receiveBoth[A,B,Nothing] {
        case HaltL(_) | HaltR(_) => halt
        case _ => go
      }
      go
    }
    val inf = Process.constant(0)
    val one = eval(Task.now(1))
    val empty = Process[Int]()
    inf.wye(empty)(whileBoth).run.timed(3000).attempt.run == \/-(()) &&
      empty.wye(inf)(whileBoth).run.timed(3000).attempt.run == \/-(()) &&
      inf.wye(one)(whileBoth).run.timed(3000).attempt.run == \/-(()) &&
      one.wye(inf)(whileBoth).run.timed(3000).attempt.run == \/-(())
  }

  property("either") = secure {
    val w = wye.either[Int,Int]
    val s = Process.constant(1).take(1)
    s.wye(s)(w).runLog.run.map(_.fold(identity, identity)).toList == List(1,1)
  }

  property("interrupt.source.halt") = secure {
    val p1 = Process(1,2,3,4,6).toSource
    val i1 = repeatEval(Task.now(false))
    val v = i1.wye(p1)(wye.interrupt).runLog.run.toList
    v == List(1,2,3,4,6)
  }

  property("interrupt.signal.halt") = secure {
    val p1 = Process.range(1,1000)
    val i1 = Process(1,2,3,4).map(_=>false).toSource
    val v = i1.wye(p1)(wye.interrupt).runLog.run.toList
    v.size < 1000
  }

  property("interrupt.signal.true") = secure {
    val p1 = Process.range(1,1000)
    val i1 = Process(1,2,3,4).map(_=>false).toSource ++ emit(true) ++ repeatEval(Task.now(false))
    val v = i1.wye(p1)(wye.interrupt).runLog.run.toList
    v.size < 1000
  }

  property("either.terminate-on-both") = secure {
    val e = (Process.range(0, 20) either Process.range(0, 20)).runLog.timed(1000).run
    (e.collect { case -\/(v) => v } == (0 until 20).toSeq) :| "Left side is merged ok" &&
      (e.collect { case \/-(v) => v } == (0 until 20).toSeq) :| "Right side is merged ok"
  }

  property("either.terminate-on-downstream") = secure {
    val e = (Process.range(0, 20) either Process.range(0, 20)).take(10).runLog.timed(1000).run
    e.size == 10
  }


  property("either.continue-when-left-done") = secure {
    val e = (Process.range(0, 20) either (awakeEvery(25 millis).take(20))).runLog.timed(5000).run
    ("Both sides were emitted" |: (e.size == 40))  &&
      ("Left side terminated earlier" |: e.zipWithIndex.filter(_._1.isLeft).lastOption.exists(_._2 < 35))   &&
      ("Right side was last" |:  e.zipWithIndex.filter(_._1.isRight).lastOption.exists(_._2 == 39))
  }

  property("either.continue-when-right-done") = secure {
    val e = ((awakeEvery(25 millis).take(20)) either Process.range(0, 20)).runLog.timed(5000).run
    ("Both sides were emitted" |: (e.size == 40)) &&
      ("Right side terminated earlier" |: e.zipWithIndex.filter(_._1.isRight).lastOption.exists(_._2 < 35))   &&
      ("Left side was last" |: e.zipWithIndex.filter(_._1.isLeft).lastOption.exists(_._2 == 39))
  }

  property("either.left.failed") = secure {
    val e =
      ((Process.range(0, 2) ++ eval(Task.fail(Bwahahaa))) either Process.range(10, 20))
       .attempt().runLog.timed(3000).run

    (e.collect { case \/-(-\/(v)) => v } == (0 until 2)) :| "Left side got collected" &&
      (e.collect { case \/-(\/-(v)) => v } == (10 until 20)) :| "Right side got collected" &&
      (e.collect { case -\/(rsn) => rsn }.nonEmpty) :| "exception was propagated"

  }

  property("either.right.failed") = secure {
    val e =
      (Process.range(0, 2) either (Process.range(10, 20) ++ eval(Task.fail(Bwahahaa))))
      .attempt().runLog.timed(3000).run

    (e.collect { case \/-(-\/(v)) => v } == (0 until 2)) :| "Left side got collected" &&
      (e.collect { case \/-(\/-(v)) => v } == (10 until 20)) :| "Right side got collected" &&
      (e.collect { case -\/(rsn) => rsn }.nonEmpty) :| "exception was propagated"

  }

  property("either.cleanup-out-halts") = secure {
    val syncL = new SyncVar[Int]
    val syncR = new SyncVar[Int]
    val syncO = new SyncVar[Int]

    // Left process terminates earlier.
    val l = Process.awakeEvery(10 millis) onComplete eval_(Task.delay{ Thread.sleep(500);syncL.put(100)})
    val r = Process.awakeEvery(10 millis) onComplete eval_(Task.delay{ Thread.sleep(600);syncR.put(200)})

    val e = ((l either r).take(10) onComplete eval_(Task.delay(syncO.put(1000)))).runLog.timed(3000).run

    (e.size == 10) :| "10 first was taken" &&
      (syncO.get(3000) == Some(1000)) :| "Out side was cleaned" &&
      (syncL.get(0) == Some(100)) :| "Left side was cleaned" &&
      (syncR.get(0) == Some(200)) :| "Right side was cleaned"

  }


  // checks we are safe on thread stack even after emitting million values
  // non-deterministically from both sides
  property("merge.million") = secure {
    val count = 1000000
    val m =
      (Process.range(0,count ) merge Process.range(0, count)).flatMap {
        (v: Int) =>
          if (v % 1000 == 0) {
            val e = new java.lang.Exception
            emit(e.getStackTrace.length)
          } else {
            halt
          }
      }.fold(0)(_ max _)

    m.runLog.timed(180000).run.map(_ < 100) == Seq(true)

  }

  // checks we are able to handle reasonable number of deeply nested wye`s .
  property("merge.deep-nested") = secure {
    val count = 20
    val deep = 100

    def src(of: Int) = Process.range(0, count).map((_, of))

    val merged =
      (1 until deep).foldLeft(src(0))({
        case (p, x) => p merge src(x)
      })

    val m =
      merged.flatMap {
        case (v, of) =>
          if (v % 10 == 0) {
            val e = new java.lang.Exception
            emit(e.getStackTrace.length)
          } else {
            halt
          }
      }.fold(0)(_ max _)

    m.runLog.timed(300000).run.map(_ < 100) == Seq(true)

  }

  //tests that wye correctly terminates drained process
  property("merge-drain-halt") = secure {

    val effect:Process[Task,Int] = Process.constant(()).drain

    val pm1 = effect.wye(Process(1000,2000).toSource)(wye.merge).take(2)
    val pm2 = Process(3000,4000).toSource.wye(effect)(wye.merge).take(2)

    (pm1 ++ pm2).runLog.timed(3000).run.size == 4
  }

}