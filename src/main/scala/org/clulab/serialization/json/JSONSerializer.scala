package org.clulab.serialization.json

import java.io.File
import org.clulab.odin
import org.clulab.odin._
import org.clulab.processors.{Document, Sentence}
import org.clulab.struct.{DirectedGraph, Edge, GraphMap, Interval}
import org.json4s.JsonDSL._
import org.json4s._
import org.json4s.native.JsonMethods._


/** JSON serialization utilities */
object JSONSerializer {

  implicit val formats = DefaultFormats

  def jsonAST(mentions: Seq[Mention]): JValue = {
    val docsMap = mentions.map(m => m.document.equivalenceHash.toString -> m.document.jsonAST).toMap
    val mentionList = JArray(mentions.map(_.jsonAST).toList)

    ("documents" -> docsMap) ~
    ("mentions" -> mentionList)
  }

  def jsonAST(f: File): JValue = parse(scala.io.Source.fromFile(f).getLines.mkString)

  /** Produce a sequence of mentions from json */
  def toMentions(json: JValue): Seq[Mention] = {

    require(json \ "documents" != JNothing, "\"documents\" key missing from json")
    require(json \ "mentions" != JNothing, "\"mentions\" key missing from json")

    val djson = json \ "documents"
    val mmjson = (json \ "mentions").asInstanceOf[JArray]

    mmjson.arr.map(mjson => toMention(mjson, djson))
  }
  /** Produce a sequence of mentions from a json file */
  def toMentions(file: File): Seq[Mention] = toMentions(jsonAST(file))

  /** Build mention from json of mention and corresponding json map of documents <br>
    * Since a single Document can be quite large and may be shared by multiple mentions,
    * only a reference to the document json is contained within each mention.
    * A map from doc reference to document json is used to avoid redundancies and reduce file size during serialization.
    * */
  def toMention(mjson: JValue, djson: JValue): Mention = {

    val tokInterval = Interval(
      (mjson \ "tokenInterval" \ "start").extract[Int],
      (mjson \ "tokenInterval" \ "end").extract[Int]
    )
    // elements shared by all Mention types
    val labels = (mjson \ "labels").extract[List[String]]
    val sentence = (mjson \ "sentence").extract[Int]
    val docHash = (mjson \ "document").extract[String]
    val document = toDocument(docHash, djson)
    val keep = (mjson \ "keep").extract[Boolean]
    val foundBy = (mjson \ "foundBy").extract[String]

    def mkArgumentsFromJsonAST(json: JValue): Map[String, Seq[Mention]] = try {
      val args = json.extract[Map[String, JArray]]
      val argPairs = for {
        (k: String, v: JValue) <- args
        mns: Seq[Mention] = v.arr.map(m => toMention(m, djson))
      } yield (k, mns)
      argPairs
    } catch {
      case e: org.json4s.MappingException => Map.empty[String, Seq[Mention]]
    }



    /** Build mention paths from json */
    def toPaths(json: JValue, djson: JValue): Map[String, Map[Mention, odin.SynPath]] = {

      /** Create mention from args json for given id */
      def findMention(mentionID: String, json: JValue, djson: JValue): Option[Mention] = {
        // inspect arguments for matching ID
        json \ "arguments" match {
          // if we don't have arguments, we can't produce a Mention
          case JNothing => None
          // Ahoy! There be args!
          case something =>
            // flatten the Seq[Mention.jsonAST] for each arg
            val argsjson: Iterable[JValue] = for {
              mnsjson: JArray <- something.extract[Map[String, JArray]].values
              mjson <- mnsjson.arr
              if (mjson \ "id").extract[String] == mentionID
            } yield mjson

            argsjson.toList match {
              case Nil => None
              case j :: _ => Some(toMention(j, djson))
            }
        }
      }

      // build paths
      json \ "paths" match {
        case JNothing => Map.empty[String, Map[Mention, odin.SynPath]]
        case contents => for {
          (role, innermap) <- contents.extract[Map[String, Map[String, JValue]]]
        } yield {
          // make inner map (Map[Mention, odin.SynPath])
          val pathMap = for {
            (mentionID: String, pathJSON: JValue) <- innermap.toSeq
            mOp = findMention(mentionID, json, djson)
            // were we able to recover a mention?
            if mOp.nonEmpty
            m = mOp.get
            edges: Seq[Edge[String]] = pathJSON.extract[Seq[Edge[String]]]
            synPath: odin.SynPath = DirectedGraph.edgesToTriples[String](edges)
          } yield m -> synPath
          // marry role with (arg -> path) info
          role -> pathMap.toMap
        }
      }
    }

    // build Mention
    mjson \ "type" match {
      case JString(EventMention.string) =>
        new EventMention(
          labels,
          tokInterval,
          // trigger must be TextBoundMention
          toMention(mjson \ "trigger", djson).asInstanceOf[TextBoundMention],
          mkArgumentsFromJsonAST(mjson \ "arguments"),
          paths = toPaths(mjson, djson),
          sentence,
          document,
          keep,
          foundBy
        )
      case JString(RelationMention.string) =>
        new RelationMention(
          labels,
          tokInterval,
          mkArgumentsFromJsonAST(mjson \ "arguments"),
          paths = toPaths(mjson, djson),
          sentence,
          document,
          keep,
          foundBy
        )
      case JString(TextBoundMention.string) =>
        new TextBoundMention(
          labels,
          tokInterval,
          sentence,
          document,
          keep,
          foundBy
        )
      case other => throw new Exception(s"unrecognized mention type '${other.toString}'")
    }
  }

  def toDocument(json: JValue): Document = {
    // recover sentences
    val sentences = (json \ "sentences").asInstanceOf[JArray].arr.map(sjson => toSentence(sjson)).toArray
    // initialize document
    val d = Document(sentences)
    // update id
    d.id = getStringOption(json, "id")
    // update text
    d.text = getStringOption(json, "text")
    d
  }
  def toDocument(docHash: String, djson: JValue): Document = toDocument(djson \ docHash)
  def toDocument(f: File): Document = toDocument(jsonAST(f))

  def toSentence(json: JValue): Sentence = {

    def getLabels(json: JValue, k: String): Option[Array[String]] = json \ k match {
      case JNothing => None
      case contents => Some(contents.extract[Array[String]])
    }

    val s = json.extract[Sentence]
    // build dependencies
    val graphs = (json \ "graphs").extract[Map[String, DirectedGraph[String]]]
    s.dependenciesByType = GraphMap(graphs)
    // build labels
    s.tags = getLabels(json, "tags")
    s.lemmas = getLabels(json, "lemmas")
    s.entities = getLabels(json, "entities")
    s.norms = getLabels(json, "norms")
    s.chunks = getLabels(json, "chunks")
    s
  }

  private def getStringOption(json: JValue, key: String): Option[String] = json \ key match {
    case JString(s) => Some(s)
    case _ => None
  }
}
