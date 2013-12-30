package scalaz.stream

import scalaz.\/
import scalaz.concurrent._
import scalaz.stream.actor.actors
import scalaz.stream.actor.message
import scalaz.stream.async.mutable.BoundedQueue
import scalaz.stream.merge.{MergeXStrategies, MergeX}
import scalaz.stream.async.immutable

package object async {

  import mutable.{Queue,Ref,Signal,Topic}

  /**
   * Convert from an `Actor` accepting `message.queue.Msg[A]` messages 
   * to a `Queue[A]`. 
   */
  def actorQueue[A](actor: Actor[message.queue.Msg[A]]): Queue[A] =
    new Queue[A] {
      def enqueueImpl(a: A): Unit = actor ! message.queue.enqueue(a)
      def dequeueImpl(cb: (Throwable \/ A) => Unit): Unit = actor ! message.queue.Dequeue(cb)
      def fail(err: Throwable): Unit = actor ! message.queue.fail(err)
      def cancel: Unit = actor ! message.queue.cancel
      def close: Unit = actor ! message.queue.close
    }

  /** 
   * Convert from an `Actor` accepting `message.queue.Msg[A]` messages 
   * to a `Queue[A]`. 
   */
  def actorRef[A](actor: Actor[message.ref.Msg[A]]): Ref[A] =
    new Ref[A] {

      import message.ref._

      @volatile var init = false
      protected[stream] def set_(f: (Option[A]) => Option[A], cb: (\/[Throwable, Option[A]]) => Unit, old: Boolean): Unit =  {
        actor ! Set(f,cb,old)
        init = true
      }

      protected[stream] def get_(cb: (\/[Throwable, (Int,A)]) => Unit, onlyChanged: Boolean, last: Int) : Unit = 
        actor ! Get(cb,onlyChanged,last)
 
      protected[stream] def fail_(t: Throwable, cb: (Throwable) => Unit):Unit =  
        actor ! Fail(t,cb)

      def isSet = init
    }

  /** 
   * Create a new continuous signal which may be controlled asynchronously.
   * All views into the returned signal are backed by the same underlying
   * asynchronous `Ref`.
   */
  def signal[A](implicit S: Strategy = Strategy.DefaultStrategy): Signal[A] =
    ref[A].signal
    
  /** 
   * Create a source that may be added to or halted asynchronously 
   * using the returned `Queue`, `q`. On calling `q.enqueue(a)`, 
   * unless another thread is already processing the elements 
   * in the queue, listeners on the queue will be run using the calling
   * thread of `q.enqueue(a)`, which means that enqueueing is not
   * guaranteed to take constant time. If this is not desired, use 
   * `queue` with a `Strategy` other than `Strategy.Sequential`.
   */
  def localQueue[A]: (Queue[A], Process[Task,A]) = 
    queue[A](Strategy.Sequential)

  /**
   * Converts discrete process to signal.
   * @param source
   * @tparam A
   */
  def toSignal[A](source:Process[Task,A])(implicit S: Strategy = Strategy.DefaultStrategy): immutable.Signal[A] = new immutable.Signal[A] {
    def changes: Process[Task, Unit] = discrete.map(_ => ())
    def continuous: Process[Task, A] = discrete.wye(Process.constant(()))(wye.echoLeft)(S)
    def discrete: Process[Task, A] = source
    def changed: Process[Task, Boolean] = discrete.map(_ => true) merge Process.constant(false)
  }


  /**
   * Creates bounded queue that is bound by supplied max size bound.
   * Please see [[scalaz.stream.async.mutable.BoundedQueue]] for more details.
   * @param max maximum size of queue. When <= 0 (default) queue is unbounded
   */
  def boundedQueue[A](max: Int = 0)(implicit S: Strategy = Strategy.DefaultStrategy): BoundedQueue[A] = {
    val mergex = MergeX(MergeXStrategies.boundedQ[A](max), Process.halt)(S)
    new BoundedQueue[A] {
      def enqueueOne(a: A): Task[Unit] = mergex.receiveOne(a)
      def dequeue: Process[Task, A] = mergex.downstreamO
      def size: immutable.Signal[Int] = toSignal(mergex.downstreamW)
      def enqueueAll(xa: Seq[A]): Task[Unit] = mergex.receiveAll(xa)
      def enqueue: Process.Sink[Task, A] = mergex.upstreamSink
      def fail(rsn: Throwable): Task[Unit] = mergex.downstreamClose(rsn)
    }
  }

  /** 
   * Returns a continuous `Process` whose value can be set 
   * asynchronously using the returned `Ref`. Callbacks will be 
   * run in the calling thread unless another thread is already
   * reading from the `Ref`, so `set` is not guaranteed to take
   * constant time. If this is not desired, use `ref` with a
   * `Strategy` other than `Strategy.Sequential`.
   */
  def localRef[A]: Ref[A] = 
    ref[A](Strategy.Sequential)
  

  /** 
   * Create a source that may be added to or halted asynchronously 
   * using the returned `Queue`. See `async.Queue`. As long as the
   * `Strategy` is not `Strategy.Sequential`, enqueueing is 
   * guaranteed to take constant time, and consumers will be run on
   * a separate logical thread. Current implementation is based on 
   * `actor.queue`.
   */
  def queue[A](implicit S: Strategy = Strategy.DefaultStrategy): (Queue[A], Process[Task,A]) = 
    actors.queue[A] match { case (snk, p) => (actorQueue(snk), p) }

  /**
   * Returns a ref, that can create continuous process, that can be set 
   * asynchronously using the returned `Ref`.
   */
  def ref[A](implicit S: Strategy = Strategy.DefaultStrategy): Ref[A] = 
    actors.ref[A](S) match { case (snk, p) => actorRef(snk)}

  /** 
   * Convert an `Queue[A]` to a `Sink[Task, A]`. The `cleanup` action will be 
   * run when the `Sink` is terminated.
   */
  def toSink[A](q: Queue[A], cleanup: Queue[A] => Task[Unit] = (q: Queue[A]) => Task.delay {}): Process.Sink[Task,A] =
    io.resource(Task.now(q))(cleanup)(q => Task.delay { (a:A) => Task.now(q.enqueue(a)) })

  /**
   * Returns a topic, that can create publisher (sink) and subscriber (source)
   * processes that can be used to publish and subscribe asynchronously. 
   * Please see `Topic` for more info.
   */
  def topic[A](implicit S: Strategy = Strategy.DefaultStrategy): Topic[A] = {
     val mergex = MergeX(MergeXStrategies.publishSubscribe[A], Process.halt)(S)
     new Topic[A] {
       def publish: Process.Sink[Task, A] = mergex.upstreamSink
       def subscribe: Process[Task, A] = mergex.downstreamO
       def publishOne(a: A): Task[Unit] = mergex.receiveOne(a)
       def fail(err: Throwable): Task[Unit] = mergex.downstreamClose(err)
     }
  }
}

