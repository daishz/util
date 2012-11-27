package com.twitter.util

import com.twitter.concurrent.Scheduler
import scala.annotation.tailrec
import scala.collection.mutable

object Promise {
  /**
   * A continuation stored from a promise.
   */
   private trait K[A] extends (Try[A] => Unit) {
     /** Depth tag used for scheduling */
     def depth: Short
   }

   private object K {
     val depthOfK: K[_] => Short = _.depth
   }

  /**
   * A monitored continuation.
   *
   * @param saved The saved local context of the invocation site
   *
   * @param traceCtx An object recorded in the future trace context
   * upon invocation
   *
   * @param k the closure to invoke in the saved context, with the
   * provided result
   *
   * @param depth a tag used to store the chain depth of this context
   * for scheduling purposes.
   */
  private class Monitored[A](
      saved: Local.Context,
      traceCtx: AnyRef,
      k: Try[A] => Unit,
      val depth: Short)
    extends K[A]
  {
    def apply(result: Try[A]) {
      val current = Local.save()
      Local.restore(saved)
      Future.trace.record(traceCtx)
      try k(result)
      catch Monitor.catcher
      finally Local.restore(current)
    }
  }

  /**
   * An unmonitored continuation.
   *
   * @param saved The saved local context of the invocation site
   *
   * @param traceCtx An object recorded in the future trace context
   * upon invocation
   *
   * @param k the closure to invoke in the saved context, with the
   * provided result
   *
   * @param depth a tag used to store the chain depth of this context
   * for scheduling purposes.
   */
  private class Unmonitored[A](
      saved: Local.Context,
      traceCtx: AnyRef,
      k: Try[A] => Unit,
      val depth: Short)
    extends K[A]
  {
    def apply(result: Try[A]) {
      val current = Local.save()
      Local.restore(saved)
      Future.trace.record(traceCtx)
      try k(result)
      finally Local.restore(current)
    }
  }

  /*
   * Performance notes
   *
   * The following is a characteristic CDF of wait queue lengths.
   * This was retrieved by instrumenting the promise implementation
   * and running it with the 'finagle-topo' test suite.
   *
   *   0 26%
   *   1 77%
   *   2 94%
   *   3 97%
   *
   * Which amounts to .94 callbacks on average.
   *
   * Due to OOPS compression on 64-bit architectures, objects that
   * have one field are of the same size as objects with two. We
   * exploit this by explicitly caching the first callback in its own
   * field, thus avoiding additional representation overhead in 77%
   * of promises.
   *
   * todo: do this sort of profiling in a production app with
   * production load.
   */
  private sealed trait State[+A]
  private case class Waiting[A](first: K[A], rest: List[K[A]]) extends State[A]
  private case class Interruptible[A](waitq: List[K[A]], handler: PartialFunction[Throwable, Unit]) extends State[A]
  private case class Interrupted[A](waitq: List[K[A]], signal: Throwable) extends State[A]
  private case class Done[A](result: Try[A]) extends State[A]
  private case class Linked[A](p: Promise[A]) extends State[A]

  private val initState: State[Nothing] = Waiting(null, Nil)
  private val unsafe = Unsafe()
  private val stateOff = unsafe.objectFieldOffset(classOf[Promise[_]].getDeclaredField("state"))

  sealed trait Responder[A] extends Future[A] {
    protected def depth: Short
    protected def parent: Promise[A]
    protected[util] def continue(k: K[A])

    /**
     * Note: exceptions in responds are monitored.  That is, if the
     * computation {{k}} throws a raw (ie.  not encoded in a Future)
     * exception, it is handled by the current monitor, see
     * {{com.twitter.util.Monitor}} for details.
     */
    def respond(traceCtx: AnyRef, k: Try[A] => Unit): Future[A] = {
      continue(new Monitored(Local.save(), traceCtx, k, depth))
      new Chained(parent, (depth+1).toShort)
    }

    protected[this] def transform[B](traceCtx: AnyRef, f: Try[A] => Future[B]): Future[B] = {
      // TODO: A future optimization might be to make
      // ``transforming'' a Promise state, which can keep a reference
      // to the transformee, allowing us to avoid keeping a separate
      // interrupt handler.
      val promise = interrupts[B](this)

      val transformer: Try[A] => Unit = { r =>
        promise.become(
          try f(r) catch {
            case e => Future.exception(e)
          }
        )
      }

      continue(new Unmonitored(Local.save(), traceCtx, transformer, depth))

      promise
    }
  }

  /** A future that is chained from a parent promise with a certain depth. */
  private class Chained[A](val parent: Promise[A], val depth: Short) extends Future[A] with Responder[A] {
    assert(depth < Short.MaxValue, "Future chains cannot be longer than 32766!")

    def get(timeout: Duration) = parent.get(timeout)
    def poll = parent.poll
    def raise(interrupt: Throwable) = parent.raise(interrupt)

    protected[util] def continue(k: K[A]) = parent.continue(k)

    override def toString = "Future@%s(depth=%s,parent=%s)".format(hashCode, depth, parent)
  }

  // PUBLIC API

  case class ImmutableResult(message: String) extends Exception(message)

  /** Create a new, empty, promise of type {{A}}. */
  def apply[A](): Promise[A] = new Promise[A]

  /**
   * Create a promise that interrupts all of ''fs''. In particular:
   * the returned promise handles an interrupt when any of ''fs'' do.
   */
  def interrupts[A](fs: Future[_]*): Promise[A] = {
    val p = new Promise[A]
    p.setInterruptHandler {
      case intr => for (f <- fs) f.raise(intr)
    }

    p
  }
}

/**
 * A writeable [[com.twitter.util.Future]] that supports merging.
 * Callbacks (responders) of Promises are scheduled with
 * [[com.twitter.concurrent.Scheduler]].
 *
 * =Implementation details=
 *
 * A Promise is in one of five states: `Waiting`, `Interruptible`,
 * `Interrupted`, `Done` and `Linked` where `Interruptible` and
 * `Interrupted` are variants of `Waiting` to deal with future
 * interrupts. Promises are concurrency-safe, using lock-free operations
 * throughout. Callback dispatch is scheduled with
 * [[com.twitter.concurrent.Scheduler]].
 *
 * Waiters are stored as a [[com.twitter.util.Promise.K]]. `K`s
 * (mnemonic: continuation) specifies a `depth`. This is used to
 * implement Promise chaining: a callback with depth `d` is invoked only
 * after all callbacks with depth < `d` have already been invoked.
 *
 * `Promise.become` merges two promises: they are declared equivalent.
 * `become` merges the states of the two promises, and links one to the
 * other. Thus promises support the analog to tail-call eliminination: no
 * space leak is incurred from `flatMap` in the tail position since
 * intermediate promises are merged into the root promise.
 *
 * A number of optimizations are employed to conserve ''space'': we pay
 * particular heed to the JVM's object representation, in particular for
 * OpenJDK (HotSpot) version 7 running on 64-bit architectures with
 * compressed OOPS. See comments on [[com.twitter.util.Promise.State]]
 * for details.
 */
class Promise[A] extends Future[A] with Promise.Responder[A] {
  import Promise._

  protected final def depth = 0
  protected final def parent = this

  @volatile private[this] var state: Promise.State[A] = initState

  def this(handleInterrupt: PartialFunction[Throwable, Unit]) {
    this()
    this.state = Interruptible(Nil, handleInterrupt)
  }

  def this(result: Try[A]) {
    this()
    this.state = Done(result)
  }

  override def toString = "Promise@%s(state=%s)".format(hashCode, state)

  @inline private[this] def cas(oldState: State[A], newState: State[A]): Boolean =
    unsafe.compareAndSwapObject(this, stateOff, oldState, newState)

  private[this] def runq(first: K[A], rest: List[K[A]], result: Try[A]) = Scheduler.submit(
    new Runnable {
      def run() {
        // It's always safe to run ``first'' ahead of everything else
        // since the only way to get a chainer is to register a
        // callback (which would always have depth 0).
        if (first ne null) first(result)
        var k: K[A] = null

        // Depth 0
        var ks = rest
        while (ks != Nil) {
          k = ks.head
          if (k.depth == 0)
            k(result)
          ks = ks.tail
        }

        // Depth 1
        ks = rest
        while (ks != Nil) {
          k = ks.head
          if (k.depth == 1)
            k(result)
          ks = ks.tail
        }

        // Depth > 1 (Rare: ~6%)
        var rem: mutable.Buffer[K[A]] = null
        ks = rest
        while (ks != Nil) {
          k = ks.head
          if (k.depth > 1) {
            if (rem == null) rem = mutable.ArrayBuffer()
            rem += k
          }
          ks = ks.tail
        }

        if (rem eq null)
          return

        val sorted = rem.sortBy(K.depthOfK)
        var i = 0
        while (i < sorted.size) {
          sorted(i).apply(result)
          i += 1
        }
      }
    })

  /**
   * (Re)sets the interrupt handler. There is only
   * one active interrupt handler.
   *
   * @param f the new interrupt handler
   */
  @tailrec
  final def setInterruptHandler(f: PartialFunction[Throwable, Unit]) {
    state match {
      case Linked(p) => p.setInterruptHandler(f)

      case s@Waiting(first, rest) =>
        val waitq = if (first eq null) rest else first :: rest
        if (!cas(s, Interruptible(waitq, f)))
          setInterruptHandler(f)

      case s@Interruptible(waitq, _) =>
        if (!cas(s, Interruptible(waitq, f)))
          setInterruptHandler(f)

      case Interrupted(_, signal) =>
        if (f.isDefinedAt(signal))
          f(signal)

      case Done(_) => // ignore
    }
  }

  /**
   * Forward interrupts to another future.
   *
   * @param other the Future to which interrupts are forwarded.
   */
  def forwardInterruptsTo(other: Future[_]) {
    setInterruptHandler { case intr => other.raise(intr) }
  }

  @tailrec final
  def raise(intr: Throwable) = state match {
    case Linked(p) => p.raise(intr)
    case s@Interruptible(waitq, handler) =>
      if (!cas(s, Interrupted(waitq, intr))) raise(intr) else {
        if (handler.isDefinedAt(intr))
          handler(intr)
      }

    case s@Interrupted(waitq, _) =>
      if (!cas(s, Interrupted(waitq, intr)))
        raise(intr)

    case s@Waiting(first, rest)  =>
      val waitq = if (first eq null) rest else first :: rest
      if (!cas(s, Interrupted(waitq, intr)))
        raise(intr)

    case Done(_) =>
  }

  def get(timeout: Duration): Try[A] =
    state match {
      case Linked(p) => p.get(timeout)
      case Done(res) => res
      case Waiting(_, _) | Interruptible(_, _) | Interrupted(_, _) =>
        val condition = new java.util.concurrent.CountDownLatch(1)
        respond { _ => condition.countDown() }
        val (v, u) = timeout.inTimeUnit
        Scheduler.flush()
        if (condition.await(v, u)) {
          val Done(res) = state
          res
        } else {
          Throw(new TimeoutException(timeout.toString))
        }
    }

  /**
   * Returns this promise's interrupt if it is interrupted.
   */
  def isInterrupted: Option[Throwable] = state match {
    case Linked(p) => p.isInterrupted
    case Interrupted(_, intr) => Some(intr)
    case Done(_) | Waiting(_, _) | Interruptible(_, _) => None
  }

  /**
   * Become the other promise. `become` declares an equivalence
   * relation: `this` and `other` are the ''same''.
   *
   * By becoming `other`, its waitlists are now merged into `this`'s,
   * and `this` becomes canonical. The same is true of interrupt
   * handlers: `other`'s interrupt handler becomes active, but is
   * stored canonically by `this` - further references are forwarded.
   * Note that `this` must be unsatisfied at the time of the call,
   * and not race with any other setters. `become` is a form of
   * satisfying the promise.
   *
   * This has the combined effect of compressing the `other` into
   * `this`, effectively providing a form of tail-call elimination
   * when used in recursion constructs. `transform` (and thus any
   * other combinator) use this to compress Futures, freeing them
   * from space leaks when used with recursive constructions.
   *
   * '''Note:''' do not use become with cyclic graphs of futures: the
   * behavior of racing `a.become(b)` with `b.become(a)` is undefined
   * (where `a` and `b` may resolve as such transitively).
   */
  def become(other: Future[A]) {
    if (other.isInstanceOf[Promise[_]]) {
      val that = other.asInstanceOf[Promise[A]]
      that.link(compress())
    } else {
      other.proxyTo(this)
      forwardInterruptsTo(other)
    }
  }

  /**
   * Populate the Promise with the given result.
   *
   * @throws ImmutableResult if the Promise is already populated
   */
  def setValue(result: A) { update(Return(result)) }

  /**
   * Populate the Promise with the given exception.
   *
   * @throws ImmutableResult if the Promise is already populated
   */
  def setException(throwable: Throwable) { update(Throw(throwable)) }

  /**
   * Populate the Promise with the given Try. The try can either be a
   * value or an exception. setValue and setException are generally
   * more readable methods to use.
   *
   * @throws ImmutableResult if the Promise is already populated
   */
  def update(result: Try[A]) {
    updateIfEmpty(result) || {
      throw new ImmutableResult("Result set multiple times: " + result)
    }
  }

  /**
   * Populate the Promise with the given Try. The try can either be a
   * value or an exception. setValue and setException are generally
   * more readable methods to use.
   *
   * @return true only if the result is updated, false if it was already set.
   */
  @tailrec
  final def updateIfEmpty(result: Try[A]): Boolean = state match {
    case Done(_) => false
    case s@Waiting(first, rest) =>
      if (!cas(s, Done(result))) updateIfEmpty(result) else {
        runq(first, rest, result)
        true
      }
    case s@Interruptible(waitq, _) =>
      if (!cas(s, Done(result))) updateIfEmpty(result) else {
        runq(null, waitq, result)
        true
      }
    case s@Interrupted(waitq, _) =>
      if (!cas(s, Done(result))) updateIfEmpty(result) else {
        runq(null, waitq, result)
        true
      }
    case Linked(p) => p.updateIfEmpty(result)
  }

  @tailrec
  protected[util] final def continue(k: K[A]) {
    state match {
      case Done(v) =>
        Scheduler.submit(new Runnable {
          def run() {
            k(v)
          }
        })
      case s@Waiting(first, rest) if first == null =>
        if (!cas(s, Waiting(k, rest)))
          continue(k)
      case s@Waiting(first, rest) =>
        if (!cas(s, Waiting(first, k :: rest)))
          continue(k)
      case s@Interruptible(waitq, handler) =>
        if (!cas(s, Interruptible(k :: waitq, handler)))
          continue(k)
      case s@Interrupted(waitq, signal) =>
        if (!cas(s, Interrupted(k :: waitq, signal)))
          continue(k)
      case Linked(p) =>
        p.continue(k)
    }
  }

  protected final def compress(): Promise[A] = state match {
    case s@Linked(p) =>
      val target = p.compress()
      cas(s, Linked(target))
      target
    case _ => this
  }

  @tailrec
  protected final def link(target: Promise[A]) {
    if (this eq target) return

    state match {
      case s@Linked(p) =>
        if (cas(s, Linked(target)))
          p.link(target)
        else
          link(target)

      case s@Done(value) =>
        if (!target.updateIfEmpty(value) && value != target()) {
          throw new IllegalArgumentException(
            "Cannot link two Done Promises with differing values")
        }

      case s@Waiting(first, rest) =>
        if (!cas(s, Linked(target))) link(target) else {
          if (first != null)
            target.continue(first)
          var ks = rest
          while (ks != Nil) {
            target.continue(ks.head)
            ks = ks.tail
          }
        }

      case s@Interruptible(waitq, handler) =>
        if (!cas(s, Linked(target))) link(target) else {
          var ks = waitq
          while (ks != Nil) {
            target.continue(ks.head)
            ks = ks.tail
          }
          target.setInterruptHandler(handler)
        }

      case s@Interrupted(waitq, signal) =>
        if (!cas(s, Linked(target))) link(target) else {
          var ks = waitq
          while (ks != Nil) {
            target.continue(ks.head)
            ks = ks.tail
          }
          target.raise(signal)
        }
    }
  }

  def poll: Option[Try[A]] = state match {
    case Linked(p) => p.poll
    case Done(res) => Some(res)
    case Waiting(_, _) | Interruptible(_, _) | Interrupted(_, _) => None
  }
}