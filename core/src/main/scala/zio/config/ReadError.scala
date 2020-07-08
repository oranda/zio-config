package zio.config

import zio.config.AnnotatedRead.Annotation
import zio.config.ReadError.Step

import scala.util.control.NoStackTrace

sealed trait ReadError[A] extends Exception with NoStackTrace { self =>
  def annotations: Set[Annotation]

  override def getMessage: String =
    prettyPrint()

  def fold[B](alternative: B)(f: PartialFunction[ReadError[A], B])(g: (B, B) => B, zero: B): B = {
    def go(list: List[ReadError[A]]): B =
      list.foldLeft(zero)((a, b) => g(b.fold(alternative)(f)(g, zero), a))

    self match {
      case e @ ReadError.MissingValue(_, _, _)    => f.applyOrElse(e, (_: ReadError[A]) => alternative)
      case e @ ReadError.FormatError(_, _, _, _)  => f.applyOrElse(e, (_: ReadError[A]) => alternative)
      case e @ ReadError.ConversionError(_, _, _) => f.applyOrElse(e, (_: ReadError[A]) => alternative)
      case e @ ReadError.Irrecoverable(list, _)   => f.applyOrElse(e, (_: ReadError[A]) => go(list))
      case e @ ReadError.OrErrors(list, _)        => f.applyOrElse(e, (_: ReadError[A]) => go(list))
      case e @ ReadError.ZipErrors(list, _)       => f.applyOrElse(e, (_: ReadError[A]) => go(list))
      case e @ ReadError.ListErrors(list, _)      => f.applyOrElse(e, (_: ReadError[A]) => go(list))
      case e @ ReadError.MapErrors(list, _)       => f.applyOrElse(e, (_: ReadError[A]) => go(list))
    }
  }

  def getListOfMissingValueSteps: List[List[Step[A]]] =
    fold(List.empty[List[Step[A]]]) {
      case ReadError.MissingValue(steps, _, _) => List(steps)
    }(_ ++ _, List.empty[List[Step[A]]])

  final def prettyPrint(keyDelimiter: Char = '.'): String = {

    sealed trait Segment
    sealed trait Step extends Segment

    final case class Sequential(all: List[Step])     extends Segment
    final case class Parallel(all: List[Sequential]) extends Step
    final case class Failure(lines: List[String])    extends Step

    def renderSteps[A](steps: List[ReadError.Step[A]]): String =
      steps
        .foldLeft(new StringBuilder()) {
          case (r, ReadError.Step.Key(k))   => r.append(keyDelimiter).append(k.toString)
          case (r, ReadError.Step.Index(i)) => r.append('[').append(i).append(']')
        }
        .delete(0, 1)
        .toString()

    def prefixBlock(values: List[String], p1: String, p2: String): List[String] =
      values match {
        case Nil => Nil
        case head :: tail =>
          (p1 + head) :: tail.map(p2 + _)
      }

    def parallelSegments[A](readError: ReadError[A]): List[Sequential] =
      readError match {
        case ReadError.ZipErrors(head :: tail, _)     => parallelSegments(head) ++ tail.flatMap(parallelSegments)
        case ReadError.ListErrors(head :: tail, _)    => parallelSegments(head) ++ tail.flatMap(parallelSegments)
        case ReadError.MapErrors(head :: tail, _)     => parallelSegments(head) ++ tail.flatMap(parallelSegments)
        case ReadError.Irrecoverable(head :: tail, _) => parallelSegments(head) ++ tail.flatMap(parallelSegments)
        case _                                        => List(readErrorToSequential(readError))
      }

    def linearSegments[A](readError: ReadError[A]): List[Step] =
      readError match {
        case ReadError.OrErrors(head :: tail, _) => linearSegments(head) ++ tail.flatMap(linearSegments)
        case _                                   => readErrorToSequential(readError).all
      }

    def renderMissingValue[A](err: ReadError.MissingValue[A]): Sequential = {
      val strings =
        "MissingValue" :: s"path: ${renderSteps(err.path)}" :: Nil

      Sequential(
        err.detail match {
          case ::(head, next) =>
            List(Failure(strings :+ s"Details: ${(head :: next).mkString(", ")}"))
          case Nil =>
            List(Failure(strings))
        }
      )
    }

    def renderFormatError[A](err: ReadError.FormatError[A]): Sequential = {
      val strings =
        "FormatError" :: s"cause: ${err.message}" :: s"path: ${renderSteps(err.path)}" :: Nil

      Sequential(
        err.detail match {
          case ::(head, next) =>
            List(Failure(strings :+ s"Details: ${(head :: next).mkString(", ")}"))
          case Nil =>
            List(Failure(strings))
        }
      )
    }

    def renderConversionError[A](err: ReadError.ConversionError[A]): Sequential =
      Sequential(
        List(
          Failure(
            "ConversionError" :: s"cause: ${err.message}" :: s"path: ${renderSteps(err.path)}" :: Nil
          )
        )
      )

    def readErrorToSequential[A](readError: ReadError[A]): Sequential =
      readError match {
        case r: ReadError.MissingValue[A]    => renderMissingValue(r)
        case r: ReadError.FormatError[A]     => renderFormatError(r)
        case r: ReadError.ConversionError[A] => renderConversionError(r)
        case t: ReadError.OrErrors[A]        => Sequential(linearSegments(t))
        case b: ReadError.ZipErrors[A]       => Sequential(List(Parallel(parallelSegments(b))))
        case b: ReadError.ListErrors[A]      => Sequential(List(Parallel(parallelSegments(b))))
        case b: ReadError.MapErrors[A]       => Sequential(List(Parallel(parallelSegments(b))))
        case b: ReadError.Irrecoverable[A]   => Sequential(List(Parallel(parallelSegments(b))))
      }

    def format(segment: Segment): List[String] =
      segment match {
        case Failure(lines) =>
          prefixBlock(lines, "─", " ")
        case Parallel(all) =>
          List(("══╦" * (all.size - 1)) + "══╗") ++
            all.foldRight[List[String]](Nil) {
              case (current, acc) =>
                prefixBlock(acc, "  ║", "  ║") ++
                  prefixBlock(format(current), "  ", "  ")
            }
        case Sequential(all) =>
          all.flatMap { segment =>
            List("║") ++
              prefixBlock(format(segment), "╠", "║")
          } ++ List("▼")
      }

    val sequence = readErrorToSequential(self)

    ("ReadError:" :: {
      sequence match {
        // use simple report for single failures
        case Sequential(List(Failure(readError))) => readError

        case _ => format(sequence).updated(0, "╥")
      }
    }).mkString(System.lineSeparator())
  }

  def size: Int =
    self match {
      case ReadError.MissingValue(_, _, _)    => 1
      case ReadError.FormatError(_, _, _, _)  => 1
      case ReadError.ConversionError(_, _, _) => 1
      case ReadError.OrErrors(list, _)        => list.map(_.size).sum
      case ReadError.ZipErrors(list, _)       => list.map(_.size).sum
      case ReadError.ListErrors(list, _)      => list.map(_.size).sum
      case ReadError.MapErrors(list, _)       => list.map(_.size).sum
      case ReadError.Irrecoverable(list, _)   => list.map(_.size).sum
    }

  def sizeOfZipAndOrErrors: Int =
    self match {
      case ReadError.MissingValue(_, _, _)    => 1
      case ReadError.FormatError(_, _, _, _)  => 1
      case ReadError.ConversionError(_, _, _) => 1
      case ReadError.OrErrors(list, _)        => list.map(_.size).sum
      case ReadError.ZipErrors(list, _)       => list.map(_.size).sum
      case ReadError.ListErrors(_, _)         => 1
      case ReadError.MapErrors(_, _)          => 1
      case ReadError.Irrecoverable(_, _)      => 1
    }

  override def toString: String =
    prettyPrint()
}

object ReadError {
  sealed trait Step[+K]

  object Step {
    final case class Key[+K](key: K)   extends Step[K]
    final case class Index(index: Int) extends Step[Nothing]
  }

  final case class MissingValue[A](
    path: List[Step[A]],
    detail: List[String] = Nil,
    annotations: Set[Annotation] = Set.empty
  ) extends ReadError[A]

  final case class FormatError[A](
    path: List[Step[A]],
    message: String,
    detail: List[String] = Nil,
    annotations: Set[Annotation] = Set.empty
  ) extends ReadError[A]

  final case class ConversionError[A](path: List[Step[A]], message: String, annotations: Set[Annotation] = Set.empty)
      extends ReadError[A]

  final case class Irrecoverable[A](list: List[ReadError[A]], annotations: Set[Annotation] = Set.empty)
      extends ReadError[A]

  final case class OrErrors[A](list: List[ReadError[A]], annotations: Set[Annotation] = Set.empty) extends ReadError[A]

  final case class ZipErrors[A](list: List[ReadError[A]], annotations: Set[Annotation] = Set.empty) extends ReadError[A]

  final case class ListErrors[A](list: List[ReadError[A]], annotations: Set[Annotation] = Set.empty)
      extends ReadError[A]

  final case class MapErrors[A](list: List[ReadError[A]], annotations: Set[Annotation] = Set.empty) extends ReadError[A]
}
