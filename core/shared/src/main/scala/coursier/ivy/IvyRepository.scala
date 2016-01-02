package coursier.ivy

import coursier.Fetch
import coursier.core._
import scala.annotation.tailrec
import scala.util.matching.Regex
import scalaz._
import java.util.regex.Pattern.quote

object IvyRepository {

  val optionalPartRegex = (quote("(") + "[^" + quote("{()}") + "]*" + quote(")")).r
  val variableRegex = (quote("[") + "[^" + quote("{[()]}") + "]*" + quote("]")).r
  val propertyRegex = (quote("${") + "[^" + quote("{[()]}") + "]*" + quote("}")).r

  sealed abstract class PatternPart(val effectiveStart: Int, val effectiveEnd: Int) extends Product with Serializable {
    require(effectiveStart <= effectiveEnd)
    def start = effectiveStart
    def end = effectiveEnd

    // FIXME Some kind of validation should be used here, to report all the missing variables,
    //       not only the first one missing.
    def apply(content: String): Map[String, String] => String \/ String
  }
  object PatternPart {
    case class Literal(override val effectiveStart: Int, override val effectiveEnd: Int) extends PatternPart(effectiveStart, effectiveEnd) {
      def apply(content: String): Map[String, String] => String \/ String = {
        assert(content.length == effectiveEnd - effectiveStart)
        val matches = variableRegex.findAllMatchIn(content).toList

        variables =>
          @tailrec
          def helper(idx: Int, matches: List[Regex.Match], b: StringBuilder): String \/ String =
            if (idx >= content.length)
              \/-(b.result())
            else {
              assert(matches.headOption.forall(_.start >= idx))
              matches.headOption.filter(_.start == idx) match {
                case Some(m) =>
                  val variableName = content.substring(m.start + 1, m.end - 1)
                  variables.get(variableName) match {
                    case None => -\/(s"Variable not found: $variableName")
                    case Some(value) =>
                      b ++= value
                      helper(m.end, matches.tail, b)
                  }
                case None =>
                  val nextIdx = matches.headOption.fold(content.length)(_.start)
                  b ++= content.substring(idx, nextIdx)
                  helper(nextIdx, matches, b)
              }
            }

          helper(0, matches, new StringBuilder)
      }
    }
    case class Optional(start0: Int, end0: Int) extends PatternPart(start0 + 1, end0 - 1) {
      override def start = start0
      override def end = end0

      def apply(content: String): Map[String, String] => String \/ String = {
        assert(content.length == effectiveEnd - effectiveStart)
        val inner = Literal(effectiveStart, effectiveEnd).apply(content)

        variables =>
          \/-(inner(variables).fold(_ => "", x => x))
      }
    }
  }

  def substituteProperties(s: String, properties: Map[String, String]): String =
    propertyRegex.findAllMatchIn(s).toVector.foldRight(s) { case (m, s0) =>
      val key = s0.substring(m.start + "${".length, m.end - "}".length)
      val value = properties.getOrElse(key, "")
      s0.take(m.start) + value + s0.drop(m.end)
    }

}

case class IvyRepository(
  pattern: String,
  changing: Option[Boolean] = None,
  properties: Map[String, String] = Map.empty,
  withChecksums: Boolean = true,
  withSignatures: Boolean = true,
  withArtifacts: Boolean = true
) extends Repository {

  import Repository._
  import IvyRepository._

  private val pattern0 = substituteProperties(pattern, properties)

  val parts = {
    val optionalParts = optionalPartRegex.findAllMatchIn(pattern0).toList.map { m =>
      PatternPart.Optional(m.start, m.end)
    }

    val len = pattern0.length

    @tailrec
    def helper(
      idx: Int,
      opt: List[PatternPart.Optional],
      acc: List[PatternPart]
    ): Vector[PatternPart] =
      if (idx >= len)
        acc.toVector.reverse
      else
        opt match {
          case Nil =>
            helper(len, Nil, PatternPart.Literal(idx, len) :: acc)
          case (opt0 @ PatternPart.Optional(start0, end0)) :: rem =>
            if (idx < start0)
              helper(start0, opt, PatternPart.Literal(idx, start0) :: acc)
            else {
              assert(idx == start0, s"idx: $idx, start0: $start0")
              helper(end0, rem, opt0 :: acc)
            }
        }

    helper(0, optionalParts, Nil)
  }

  assert(pattern0.isEmpty == parts.isEmpty)
  if (pattern0.nonEmpty) {
    for ((a, b) <- parts.zip(parts.tail))
      assert(a.end == b.start)
    assert(parts.head.start == 0)
    assert(parts.last.end == pattern0.length)
  }

  private val substituteHelpers = parts.map { part =>
    part(pattern0.substring(part.effectiveStart, part.effectiveEnd))
  }

  def substitute(variables: Map[String, String]): String \/ String =
    substituteHelpers.foldLeft[String \/ String](\/-("")) {
      case (acc0, helper) =>
        for {
          acc <- acc0
          s <- helper(variables)
        } yield acc + s
    }

  // See http://ant.apache.org/ivy/history/latest-milestone/concept.html for a
  // list of variables that should be supported.
  // Some are missing (branch, conf, originalName).
  private def variables(
    module: Module,
    version: String,
    `type`: String,
    artifact: String,
    ext: String,
    classifierOpt: Option[String]
  ) =
    Map(
      "organization" -> module.organization,
      "organisation" -> module.organization,
      "orgPath" -> module.organization.replace('.', '/'),
      "module" -> module.name,
      "revision" -> version,
      "type" -> `type`,
      "artifact" -> artifact,
      "ext" -> ext
    ) ++ module.attributes ++ classifierOpt.map("classifier" -> _).toSeq


  val source: Artifact.Source =
    if (withArtifacts)
      new Artifact.Source {
        def artifacts(
          dependency: Dependency,
          project: Project,
          overrideClassifiers: Option[Seq[String]]
        ) = {

          val retained =
            overrideClassifiers match {
              case None =>
                project.publications.collect {
                  case (conf, p)
                    if conf == "*" ||
                       conf == dependency.configuration ||
                       project.allConfigurations.getOrElse(dependency.configuration, Set.empty).contains(conf) =>
                    p
                }
              case Some(classifiers) =>
                val classifiersSet = classifiers.toSet
                project.publications.collect {
                  case (_, p) if classifiersSet(p.classifier) =>
                    p
                }
            }

          val retainedWithUrl = retained.flatMap { p =>
            substitute(variables(
              dependency.module,
              dependency.version,
              p.`type`,
              p.name,
              p.ext,
              Some(p.classifier).filter(_.nonEmpty)
            )).toList.map(p -> _)
          }

          retainedWithUrl.map { case (p, url) =>
            var artifact = Artifact(
              url,
              Map.empty,
              Map.empty,
              p.attributes,
              changing = changing.getOrElse(project.version.contains("-SNAPSHOT")) // could be more reliable
            )

            if (withChecksums)
              artifact = artifact.withDefaultChecksums
            if (withSignatures)
              artifact = artifact.withDefaultSignature

            artifact
          }
        }
      }
    else
      Artifact.Source.empty


  def find[F[_]](
    module: Module,
    version: String,
    fetch: Fetch.Content[F]
  )(implicit
    F: Monad[F]
  ): EitherT[F, String, (Artifact.Source, Project)] = {

    val eitherArtifact: String \/ Artifact =
      for {
        url <- substitute(
          variables(module, version, "ivy", "ivy", "xml", None)
        )
      } yield {
        var artifact = Artifact(
          url,
          Map.empty,
          Map.empty,
          Attributes("ivy", ""),
          changing = changing.getOrElse(version.contains("-SNAPSHOT"))
        )

        if (withChecksums)
          artifact = artifact.withDefaultChecksums
        if (withSignatures)
          artifact = artifact.withDefaultSignature

        artifact
      }

    for {
      artifact <- EitherT(F.point(eitherArtifact))
      ivy <- fetch(artifact)
      proj <- EitherT(F.point {
        for {
          xml <- \/.fromEither(compatibility.xmlParse(ivy))
          _ <- if (xml.label == "ivy-module") \/-(()) else -\/("Module definition not found")
          proj <- IvyXml.project(xml)
        } yield proj
      })
    } yield (source, proj)
  }

}