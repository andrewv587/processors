package edu.arizona.sista.odin.domains.bigmechanism.dryrun2015

import edu.arizona.sista.processors.Document
import edu.arizona.sista.struct.Interval
import edu.arizona.sista.odin._

class DarpaActions extends Actions {
  // NOTE these are example actions that should be adapted for the darpa evaluation

  //
  val proteinLabels = Seq("Simple_chemical", "Complex", "Protein", "Protein_with_site", "Gene_or_gene_product", "GENE")
  val simpleProteinLabels = Seq("Protein", "Gene_or_gene_product")
  val siteLabels = Seq("Site", "Protein_with_site")
  val eventLabels = Seq("Phosphorylation", "Exchange", "Hydroxylation", "Ubiquitination", "Binding", "Degradation", "Hydrolysis", "Transcription", "Transport")

  def debug(label: String, mention: Map[String, Seq[Interval]], sent: Int, doc: Document, ruleName: String, state: State): Unit = {
    val debugOut = for (k <- mention.keys) yield s"$k => ${mention(k).flatMap(m => doc.sentences(sent).words.slice(m.start, m.end)).mkString(" ")}"

    println(s"\nArgs for $ruleName: \n\t${debugOut.mkString("\n\t")}\n")
  }

  def mkNERMentions(mentions: Seq[Mention], state: State): Seq[Mention] = {
    mentions flatMap { m =>
      val candidates = state.mentionsFor(m.sentence, m.tokenInterval.toSeq)
      // do any candidates intersect the mention?
      val overlap = candidates.exists(_.tokenInterval.intersects(m.tokenInterval))
      if (overlap) None else Some(m)
    }
  }

  def mkUbiquitination(mentions: Seq[Mention], state: State): Seq[Mention] = {
    mentions.filter { m =>
      !m.arguments.values.flatten.exists(_.text.toLowerCase.startsWith("ubiq")) // Don't allow Ubiquitin
    }
  }

  def mkBinding(mentions: Seq[Mention], state: State): Seq[Mention] = {

    mentions flatMap { m =>
      m match {
        case m: EventMention => {
          val args = m.arguments
          val themes = for {
            name <- args.keys
            if name startsWith "theme"
            theme <- args(name)
          } yield theme
          Seq(new EventMention(m.labels, m.trigger, Map("theme" -> themes.toSeq), m.sentence, m.document, m.keep, m.foundBy))
        }
        case r: RelationMention => Nil
        case _ => Nil
      }
    }
  }

  def mkTextBoundMention(label: String, mention: Map[String, Seq[Interval]], sent: Int, doc: Document, ruleName: String, state: State, keep: Boolean): Seq[Mention] = {
    Seq(new TextBoundMention(label, mention("--GLOBAL--").head, sent, doc, keep, ruleName))
  }

  def mkConversion(label: String, mention: Map[String, Seq[Interval]], sent: Int, doc: Document, ruleName: String, state: State, keep: Boolean): Seq[Mention] = {
    val trigger = new TextBoundMention(label, mention("trigger").head, sent, doc, keep, ruleName)
    val theme = state.mentionsFor(sent, mention("theme").head.start, simpleProteinLabels).head
    val cause = if (mention contains "cause") state.mentionsFor(sent, mention("cause").head.start, simpleProteinLabels).headOption else None
    val args = if (cause.isDefined) Map("Theme" -> Seq(theme), "Cause" -> Seq(cause.get)) else Map("Theme" -> Seq(theme))
    val event = new EventMention(label, trigger, args, sent, doc, keep, ruleName)

    Seq(event)
  }

  def mkComplexEntity(label: String, mention: Map[String, Seq[Interval]], sent: Int, doc: Document, ruleName: String, state: State, keep: Boolean): Seq[Mention] = {
    // construct an event mention from a complex entity like "Protein_with_site"

    val proteins = state.mentionsFor(sent, mention("protein").flatMap(_.toSeq), simpleProteinLabels).distinct
    val sites = state.mentionsFor(sent, mention("site").flatMap(_.toSeq), Seq("Site")).distinct
    val events = for (protein <- proteins; site <- sites) yield new RelationMention(label, Map("Protein" -> Seq(protein), "Site" -> Seq(site)), sent, doc, keep, ruleName)

    events
  }

  def findCoref(state: State, doc: Document, sent: Int, anchor: Interval, lspan: Int = 2, rspan: Int = 0, antType: Seq[String], n: Int = 1): Seq[Mention] = {
    // println(s"attempting coref with type(s) ${antType.mkString(", ")}")

    var leftwd = if (lspan > 0) {
      (math.max(0, anchor.start - lspan) until anchor.start).reverse flatMap (i => state.mentionsFor(sent, i, antType))
    } else Nil
    var lremainder = lspan - anchor.start
    var iter = 1
    while (lremainder > 0 & sent - iter >= 0) {
      leftwd = leftwd ++ ((math.max(0, doc.sentences(sent - iter).size - lremainder) until doc.sentences(sent - iter).size).reverse flatMap (i => state.mentionsFor(sent - iter, i, antType)))
      lremainder = lremainder - doc.sentences(sent - iter).size
      iter += 1
    }

    // println(s"leftward: ${(for (m <- leftwd) yield m.text).mkString(", ")}")

    var rightwd = if (rspan > 0) {
      (anchor.end + 1) to math.min(anchor.end + rspan, doc.sentences(sent).size - 1) flatMap (i => state.mentionsFor(sent, i, antType))
    } else Nil
    var rremainder = rspan - (doc.sentences(sent).size - anchor.end)
    iter = 1
    while (rremainder > 0 & sent + iter < doc.sentences.length) {
      rightwd = rightwd ++ (0 until math.min(rremainder, doc.sentences(sent + iter).size) flatMap (i => state.mentionsFor(sent + iter, i, antType)))
      rremainder = rremainder - doc.sentences(sent + iter).size
      iter += 1
    }

    // println(s"rightward: ${(for (m <- rightwd) yield m.text).mkString(", ")}")

    val leftright = (leftwd ++ rightwd).distinct
    val adcedentMentions = if (leftright.nonEmpty) Some(leftright.slice(0, n))
    else None

    if (adcedentMentions.isDefined) {
      // println(s"${doc.sentences(sent).getSentenceText()}\n${(for (m <- adcedentMentions.get) yield m.text).mkString(", ")}\n\n")
      adcedentMentions.get
    } else {
      // println("None found")
      Nil
    }
  }

  def findCoref(state: State, doc: Document, sent: Int, anchor: Interval, lspan: Int, rspan: Int, antType: Seq[String], n: String): Seq[Mention] = {
    // println(s"attempting coref with quantifier ${n}")
    // our lookup for unresolved mention counts
    val numMap = Map("a" -> 1,
      "an" -> 1,
      "the" -> 1, // assume one for now...
      "both" -> 2,
      "these" -> 2, // assume two for now...
      "this" -> 1,
      "some" -> 3, // assume three for now...
      "one" -> 1,
      "two" -> 2,
      "three" -> 3)

    def retrieveInt(somenum: String): Int = {
      def finalAttempt(num: String): Int = try {
        num.toInt
      } catch {
        case e: NumberFormatException => 1
      }
      numMap.getOrElse(somenum, finalAttempt(somenum))
    }

    findCoref(state, doc, sent, anchor, lspan, rspan, antType, retrieveInt(n.toLowerCase))
  }

  def meldMentions(mention: Map[String, Seq[Interval]]): Interval = {
    val range = (for (i: Interval <- mention.values.toSet.toSeq.flatten) yield Seq(i.start, i.end)).flatten.sorted
    new Interval(range.head, range.last)
  }

  def mkBindingCorefEvent(label: String, mention: Map[String, Seq[Interval]], sent: Int, doc: Document, ruleName: String, state: State, keep: Boolean): Seq[Mention] = {
    val trigger = new TextBoundMention(label, mention("trigger").head, sent, doc, keep, ruleName)
    val themes = for {
      name <- mention.keys
      if name startsWith "theme"
      m <- mention(name)
      theme <- state.mentionsFor(sent, m.start, "Simple_chemical" +: simpleProteinLabels)
    } yield theme

    val corefThemes = if (mention("endophor").nonEmpty & mention("quantifier").nonEmpty) {
      val endophorSpan = mention("quantifier").head
      val endophorText = doc.sentences(sent).words.slice(endophorSpan.start,endophorSpan.end).mkString("")
      findCoref(state, doc, sent, meldMentions(mention), 7, 0, "Simple_chemical" +: simpleProteinLabels, endophorText)
    } else if (mention("endophor").nonEmpty) {
      val endophorSpan = mention("endophor").head
      val endophorText = doc.sentences(sent).words.slice(endophorSpan.start,endophorSpan.end).mkString("")
      // println(endophorText)
      findCoref(state, doc, sent, meldMentions(mention), 7, 0, "Simple_chemical" +: simpleProteinLabels, endophorText)
    } else Nil
    val allThemes = themes ++ corefThemes
    val args = Map("Theme" -> allThemes.toSeq.distinct)
    val event = new EventMention(label, trigger, args, sent, doc, keep, ruleName)
    Seq(event)
  }

  def mkExchange(label: String, mention: Map[String, Seq[Interval]], sent: Int, doc: Document, ruleName: String, state: State, keep: Boolean): Seq[Mention] = {
    val trigger = new TextBoundMention(label, mention("trigger").head, sent, doc, keep, ruleName)
    val theme1 = mention("theme1") flatMap (m => state.mentionsFor(sent, m.start, "Simple_chemical"))
    val theme2 = mention("theme2") flatMap (m => state.mentionsFor(sent, m.start, "Simple_chemical"))
    val startGoals = for {
      name <- mention.keys
      if name startsWith "goal"
      m <- mention(name)
      goal <- state.mentionsFor(sent, m.start, proteinLabels)
    } yield goal
    val corefGoals = if (startGoals.isEmpty) findCoref(state,doc,sent,meldMentions(mention),4,3,proteinLabels,1)
    else Nil
    val goals = startGoals ++ corefGoals
    val causes = mention.getOrElse("cause", Nil) flatMap (m => state.mentionsFor(sent, m.start, proteinLabels))
    val events = trigger match {
      case hasCausehasGoal if causes.nonEmpty & goals.nonEmpty => for (t1 <- theme1; t2 <- theme2; cause <- causes; goal <- goals) yield new EventMention(label, trigger, Map("Theme1" -> Seq(t1), "Theme2" -> Seq(t2), "Goal" -> Seq(goal), "Cause" -> Seq(cause)), sent, doc, keep, ruleName)
      case noCausehasGoal if causes.isEmpty & goals.nonEmpty => for (t1 <- theme1; t2 <- theme2; goal <- goals) yield new EventMention(label, trigger, Map("Theme1" -> Seq(t1), "Theme2" -> Seq(t2), "Goal" -> Seq(goal)), sent, doc, keep, ruleName)
      case hasCausenoGoal if causes.nonEmpty & goals.isEmpty => for (t1 <- theme1; t2 <- theme2; cause <- causes) yield new EventMention(label, trigger, Map("Theme1" -> Seq(t1), "Theme2" -> Seq(t2), "Cause" -> Seq(cause)), sent, doc, keep, ruleName)
      case noCausenoGoal if causes.isEmpty & goals.isEmpty => for (t1 <- theme1; t2 <- theme2) yield new EventMention(label, trigger, Map("Theme1" -> Seq(t1), "Theme2" -> Seq(t2)), sent, doc, keep, ruleName)
    }
    events
  }

  def mkDegradation(label: String, mention: Map[String, Seq[Interval]], sent: Int, doc: Document, ruleName: String, state: State, keep: Boolean): Seq[Mention] = {
    val trigger = new TextBoundMention(label, mention("trigger").head, sent, doc, keep, ruleName)
    val themes = mention("theme") flatMap (m => state.mentionsFor(sent, m.start, simpleProteinLabels))
    val causes = mention("cause") flatMap (m => state.mentionsFor(sent, m.start, simpleProteinLabels))
    val events = for (theme <- themes; cause <- causes) yield new EventMention(label, trigger, Map("Theme" -> Seq(theme), "Cause" -> Seq(cause)), sent, doc, keep, ruleName)
    events
  }

  def mkHydrolysis(label: String, mention: Map[String, Seq[Interval]], sent: Int, doc: Document, ruleName: String, state: State, keep: Boolean): Seq[Mention] = {
    val trigger = new TextBoundMention(label, mention("trigger").head, sent, doc, keep, ruleName)
    val themes = if (mention contains "theme") mention("theme") flatMap (m => state.mentionsFor(sent, m.start, proteinLabels))
    else Nil
    // else findCoref(state,doc,sent,meldMentions(mention),10,2,Seq("Simple_chemical"),1)
    val proteins = if (mention contains "protein") state.mentionsFor(sent, mention("protein").map(_.start), proteinLabels)
    // else if (themes.isEmpty) Nil
    else findCoref(state,doc,sent,meldMentions(mention),1,7,"Complex" +: simpleProteinLabels,1)
    val causes = if (mention contains "cause") mention("cause") flatMap (m => state.mentionsFor(sent, m.start, simpleProteinLabels))
    else Nil

    if (themes.isEmpty & proteins.isEmpty) return Nil

    val complexes = if (proteins.nonEmpty & themes.nonEmpty) for (protein <- proteins; theme <- themes) yield new RelationMention("Complex", Map("Participant" -> Seq(protein, theme)), sent, doc, keep, ruleName)
    else Nil

    val events = trigger match {
      case hasComplexhasCause if complexes.nonEmpty & causes.nonEmpty => for (complex <- complexes; cause <- causes) yield new EventMention(label, trigger, Map("Theme" -> Seq(complex), "Cause" -> Seq(cause)), sent, doc, keep, ruleName)
      case hasComplexnoCause if complexes.nonEmpty & causes.isEmpty => for (complex <- complexes) yield new EventMention(label, trigger, Map("Theme" -> Seq(complex)), sent, doc, keep, ruleName)
      case hasThemehasCause if complexes.isEmpty & themes.nonEmpty & causes.nonEmpty => for (theme <- themes; cause <- causes) yield new EventMention(label, trigger, Map("Theme" -> Seq(theme), "Cause" -> Seq(cause)), sent, doc, keep, ruleName)
      case hasThemenoCause if complexes.isEmpty & themes.nonEmpty & causes.isEmpty => for (theme <- themes) yield new EventMention(label, trigger, Map("Theme" -> Seq(theme)), sent, doc, keep, ruleName)
      case noThemeHasProteinhasCause if complexes.isEmpty & themes.isEmpty & proteins.nonEmpty & causes.nonEmpty => for (protein <- proteins; cause <- causes) yield new EventMention(label, trigger, Map("Theme" -> Seq(protein), "Cause" -> Seq(cause)), sent, doc, keep, ruleName)
      case noThemeHasProteinnoCause if complexes.isEmpty & themes.isEmpty & proteins.nonEmpty & causes.isEmpty => for (protein <- proteins) yield new EventMention(label, trigger, Map("Theme" -> Seq(protein)), sent, doc, keep, ruleName)
      case _ => Nil
    }

    events
  }

  def mkTransport(label: String, mention: Map[String, Seq[Interval]], sent: Int, doc: Document, ruleName: String, state: State, keep: Boolean): Seq[Mention] = {

    //debug(label, mention, sent, doc, ruleName, state)

    val trigger = new TextBoundMention(label, mention("trigger").head, sent, doc, keep, ruleName)
    val theme = state.mentionsFor(sent, mention("theme").head.start, Seq("Protein", "Gene_or_gene_product", "Small_molecule"))

    val src = if ((mention contains "source") && !mention("source").isEmpty) state.mentionsFor(sent, mention("source").head.start, Seq("Cellular_component")) else Seq()

    //val dst = mention.getOrElse("destination", Nil) flatMap (m => state.mentionsFor(sent, m.start, Seq("Cellular_component")))
    val dst = if ((mention contains "destination") && !mention("destination").isEmpty) mention("destination") flatMap (m => state.mentionsFor(sent, m.start, Seq("Cellular_component"))) else Seq()

    val args = Map("Theme" -> theme, "Source" -> src, "Destination" -> dst)
    val event = new EventMention(label, trigger, args, sent, doc, keep, ruleName)

    Seq(event)
  }

}
